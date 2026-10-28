package org.ampy.audioengine

import org.ampy.core.ModuleInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object AudioEngineModule {
    val info = ModuleInfo("AmpyAudioEngine", "0.1.0", "DSP, I/O WAV/PCM, ricampionamento, segmentazione, mixer")
}

/** Buffer audio in memoria, canali non interlacciati. */
class AudioBuffer(val sampleRate: Int, val channels: Array<FloatArray>) {
    val channelCount get() = channels.size
    val frames get() = channels.firstOrNull()?.size ?: 0
    val durationSec get() = frames.toDouble() / sampleRate

    fun mono(): FloatArray {
        if (channels.size == 1) return channels[0]
        val n = frames
        return FloatArray(n) { i -> var s = 0f; for (c in channels) s += c[i]; s / channels.size }
    }

    companion object {
        fun mono(sampleRate: Int, data: FloatArray) = AudioBuffer(sampleRate, arrayOf(data))
    }
}

/**
 * Sorgente PCM ad accesso casuale. Le implementazioni su disco permettono di elaborare brani
 * di ore senza caricarli in RAM.
 */
interface PcmSource : AutoCloseable {
    val sampleRate: Int
    val channelCount: Int
    val frameCount: Long
    /** Legge [count] frame a partire da [start] nel buffer interlacciato [out] (count*channels). Ritorna i frame letti. */
    fun read(start: Long, count: Int, out: FloatArray): Int
    val durationSec: Double get() = frameCount.toDouble() / sampleRate

    /** Legge un intervallo come mono. */
    fun readMono(start: Long, count: Int): FloatArray {
        val n = minOf(count.toLong(), frameCount - start).toInt().coerceAtLeast(0)
        val inter = FloatArray(n * channelCount)
        val got = read(start, n, inter)
        val ch = channelCount
        return FloatArray(got) { i -> var s = 0f; for (c in 0 until ch) s += inter[i * ch + c]; s / ch }
    }
    /** Legge un intervallo mantenendo i canali (non interlacciati). */
    fun readChannels(start: Long, count: Int): Array<FloatArray> {
        val n = minOf(count.toLong(), frameCount - start).toInt().coerceAtLeast(0)
        val inter = FloatArray(n * channelCount)
        val got = read(start, n, inter)
        return Array(channelCount) { c -> FloatArray(got) { i -> inter[i * channelCount + c] } }
    }
}

class MemoryPcmSource(private val buf: AudioBuffer) : PcmSource {
    override val sampleRate = buf.sampleRate
    override val channelCount = buf.channelCount
    override val frameCount = buf.frames.toLong()
    override fun read(start: Long, count: Int, out: FloatArray): Int {
        val s = start.toInt()
        val n = minOf(count, buf.frames - s).coerceAtLeast(0)
        for (i in 0 until n) for (c in 0 until channelCount) out[i * channelCount + c] = buf.channels[c][s + i]
        return n
    }
    override fun close() {}
}

/**
 * File PCM float32 little-endian interlacciato (formato cache interno, ".f32").
 * Il decoder Android scrive qui i campioni man mano che decodifica.
 */
class RawFloatFile(val file: File, override val sampleRate: Int, override val channelCount: Int) : PcmSource {
    private val raf = RandomAccessFile(file, "r")
    private val ch: FileChannel = raf.channel
    override val frameCount: Long get() = ch.size() / (4L * channelCount)

