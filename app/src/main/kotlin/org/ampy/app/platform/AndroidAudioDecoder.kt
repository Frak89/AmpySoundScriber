package org.ampy.app.platform

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.ampy.audioengine.RawFloatFile
import org.ampy.core.AmpyLog
import org.ampy.core.JobContext
import org.ampy.core.LogCategory
import java.io.File
import java.nio.ByteOrder

/**
 * Decodifica MP3, WAV, FLAC, OGG/Opus, M4A/AAC (e ogni formato supportato dai codec del dispositivo)
 * in un file PCM float32 su disco, in streaming: la memoria usata non dipende dalla durata del brano.
 */
class AndroidAudioDecoder(private val context: Context) {

    data class Decoded(val pcm: RawFloatFile, val mime: String, val sampleRate: Int, val channels: Int, val durationSec: Double)

    class UnsupportedAudioException(msg: String) : Exception(msg)

    fun decode(uri: Uri, out: File, ctx: JobContext): Decoded {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("Nessuna traccia audio nel file")
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else -1L
            AmpyLog.i(LogCategory.AUDIO_IMPORT, "decoder", "Importazione $uri: mime=$mime durata=${durationUs / 1e6}s formato=$inFormat")

            val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw UnsupportedAudioException("Decoder non disponibile sul dispositivo per $mime")
            }
            // richiesta di uscita float (supportata dai codec Codec2 moderni); altrimenti si converte da PCM 16 bit
            inFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            try { codec.configure(inFormat, null, null, 0) } catch (e: Exception) {
                inFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                codec.configure(inFormat, null, null, 0)
            }
            codec.start()

            var writer: RawFloatFile.Writer? = null
            var sampleRate = inFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = inFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var lastProgress = 0L
            try {
                while (!outputDone) {
                    ctx.cancel.throwIfCancelled()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val t = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, size, t, 0)
                                extractor.advance()
                                if (durationUs > 0 && t - lastProgress > 500_000) {
                                    lastProgress = t
                                    ctx.progress.report((t.toDouble() / durationUs).coerceIn(0.0, 1.0), "Decodifica audio ($mime)")
                                }
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = f.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            encoding = f.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            AmpyLog.d(LogCategory.AUDIO_IMPORT, "decoder", "Formato di uscita: $sampleRate Hz, $channels canali, encoding $encoding")
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)
                            if (buf != null && info.size > 0) {
                                if (writer == null) writer = RawFloatFile.Writer(out, channels)
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                buf.order(ByteOrder.nativeOrder())
                                val floats = when (encoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(info.size / 4).also { buf.asFloatBuffer().get(it) }
                                    AudioFormat.ENCODING_PCM_16BIT -> { val sb = buf.asShortBuffer(); FloatArray(sb.remaining()) { sb.get() / 32768f } }
                                    AudioFormat.ENCODING_PCM_8BIT -> FloatArray(info.size) { ((buf.get().toInt() and 0xff) - 128) / 128f }
                                    else -> throw UnsupportedAudioException("Codifica PCM $encoding non gestita")
                                }
                                writer.writeInterleaved(floats, floats.size / channels)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                writer?.close()
                runCatching { codec.stop() }; codec.release()
            }
            if (writer == null) throw UnsupportedAudioException("Il decoder non ha prodotto campioni")
            val pcm = RawFloatFile(out, sampleRate, channels)
            ctx.progress.report(1.0, "Decodifica completata")
            AmpyLog.i(LogCategory.AUDIO_IMPORT, "decoder", "Decodificati ${pcm.frameCount} frame (${"%.1f".format(pcm.durationSec)} s)")
            return Decoded(pcm, mime, sampleRate, channels, pcm.durationSec)
        } catch (e: UnsupportedAudioException) {
            AmpyLog.e(LogCategory.AUDIO_IMPORT, "decoder", e.message ?: "errore", e); throw e
        } catch (e: org.ampy.core.CancelledException) {
            throw e
        } catch (e: Exception) {
            AmpyLog.e(LogCategory.AUDIO_IMPORT, "decoder", "Importazione fallita: ${e.javaClass.simpleName}: ${e.message}", e)
            throw UnsupportedAudioException("Impossibile decodificare il file: ${e.message}")
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, def: Int) = if (containsKey(key)) getInteger(key) else def
}
