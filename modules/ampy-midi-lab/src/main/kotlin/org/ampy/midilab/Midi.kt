package org.ampy.midilab

import org.ampy.core.InstrumentKind
import org.ampy.core.KeySignature
import org.ampy.core.ModuleInfo
import org.ampy.core.NoteEvent
import org.ampy.core.Project
import org.ampy.core.TempoChange
import org.ampy.core.TimeSignature
import org.ampy.core.Track
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object MidiLabModule {
    val info = ModuleInfo("AmpyMIDILab", "0.1.0", "Lettura/scrittura Standard MIDI File, sintesi di anteprima, import/export")
}

class InvalidMidiException(msg: String) : Exception(msg)

/** Conversione secondi <-> tick con mappa di tempo a tratti costanti. */
class TickMap(val ppq: Int, tempo: List<TempoChange>) {
    private val tempos = tempo.sortedBy { it.timeSec }.ifEmpty { listOf(TempoChange(0.0, 120.0)) }
    private val tickAt = LongArray(tempos.size).also { arr ->
        for (i in 1 until arr.size) arr[i] = arr[i - 1] + ((tempos[i].timeSec - tempos[i - 1].timeSec) * tempos[i - 1].bpm / 60.0 * ppq).roundToLong()
    }
    fun secToTick(t: Double): Long {
        val i = tempos.indexOfLast { it.timeSec <= t }.coerceAtLeast(0)
        return tickAt[i] + ((t - tempos[i].timeSec) * tempos[i].bpm / 60.0 * ppq).roundToLong()
    }
    fun tempoEvents(): List<Pair<Long, Double>> = tempos.indices.map { tickAt[it] to tempos[it].bpm }
}

object MidiWriter {
    private class Ev(val tick: Long, val order: Int, val bytes: ByteArray)

    private fun vlq(out: ByteArrayOutputStream, value: Long) {
        var v = value
        val buf = ArrayList<Int>()
        buf.add((v and 0x7f).toInt()); v = v shr 7
        while (v > 0) { buf.add(((v and 0x7f) or 0x80).toInt()); v = v shr 7 }
        for (i in buf.indices.reversed()) out.write(buf[i])
    }

    private fun meta(type: Int, data: ByteArray): ByteArray = ByteArrayOutputStream().also { o ->
        o.write(0xff); o.write(type); vlq(o, data.size.toLong()); o.write(data)
    }.toByteArray()