    override fun read(start: Long, count: Int, out: FloatArray): Int {
        val avail = (frameCount - start).coerceAtLeast(0)
        val n = minOf(count.toLong(), avail).toInt()
        if (n == 0) return 0
        val bb = ByteBuffer.allocate(n * channelCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        var pos = start * channelCount * 4
        while (bb.hasRemaining()) { val r = ch.read(bb, pos); if (r <= 0) break; pos += r }
        bb.flip()
        bb.asFloatBuffer().get(out, 0, n * channelCount)
        return n
    }
    override fun close() { raf.close() }

    /** Writer incrementale. */
    class Writer(val file: File, val channelCount: Int) : AutoCloseable {
        private val raf = RandomAccessFile(file, "rw").also { it.setLength(0) }
        private val ch = raf.channel
        var framesWritten = 0L; private set
        fun writeInterleaved(data: FloatArray, frames: Int = data.size / channelCount) {
            val bb = ByteBuffer.allocate(frames * channelCount * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(data, 0, frames * channelCount)
            while (bb.hasRemaining()) ch.write(bb)
            framesWritten += frames
        }
        override fun close() { ch.force(false); raf.close() }
    }
}

/** Segmentazione con sovrapposizione per elaborazioni a blocchi su brani di qualsiasi durata. */
data class Segment(val index: Int, val start: Long, val length: Int, val coreStart: Long, val coreEnd: Long)

object Segmenter {
    /**
     * Divide [total] frame in segmenti lunghi [segLen] con [overlap] frame condivisi.
     * coreStart/coreEnd delimitano la parte "di competenza" del segmento (senza bordi), utile per
     * ricomporre i risultati evitando artefatti ai margini.
     */
    fun plan(total: Long, segLen: Int, overlap: Int): List<Segment> {
        require(segLen > 2 * overlap) { "segLen deve essere > 2*overlap" }
        if (total <= 0) return emptyList()
        val step = segLen - overlap
        val res = ArrayList<Segment>()
        var start = 0L; var i = 0
        while (true) {
            val len = minOf(segLen.toLong(), total - start).toInt()
            val coreStart = if (i == 0) 0 else start + overlap / 2
            val isLast = start + len >= total
            val coreEnd = if (isLast) total else start + step + overlap / 2
            res.add(Segment(i, start, len, coreStart, coreEnd))
            if (isLast) break
            start += step; i++
        }
        return res
    }

    /** Dimensione di segmento adattiva in base alla memoria libera (bytesPerFrame = costo stimato per frame). */
    fun adaptiveSegmentFrames(freeBytes: Long, bytesPerFrame: Long, minFrames: Int, maxFrames: Int, safety: Double = 0.25): Int {
        val budget = (freeBytes * safety / bytesPerFrame).toLong()
        return budget.coerceIn(minFrames.toLong(), maxFrames.toLong()).toInt()
    }
}

/** Picchi min/max per disegnare la forma d'onda di file lunghi in modo efficiente. */
object WaveformPeaks {
    fun compute(src: PcmSource, buckets: Int, blockFrames: Int = 65536): Pair<FloatArray, FloatArray> {
        val mins = FloatArray(buckets) { 0f }; val maxs = FloatArray(buckets) { 0f }
        if (src.frameCount == 0L) return mins to maxs
        val framesPerBucket = src.frameCount.toDouble() / buckets
        var pos = 0L
        while (pos < src.frameCount) {
            val mono = src.readMono(pos, blockFrames)
            for (i in mono.indices) {
                val b = ((pos + i) / framesPerBucket).toInt().coerceIn(0, buckets - 1)
                val v = mono[i]
                if (v < mins[b]) mins[b] = v
                if (v > maxs[b]) maxs[b] = v
            }
            if (mono.isEmpty()) break
            pos += mono.size
        }
        return mins to maxs
    }
}

/** Mixer offline per stem/tracce: volume, pan, mute, solo. */
object Mixer {
    data class Channel(val left: FloatArray, val right: FloatArray, val volume: Float, val pan: Float, val muted: Boolean, val solo: Boolean)

    fun mix(channels: List<Channel>, frames: Int): Array<FloatArray> {
        val anySolo = channels.any { it.solo }
        val l = FloatArray(frames); val r = FloatArray(frames)
        for (c in channels) {
            if (c.muted || (anySolo && !c.solo)) continue
            val p = c.pan.coerceIn(-1f, 1f)
            // pan a potenza costante
            val angle = (p + 1) * Math.PI / 4
            val gl = (kotlin.math.cos(angle) * Math.sqrt(2.0)).toFloat() * c.volume
            val gr = (kotlin.math.sin(angle) * Math.sqrt(2.0)).toFloat() * c.volume
            val n = minOf(frames, c.left.size)
            for (i in 0 until n) { l[i] += c.left[i] * gl; r[i] += c.right[i] * gr }
        }
        return arrayOf(l, r)
    }
}

object AudioMath {
    fun rms(x: FloatArray): Double { var s = 0.0; for (v in x) s += v * v; return Math.sqrt(s / maxOf(1, x.size)) }
    fun peak(x: FloatArray): Float { var m = 0f; for (v in x) { val a = kotlin.math.abs(v); if (a > m) m = a }; return m }
    fun normalize(x: FloatArray, target: Float = 0.95f): FloatArray { val p = peak(x); return if (p < 1e-9f) x else FloatArray(x.size) { x[it] * target / p } }
}
