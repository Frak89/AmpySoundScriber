package org.ampy.audioengine

import org.ampy.core.JobContext
import java.io.File

/** Conversioni in streaming (memoria costante) per file di qualsiasi durata. */
object PcmConvert {
    /**
     * Converte [src] in mono a [dstRate] Hz scrivendo su [out] (.f32). Restituisce la sorgente risultante.
     * Il progresso è reale (frame letti / frame totali).
     */
    fun toMono(src: PcmSource, dstRate: Int, out: File, ctx: JobContext = JobContext(), block: Int = 1 shl 16): RawFloatFile {
        val rs = Resampler(src.sampleRate, dstRate)
        RawFloatFile.Writer(out, 1).use { w ->
            var pos = 0L
            while (pos < src.frameCount) {
                ctx.cancel.throwIfCancelled()
                val mono = src.readMono(pos, block)
                if (mono.isEmpty()) break
                pos += mono.size
                val y = rs.processBlock(mono, last = pos >= src.frameCount)
                if (y.isNotEmpty()) w.writeInterleaved(y)
                ctx.progress.report(pos.toDouble() / src.frameCount, "Ricampionamento a $dstRate Hz")
            }
        }
        return RawFloatFile(out, dstRate, 1)
    }

    /** Ricampiona mantenendo i canali (es. stereo 48 kHz -> 44,1 kHz per un modello di separazione). */
    fun toRate(src: PcmSource, dstRate: Int, out: File, channels: Int = src.channelCount, ctx: JobContext = JobContext(), block: Int = 1 shl 16): RawFloatFile {
        val rs = Array(channels) { Resampler(src.sampleRate, dstRate) }
        RawFloatFile.Writer(out, channels).use { w ->
            var pos = 0L
            while (pos < src.frameCount) {
                ctx.cancel.throwIfCancelled()
                val chs = src.readChannels(pos, block)
                val n = chs.firstOrNull()?.size ?: 0
                if (n == 0) break
                pos += n
                val last = pos >= src.frameCount
                val ys = Array(channels) { c -> rs[c].processBlock(chs[minOf(c, chs.size - 1)], last) }
                val m = ys.minOf { it.size }
                if (m > 0) {
                    val inter = FloatArray(m * channels)
                    for (i in 0 until m) for (c in 0 until channels) inter[i * channels + c] = ys[c][i]
                    w.writeInterleaved(inter, m)
                }
                ctx.progress.report(pos.toDouble() / src.frameCount, "Ricampionamento a $dstRate Hz")
            }
        }
        return RawFloatFile(out, dstRate, channels)
    }
}