    private fun chunk(events: List<Ev>): ByteArray {
        val body = ByteArrayOutputStream()
        var last = 0L
        for (e in events.sortedWith(compareBy({ it.tick }, { it.order }))) {
            vlq(body, e.tick - last); body.write(e.bytes); last = e.tick
        }
        vlq(body, 0); body.write(byteArrayOf(0xff.toByte(), 0x2f, 0))
        val b = body.toByteArray()
        return ByteArrayOutputStream().also { o ->
            o.write("MTrk".toByteArray()); o.write(be32(b.size)); o.write(b)
        }.toByteArray()
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /**
     * Scrive un SMF formato 1: traccia 0 = tempo/armatura/metro, poi una traccia per strumento.
     * I pitch bend di Basic Pitch (1/3 semitono) sono convertiti con range ±2 semitoni.
     */
    fun write(tracks: List<Track>, tempo: List<TempoChange>, key: KeySignature, ts: TimeSignature, ppq: Int = 480, title: String = "AmpySoundScriber"): ByteArray {
        val tm = TickMap(ppq, tempo)
        val conductor = ArrayList<Ev>()
        conductor.add(Ev(0, 0, meta(0x03, title.toByteArray(Charsets.UTF_8))))
        for ((tick, bpm) in tm.tempoEvents()) {
            val us = (60_000_000.0 / bpm).roundToInt()
            conductor.add(Ev(tick, 1, meta(0x51, byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte()))))
        }
        val dd = Integer.numberOfTrailingZeros(ts.denominator)
        conductor.add(Ev(0, 2, meta(0x58, byteArrayOf(ts.numerator.toByte(), dd.toByte(), 24, 8))))
        conductor.add(Ev(0, 3, meta(0x59, byteArrayOf(key.fifths.toByte(), if (key.isMinor) 1 else 0))))
        val chunks = arrayListOf(chunk(conductor))

        var nextChannel = 0
        for (t in tracks) {
            val ch = if (t.instrument.isDrum) 9 else { val c = nextChannel; nextChannel++; if (nextChannel == 9) nextChannel = 10; c % 16 }
            val evs = ArrayList<Ev>()
            evs.add(Ev(0, 0, meta(0x03, t.name.toByteArray(Charsets.UTF_8))))
            if (!t.instrument.isDrum) {
                evs.add(Ev(0, 1, byteArrayOf((0xC0 or ch).toByte(), t.instrument.gmProgram.toByte())))
                // RPN 0 = pitch bend range ±2 semitoni
                for ((cc, v) in listOf(101 to 0, 100 to 0, 6 to 2, 38 to 0)) evs.add(Ev(0, 2, byteArrayOf((0xB0 or ch).toByte(), cc.toByte(), v.toByte())))
            }
            evs.add(Ev(0, 3, byteArrayOf((0xB0 or ch).toByte(), 7, (t.volume.coerceIn(0f, 1f) * 100).roundToInt().toByte())))
            evs.add(Ev(0, 3, byteArrayOf((0xB0 or ch).toByte(), 10, ((t.pan.coerceIn(-1f, 1f) + 1) * 63.5f).roundToInt().coerceIn(0, 127).toByte())))
            val bendable = t.notes.filter { n -> n.pitchBends != null && t.notes.none { o -> o !== n && o.startSec < n.endSec && n.startSec < o.endSec } }.toSet()
            for (n in t.notes) {
                val s = tm.secToTick(n.startSec); var e = tm.secToTick(n.endSec)
                if (e <= s) e = s + 1
                evs.add(Ev(s, 10, byteArrayOf((0x90 or ch).toByte(), n.pitch.toByte(), n.velocity.coerceIn(1, 127).toByte())))
                evs.add(Ev(e, 5, byteArrayOf((0x80 or ch).toByte(), n.pitch.toByte(), 0)))
                if (n in bendable) {
                    val pb = n.pitchBends!!
                    pb.forEachIndexed { i, b ->
                        val time = n.startSec + (n.endSec - n.startSec) * i / maxOf(1, pb.size - 1)
                        val v = (8192 + (b * 4096 / 3.0)).roundToInt().coerceIn(0, 16383)
                        evs.add(Ev(tm.secToTick(time), 8, byteArrayOf((0xE0 or ch).toByte(), (v and 0x7f).toByte(), (v shr 7).toByte())))
                    }
                    evs.add(Ev(e, 6, byteArrayOf((0xE0 or ch).toByte(), 0, 0x40)))
                }
            }
            chunks.add(chunk(evs))
        }
        return ByteArrayOutputStream().also { o ->
            o.write("MThd".toByteArray()); o.write(be32(6))
            o.write(byteArrayOf(0, 1, (chunks.size shr 8).toByte(), chunks.size.toByte(), (ppq shr 8).toByte(), ppq.toByte()))
            chunks.forEach { o.write(it) }
        }.toByteArray()
    }

    fun write(project: Project, tracks: List<Track> = project.tracks): ByteArray =
        write(tracks, project.tempo, project.key, project.timeSignature, title = project.title)

    fun writeFile(file: File, project: Project, tracks: List<Track> = project.tracks) = file.writeBytes(write(project, tracks))
}

data class MidiImport(val tracks: List<Track>, val tempo: List<TempoChange>, val timeSignature: TimeSignature, val key: KeySignature?, val ppq: Int)

