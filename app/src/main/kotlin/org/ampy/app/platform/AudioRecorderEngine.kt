package org.ampy.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.ampy.audioengine.RawFloatFile
import org.ampy.core.AmpyLog
import org.ampy.core.LogCategory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Registrazione dal microfono in float32 su disco (nessun limite di durata oltre lo spazio libero). */
class AudioRecorderEngine(private val context: Context) {
    val sampleRate = 48000
    @Volatile var level: Float = 0f; private set
    @Volatile var framesRecorded: Long = 0; private set
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    val isRecording get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(out: File) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Permesso microfono non concesso" }
        if (!running.compareAndSet(false, true)) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // UNPROCESSED evita AGC/soppressione rumore quando disponibile; altrimenti MIC
        val source = if (isUnprocessedSupported()) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf * 4, 16384))
            .build()
        framesRecorded = 0
        worker = thread(name = "ampy-recorder") {
            val buf = FloatArray(2048)
            try {
                RawFloatFile.Writer(out, 1).use { w ->
                    rec.startRecording()
                    AmpyLog.i(LogCategory.AUDIO_IMPORT, "recorder", "Registrazione avviata (sorgente $source, $sampleRate Hz)")
                    while (running.get()) {
                        val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                        if (n < 0) { AmpyLog.e(LogCategory.AUDIO_IMPORT, "recorder", "AudioRecord.read errore $n"); break }
                        if (n > 0) {
                            w.writeInterleaved(buf, n)
                            framesRecorded += n
                            var pk = 0f; for (i in 0 until n) { val a = kotlin.math.abs(buf[i]); if (a > pk) pk = a }
                            level = pk
                        }
                    }
                }
            } catch (e: Exception) {
                AmpyLog.e(LogCategory.AUDIO_IMPORT, "recorder", "Registrazione interrotta: ${e.message}", e)
            } finally {
                runCatching { rec.stop() }; rec.release(); running.set(false); level = 0f
            }
        }
    }

    fun stop() { running.set(false); worker?.join(2000); worker = null }

    private fun isUnprocessedSupported(): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }.getOrDefault(false)
}
