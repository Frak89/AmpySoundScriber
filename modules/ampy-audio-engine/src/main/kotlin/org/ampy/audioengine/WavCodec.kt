package org.ampy.audioengine

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lettura/scrittura WAV (PCM 16/24/32 bit int e float32, WAVE_FORMAT_EXTENSIBLE incluso). */
object WavCodec {
    fun read(file: File): AudioBuffer = file.inputStream().buffered().use { read(it) }

    fun read(input: InputStream): AudioBuffer {
        val din = DataInputStream(input)
        fun le32(): Int { val b = ByteArray(4); din.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int }
        fun le16(): Int { val b = ByteArray(2); din.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff }
        fun tag(): String { val b = ByteArray(4); din.readFully(b); return String(b, Charsets.US_ASCII) }
        if (tag() != "RIFF") throw IllegalArgumentException("Non è un file RIFF")
        le32()
        if (tag() != "WAVE") throw IllegalArgumentException("Non è un file WAVE")
        var format = 1; var channels = 1; var rate = 44100; var bits = 16
        while (true) {
            val id = try { tag() } catch (e: EOFException) { throw IllegalArgumentException("chunk data mancante") }
            val size = le32()
            when (id) {
                "fmt " -> {
                    format = le16(); channels = le16(); rate = le32(); le32(); le16(); bits = le16()
                    var rest = size - 16
                    if (format == 0xFFFE && rest >= 10) { le16(); le16(); le32(); format = le16(); rest -= 10; din.skipBytes(rest); rest = 0 }
                    if (rest > 0) din.skipBytes(rest)
                    if (size % 2 == 1) din.skipBytes(1)
                }
                "data" -> {
                    val bytesPerSample = bits / 8
                    val total = if (size < 0 || size == -1) Int.MAX_VALUE else size
                    val bytes = readUpTo(din, total)
                    val frames = bytes.size / (bytesPerSample * channels)
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val out = Array(channels) { FloatArray(frames) }
                    for (i in 0 until frames) for (c in 0 until channels) {
                        out[c][i] = when {
                            format == 3 && bits == 32 -> bb.float
                            format == 3 && bits == 64 -> bb.double.toFloat()
                            bits == 16 -> bb.short / 32768f
                            bits == 24 -> { val b0 = bb.get().toInt() and 0xff; val b1 = bb.get().toInt() and 0xff; val b2 = bb.get().toInt(); ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f }
                            bits == 32 -> bb.int / 2147483648f
                            bits == 8 -> ((bb.get().toInt() and 0xff) - 128) / 128f
                            else -> throw IllegalArgumentException("Formato WAV non supportato: fmt=$format bits=$bits")
                        }
                    }
                    return AudioBuffer(rate, out)
                }
                else -> { din.skipBytes(size + (size and 1)) }
            }
        }
    }

    private fun readUpTo(din: DataInputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        var remaining = max.toLong()
        while (remaining > 0) {
            val r = din.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r <= 0) break
            out.write(buf, 0, r); remaining -= r
        }
        return out.toByteArray()
    }

    /** Scrive PCM 16 bit con dithering TPDF leggero. */
    fun write16(file: File, buf: AudioBuffer) = BufferedOutputStream(FileOutputStream(file)).use { write16(it, buf) }

    fun write16(out: OutputStream, buf: AudioBuffer) {
        val ch = buf.channelCount; val frames = buf.frames
        val dataSize = frames * ch * 2
        out.write(header(buf.sampleRate, ch, 16, 1, dataSize))
        val bb = ByteBuffer.allocate(8192 * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
        val rnd = java.util.Random(1234)
        for (i in 0 until frames) {
            for (c in 0 until ch) {
                val dither = (rnd.nextFloat() - rnd.nextFloat()) / 32768f
                val v = ((buf.channels[c][i] + dither).coerceIn(-1f, 1f) * 32767f).toInt()
                bb.putShort(v.toShort())
            }
            if (!bb.hasRemaining() || bb.remaining() < ch * 2) { out.write(bb.array(), 0, bb.position()); bb.clear() }
        }
        out.write(bb.array(), 0, bb.position())
    }

    /** Converte una sorgente PCM (anche molto lunga) in WAV 16 bit a blocchi. */
    fun write16Streaming(file: File, src: PcmSource, block: Int = 65536) {
        val ch = src.channelCount
        val dataSize = src.frameCount * ch * 2
        require(dataSize < 0xFFFFFFF0L) { "WAV > 4GB non supportato: esportare in FLAC/RF64" }
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(header(src.sampleRate, ch, 16, 1, dataSize.toInt()))
            val inter = FloatArray(block * ch)
            val bb = ByteBuffer.allocate(block * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0L
            while (pos < src.frameCount) {
                val n = src.read(pos, block, inter)
                if (n <= 0) break
                bb.clear()
                for (i in 0 until n * ch) bb.putShort((inter[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                out.write(bb.array(), 0, bb.position())
                pos += n
            }
        }
    }

    fun header(rate: Int, ch: Int, bits: Int, format: Int, dataSize: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataSize); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(format.toShort()); bb.putShort(ch.toShort())
        bb.putInt(rate); bb.putInt(rate * ch * bits / 8); bb.putShort((ch * bits / 8).toShort()); bb.putShort(bits.toShort())
        bb.put("data".toByteArray()); bb.putInt(dataSize)
        return bb.array()
    }

    /** Aggiorna le dimensioni nell'header dopo una registrazione di lunghezza ignota. */
    fun patchSizes(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val len = raf.length()
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(4); raf.write(bb.putInt((len - 8).toInt()).array())
            bb.clear(); raf.seek(40); raf.write(bb.putInt((len - 44).toInt()).array())
        }
    }
}