object MidiReader {
    fun read(bytes: ByteArray): MidiImport {
        var p = 0
        fun u8() = bytes.getOrElse(p++) { throw InvalidMidiException("fine file inattesa") }.toInt() and 0xff
        fun be(n: Int): Int { var v = 0; repeat(n) { v = (v shl 8) or u8() }; return v }
        fun tag() = String(bytes, p, 4, Charsets.US_ASCII).also { p += 4 }
        if (bytes.size < 14 || tag() != "MThd") throw InvalidMidiException("header MThd mancante")
        val hl = be(4); val format = be(2); val ntr = be(2); val division = be(2); p += hl - 6
        if (division and 0x8000 != 0) throw InvalidMidiException("timecode SMPTE non supportato")
        val ppq = division
        data class Raw(val tick: Long, val type: Int, val ch: Int, val d1: Int, val d2: Int, val meta: ByteArray? = null, val metaType: Int = -1)
        val rawTracks = ArrayList<Pair<String?, List<Raw>>>()
        repeat(ntr) {
            if (p + 8 > bytes.size) return@repeat
            val id = tag(); val len = be(4); val end = p + len
            if (id != "MTrk") { p = end; return@repeat }
            var tick = 0L; var running = 0; var name: String? = null
            val evs = ArrayList<Raw>()
            while (p < end) {
                var delta = 0L
                while (true) { val b = u8(); delta = (delta shl 7) or (b and 0x7f).toLong(); if (b and 0x80 == 0) break }
                tick += delta
                var status = u8()
                if (status < 0x80) { if (running == 0) throw InvalidMidiException("running status senza stato"); p--; status = running }
                when {
                    status == 0xff -> {
                        val type = u8(); var l = 0L
                        while (true) { val b = u8(); l = (l shl 7) or (b and 0x7f).toLong(); if (b and 0x80 == 0) break }
                        val data = bytes.copyOfRange(p, p + l.toInt()); p += l.toInt()
                        if (type == 0x03 && name == null) name = String(data, Charsets.UTF_8)
                        if (type == 0x2f) p = end
                        evs.add(Raw(tick, 0xff, 0, 0, 0, data, type))
                    }
                    status == 0xf0 || status == 0xf7 -> {
                        var l = 0L
                        while (true) { val b = u8(); l = (l shl 7) or (b and 0x7f).toLong(); if (b and 0x80 == 0) break }
                        p += l.toInt()
                    }
                    else -> {
                        running = status
                        val type = status and 0xf0; val ch = status and 0x0f
                        val d1 = u8(); val d2 = if (type == 0xC0 || type == 0xD0) 0 else u8()
                        evs.add(Raw(tick, type, ch, d1, d2))
                    }
                }
            }
            p = end
            rawTracks.add(name to evs)
        }
        // mappa di tempo globale (formato 1: dalla traccia 0; formato 0: unica traccia)
        val tempoTicks = rawTracks.flatMap { it.second }.filter { it.type == 0xff && it.metaType == 0x51 }
            .map { it.tick to 60_000_000.0 / (((it.meta!![0].toInt() and 0xff) shl 16) or ((it.meta[1].toInt() and 0xff) shl 8) or (it.meta[2].toInt() and 0xff)) }
            .sortedBy { it.first }.ifEmpty { listOf(0L to 120.0) }
        val tempoSec = ArrayList<TempoChange>()
        run {
            var lastTick = 0L; var lastSec = 0.0; var bpm = 120.0
            for ((t, b) in tempoTicks) { lastSec += (t - lastTick) / ppq.toDouble() * 60.0 / bpm; lastTick = t; bpm = b; tempoSec.add(TempoChange(lastSec, b)) }
        }
        fun tickToSec(t: Long): Double {
            var lastTick = 0L; var sec = 0.0; var bpm = 120.0
            for ((tt, b) in tempoTicks) { if (tt > t) break; sec += (tt - lastTick) / ppq.toDouble() * 60.0 / bpm; lastTick = tt; bpm = b }
            return sec + (t - lastTick) / ppq.toDouble() * 60.0 / bpm
        }
        val allRaw = rawTracks.flatMap { it.second }
        val tsRaw = allRaw.firstOrNull { it.type == 0xff && it.metaType == 0x58 }
        val ts = tsRaw?.let { TimeSignature(it.meta!![0].toInt(), 1 shl it.meta[1].toInt()) } ?: TimeSignature()
        val ksRaw = allRaw.firstOrNull { it.type == 0xff && it.metaType == 0x59 }
        val key = ksRaw?.let { r -> val sf = r.meta!![0].toInt(); val minor = r.meta[1].toInt() == 1; val majTonic = Math.floorMod(sf * 7, 12); KeySignature(if (minor) (majTonic + 9) % 12 else majTonic, minor) }

        val tracks = ArrayList<Track>()
        rawTracks.forEachIndexed { ti, (name, evs) ->
            val byChannel = evs.filter { it.type in listOf(0x80, 0x90, 0xC0) }.groupBy { it.ch }
            for ((ch, list) in byChannel) {
                val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()
                val notes = ArrayList<NoteEvent>()
                var program = 0
                for (e in list.sortedBy { it.tick }) {
                    when {
                        e.type == 0xC0 -> program = e.d1
                        e.type == 0x90 && e.d2 > 0 -> open.getOrPut(e.d1) { ArrayDeque() }.addLast(e.tick to e.d2)
                        else -> open[e.d1]?.removeFirstOrNull()?.let { (st, vel) -> notes.add(NoteEvent(e.d1, tickToSec(st), tickToSec(e.tick), vel)) }
                    }
                }
                if (notes.isEmpty()) continue
                val kind = if (ch == 9) InstrumentKind.DRUMS else InstrumentKind.entries.filter { !it.isDrum }.minBy { kotlin.math.abs(it.gmProgram - program) + if (it.gmProgram / 8 == program / 8) 0 else 100 }
                tracks.add(Track("midi-$ti-$ch", name ?: "Traccia ${tracks.size + 1}", kind, notes.sortedBy { it.startSec }))
            }
        }
        return MidiImport(tracks, tempoSec, ts, key, ppq)
    }
}
