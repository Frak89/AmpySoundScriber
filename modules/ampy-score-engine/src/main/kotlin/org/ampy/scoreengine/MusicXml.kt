package org.ampy.scoreengine

import org.ampy.core.Clef
import org.ampy.core.InstrumentKind
import org.ampy.core.KeySignature
import org.ampy.core.ModuleInfo
import org.ampy.core.NoteEvent
import org.ampy.core.Project
import org.ampy.core.TempoChange
import org.ampy.core.TimeSignature
import org.ampy.core.Track
import org.ampy.musicbrain.ChordSegment
import org.ampy.rhythmlab.Quantizer
import org.ampy.rhythmlab.TempoMap

object ScoreEngineModule {
    val info = ModuleInfo("AmpyScoreEngine", "0.1.0", "Notazione: quantizzazione, MusicXML (export/import), tablature; rendering con Verovio")
}

data class ScoreOptions(
    /** Divisioni per quarto: 12 permette semicrome e terzine. */
    val divisions: Int = 12,
    val maxSubdivision: Int = 4,
    val chords: List<ChordSegment> = emptyList(),
    /** Id delle tracce per cui aggiungere un rigo di tablatura. */
    val tabTracks: Set<String> = emptySet(),
    val composer: String = "AmpySoundScriber",
)

/**
 * Generatore MusicXML 4.0 (partwise). Ogni traccia diventa una parte; il pianoforte usa il doppio rigo,
 * chitarra e basso la chiave con ottava bassa (ed eventualmente un rigo TAB).
 * La polifonia di ogni rigo è ridotta a una sequenza di accordi (voce singola) per garantire misure valide.
 */
object MusicXmlWriter {

    private data class Ev(val start: Long, val dur: Long, val notes: List<NoteEvent>)          // notes vuoto = pausa
    private data class Piece(val pos: Long, val dur: Long, val notes: List<NoteEvent>, val tieStop: Boolean, val tieStart: Boolean)

    private val SHARP_NAMES = listOf("C" to 0, "C" to 1, "D" to 0, "D" to 1, "E" to 0, "F" to 0, "F" to 1, "G" to 0, "G" to 1, "A" to 0, "A" to 1, "B" to 0)
    private val FLAT_NAMES = listOf("C" to 0, "D" to -1, "D" to 0, "E" to -1, "E" to 0, "F" to 0, "G" to -1, "G" to 0, "A" to -1, "A" to 0, "B" to -1, "B" to 0)

    /** (tick, tipo, puntato, terzina) per divisions=12; scalati per altri valori multipli di 12. */
    private fun durationTable(div: Int): List<Triple<Long, String, Pair<Boolean, Boolean>>> {
        val q = div.toLong()
        val t = mutableListOf(
            Triple(q * 4, "whole", false to false), Triple(q * 3, "half", true to false), Triple(q * 2, "half", false to false),
            Triple(q * 3 / 2, "quarter", true to false), Triple(q, "quarter", false to false), Triple(q * 3 / 4, "eighth", true to false),
            Triple(q * 2 / 3, "quarter", false to true), Triple(q / 2, "eighth", false to false), Triple(q / 3, "eighth", false to true),
            Triple(q / 4, "16th", false to false), Triple(q / 6, "16th", false to true), Triple(q / 12, "32nd", false to true),
        )
        return t.filter { it.first > 0 && (it.first * 12) % 1 == 0L }.distinctBy { it.first }
    }

    private fun decompose(dur: Long, div: Int): List<Triple<Long, String, Pair<Boolean, Boolean>>> {
        val table = durationTable(div)
        val out = ArrayList<Triple<Long, String, Pair<Boolean, Boolean>>>()
        var rem = dur
        while (rem > 0) {
            val d = table.firstOrNull { it.first <= rem } ?: table.last()
            out.add(d); rem -= d.first
        }
        return out
    }

    /** Riduce le note di un rigo a sequenza monofonica di accordi senza sovrapposizioni. */
    private fun toStream(notes: List<NoteEvent>, tempo: TempoMap, div: Int, maxSub: Int): List<Ev> {
        if (notes.isEmpty()) return emptyList()
        val sub = Quantizer.detectSubdivision(notes.map { tempo.secToBeat(it.startSec) }, maxSub).let { if (div % it == 0) it else 4 }
        val q = Quantizer.quantize(notes, tempo, sub, div)
        val groups = q.groupBy { it.startTick }.toSortedMap()
        val starts = groups.keys.toList()
        return starts.mapIndexed { i, st ->
            val g = groups[st]!!
            val maxDur = g.maxOf { it.durTicks }
            val next = starts.getOrNull(i + 1)
            val dur = if (next != null) minOf(maxDur, next - st) else maxDur
            Ev(st, dur, g.map { it.note }.distinctBy { it.pitch }.sortedBy { it.pitch })
        }
    }

    private fun splitIntoMeasures(stream: List<Ev>, measureLen: Long, nMeasures: Int): List<List<Piece>> {
        val out = List(nMeasures) { ArrayList<Piece>() }
        var cursor = 0L
        val withRests = ArrayList<Ev>()
        for (e in stream) {
            if (e.start > cursor) withRests.add(Ev(cursor, e.start - cursor, emptyList()))
            withRests.add(e); cursor = e.start + e.dur
        }
        val total = measureLen * nMeasures
        if (cursor < total) withRests.add(Ev(cursor, total - cursor, emptyList()))
        for (e in withRests) {
            var s = e.start; val end = e.start + e.dur
            var first = true
            while (s < end) {
                val m = (s / measureLen).toInt()
                if (m >= nMeasures) break
                val mEnd = (m + 1) * measureLen
                val pe = minOf(end, mEnd)
                val isRest = e.notes.isEmpty()
                out[m].add(Piece(s - m * measureLen, pe - s, e.notes, tieStop = !isRest && !first, tieStart = !isRest && pe < end))
                first = false; s = pe
            }
        }
        return out
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun pitchXml(p: Int, useFlats: Boolean): String {
        val (step, alter) = (if (useFlats) FLAT_NAMES else SHARP_NAMES)[p % 12]
        val oct = p / 12 - 1
        return "<pitch><step>$step</step>${if (alter != 0) "<alter>$alter</alter>" else ""}<octave>$oct</octave></pitch>"
    }

    internal val DRUM_DISPLAY = mapOf(35 to ("F" to 4), 36 to ("F" to 4), 38 to ("C" to 5), 40 to ("C" to 5), 37 to ("C" to 5), 41 to ("A" to 4), 43 to ("A" to 4), 45 to ("D" to 5), 47 to ("E" to 5), 48 to ("E" to 5), 50 to ("F" to 5), 42 to ("G" to 5), 44 to ("D" to 4), 46 to ("G" to 5), 49 to ("A" to 5), 57 to ("A" to 5), 51 to ("F" to 5), 53 to ("F" to 5))

    private fun writePieces(sb: StringBuilder, pieces: List<Piece>, div: Int, useFlats: Boolean, staff: Int?, voice: Int, mode: Mode, tab: Map<Long, TabNote>) {
        var tupletAcc = 0L; var tupletGroup = 0L
        for (pc in pieces) {
            val parts = decompose(pc.dur, div)
            parts.forEachIndexed { pi, (ticks, type, flags) ->
                val (dotted, triplet) = flags
                val tieStop = !pc.notes.isEmpty() && (pc.tieStop || pi > 0)
                val tieStart = !pc.notes.isEmpty() && (pc.tieStart || pi < parts.size - 1)
                val list: List<NoteEvent?> = if (pc.notes.isEmpty()) listOf(null) else pc.notes
                var tupletStart = false; var tupletStop = false
                if (triplet) {
                    if (tupletAcc == 0L) { tupletStart = true; tupletGroup = ticks * 3 }
                    tupletAcc += ticks
                    if (tupletAcc >= tupletGroup) { tupletStop = true; tupletAcc = 0 }
                } else tupletAcc = 0
                list.forEachIndexed { ci, n ->
                    sb.append("<note>")
                    if (ci > 0) sb.append("<chord/>")
                    when {
                        n == null -> sb.append("<rest/>")
                        mode == Mode.DRUMS -> { val (st, oc) = DRUM_DISPLAY[n.pitch] ?: ("C" to 5); sb.append("<unpitched><display-step>$st</display-step><display-octave>$oc</display-octave></unpitched>") }
                        else -> sb.append(pitchXml(n.pitch, useFlats))
                    }
                    sb.append("<duration>$ticks</duration>")
                    if (tieStop) sb.append("<tie type=\"stop\"/>")
                    if (tieStart) sb.append("<tie type=\"start\"/>")
                    sb.append("<voice>$voice</voice><type>$type</type>")
                    if (dotted) sb.append("<dot/>")
                    if (triplet) sb.append("<time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>")
                    if (n != null && mode == Mode.DRUMS && n.pitch in setOf(42, 44, 46, 49, 51, 57)) sb.append("<notehead>x</notehead>")
                    if (staff != null) sb.append("<staff>$staff</staff>")
                    val tn = n?.let { tab[it.id] }
                    val tup = ci == 0 && (tupletStart || tupletStop)
                    if (tieStop || tieStart || tup || (mode == Mode.TAB && tn != null)) {
                        sb.append("<notations>")
                        if (ci == 0 && tupletStart) sb.append("<tuplet type=\"start\" bracket=\"yes\"/>")
                        if (ci == 0 && tupletStop) sb.append("<tuplet type=\"stop\"/>")
                        if (tieStop) sb.append("<tied type=\"stop\"/>")
                        if (tieStart) sb.append("<tied type=\"start\"/>")
                        if (mode == Mode.TAB && tn != null) sb.append("<technical><string>${tn.string}</string><fret>${tn.fret}</fret></technical>")
                        sb.append("</notations>")
                    }
                    sb.append("</note>")
                }
            }
        }
    }

    private enum class Mode { PITCHED, DRUMS, TAB }

    private data class PartSpec(val id: String, val name: String, val track: Track, val mode: Mode, val staves: List<List<NoteEvent>>, val tab: TabGenerator.Result? = null)

    fun write(project: Project, tracks: List<Track> = project.tracks, opt: ScoreOptions = ScoreOptions()): String {
        val div = opt.divisions
        val tempo = TempoMap(project.tempo)
        val ts = project.timeSignature
        val measureLen = div.toLong() * 4 * ts.numerator / ts.denominator
        val useFlats = project.key.fifths < 0

        val specs = ArrayList<PartSpec>()
        tracks.forEachIndexed { i, t ->
            val pid = "P${i + 1}"
            when {
                t.instrument.isDrum -> specs.add(PartSpec(pid, t.name, t, Mode.DRUMS, listOf(t.notes)))
                t.instrument.clef == Clef.GRAND -> specs.add(PartSpec(pid, t.name, t, Mode.PITCHED, listOf(t.notes.filter { it.pitch >= 60 }, t.notes.filter { it.pitch < 60 })))
                else -> specs.add(PartSpec(pid, t.name, t, Mode.PITCHED, listOf(t.notes)))
            }
            if (t.id in opt.tabTracks && !t.instrument.isDrum) {
                val tuning = t.tuning ?: if (t.instrument == InstrumentKind.BASS) org.ampy.core.Tuning.BASS_4 else org.ampy.core.Tuning.GUITAR_STANDARD
                val res = TabGenerator(tuning).assign(t.notes)
                specs.add(PartSpec("${pid}T", "${t.name} (TAB)", t.copy(tuning = tuning), Mode.TAB, listOf(t.notes), res))
            }
        }
        val streams = specs.map { s -> s.staves.map { toStream(it, tempo, div, opt.maxSubdivision) } }
        val lastTick = streams.flatten().flatten().maxOfOrNull { it.start + it.dur } ?: measureLen
        val nMeasures = maxOf(1, ((lastTick + measureLen - 1) / measureLen).toInt())

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
        sb.append("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" \"http://www.musicxml.org/dtds/partwise.dtd\">\n")
        sb.append("<score-partwise version=\"4.0\">")
        sb.append("<work><work-title>${esc(project.title)}</work-title></work>")
        sb.append("<identification><creator type=\"composer\">${esc(opt.composer)}</creator><encoding><software>AmpySoundScriber</software></encoding></identification>")
        sb.append("<part-list>")
        specs.forEachIndexed { i, s ->
            sb.append("<score-part id=\"${s.id}\"><part-name>${esc(s.name)}</part-name>")
            if (s.mode != Mode.TAB) {
                sb.append("<score-instrument id=\"${s.id}-I1\"><instrument-name>${esc(s.name)}</instrument-name></score-instrument>")
                sb.append("<midi-instrument id=\"${s.id}-I1\"><midi-channel>${if (s.mode == Mode.DRUMS) 10 else (i % 15) + 1}</midi-channel><midi-program>${s.track.instrument.gmProgram + 1}</midi-program></midi-instrument>")
            }
            sb.append("</score-part>")
        }
        sb.append("</part-list>")

        val tempoMarks = project.tempo.map { tc -> Math.round(tempo.secToBeat(tc.timeSec) * div) to tc.bpm }
        specs.forEachIndexed { si, spec ->
            sb.append("<part id=\"${spec.id}\">")
            val perStaff = streams[si].map { splitIntoMeasures(it, measureLen, nMeasures) }
            val tabMap = spec.tab?.notes?.associate { tn -> tn.note.id to tn.copy(string = spec.track.tuning!!.openStrings.size - tn.string) } ?: emptyMap()
            for (m in 0 until nMeasures) {
                sb.append("<measure number=\"${m + 1}\">")
                if (m == 0) {
                    sb.append("<attributes><divisions>$div</divisions>")
                    if (spec.mode == Mode.PITCHED) sb.append("<key><fifths>${project.key.fifths}</fifths><mode>${if (project.key.isMinor) "minor" else "major"}</mode></key>")
                    sb.append("<time><beats>${ts.numerator}</beats><beat-type>${ts.denominator}</beat-type></time>")
                    if (spec.staves.size > 1) sb.append("<staves>${spec.staves.size}</staves>")
                    when {
                        spec.mode == Mode.DRUMS -> sb.append("<clef><sign>percussion</sign></clef>")
                        spec.mode == Mode.TAB -> {
                            val tun = spec.track.tuning!!
                            sb.append("<clef><sign>TAB</sign><line>5</line></clef><staff-details><staff-lines>${tun.openStrings.size}</staff-lines>")
                            tun.openStrings.reversed().forEachIndexed { li, p ->
                                val (st, al) = SHARP_NAMES[p % 12]
                                // staff-tuning line 1 = rigo più basso = corda più grave
                                sb.append("<staff-tuning line=\"${tun.openStrings.size - li}\"><tuning-step>$st</tuning-step>${if (al != 0) "<tuning-alter>$al</tuning-alter>" else ""}<tuning-octave>${p / 12 - 1}</tuning-octave></staff-tuning>")
                            }
                            sb.append("</staff-details>")
                        }
                        spec.staves.size > 1 -> sb.append("<clef number=\"1\"><sign>G</sign><line>2</line></clef><clef number=\"2\"><sign>F</sign><line>4</line></clef>")
                        spec.track.instrument.clef == Clef.BASS -> sb.append("<clef><sign>F</sign><line>4</line>${if (spec.track.instrument.transposeOctaveDisplay != 0) "<clef-octave-change>-1</clef-octave-change>" else ""}</clef>")
                        else -> sb.append("<clef><sign>G</sign><line>2</line>${if (spec.track.instrument.transposeOctaveDisplay != 0) "<clef-octave-change>-1</clef-octave-change>" else ""}</clef>")
                    }
                    sb.append("</attributes>")
                }
                if (si == 0) {
                    for ((tick, bpm) in tempoMarks) if (tick / measureLen == m.toLong()) {
                        val off = tick - m * measureLen
                        sb.append("<direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>${"%.0f".format(java.util.Locale.ROOT, bpm)}</per-minute></metronome></direction-type>")
                        if (off > 0) sb.append("<offset>$off</offset>")
                        sb.append("<sound tempo=\"${"%.2f".format(java.util.Locale.ROOT, bpm)}\"/></direction>")
                    }
                }
                if (spec.mode == Mode.PITCHED && si == specs.indexOfFirst { it.mode == Mode.PITCHED }) {
                    for (c in opt.chords) {
                        val ch = c.chord ?: continue
                        val tick = Math.round(tempo.secToBeat(c.startSec) * div)
                        if (tick / measureLen != m.toLong()) continue
                        val (rs, ra) = (if (useFlats) FLAT_NAMES else SHARP_NAMES)[ch.root]
                        sb.append("<harmony><root><root-step>$rs</root-step>${if (ra != 0) "<root-alter>$ra</root-alter>" else ""}</root><kind text=\"${esc(ch.quality.symbol)}\">${harmonyKind(ch.quality)}</kind>")
                        if (ch.bass != ch.root) { val (bs, ba) = (if (useFlats) FLAT_NAMES else SHARP_NAMES)[ch.bass]; sb.append("<bass><bass-step>$bs</bass-step>${if (ba != 0) "<bass-alter>$ba</bass-alter>" else ""}</bass>") }
                        val off = tick - m * measureLen
                        if (off > 0) sb.append("<offset>$off</offset>")
                        sb.append("</harmony>")
                    }
                }
                perStaff.forEachIndexed { staffIdx, measures ->
                    if (staffIdx > 0) sb.append("<backup><duration>$measureLen</duration></backup>")
                    val staffNo = if (spec.staves.size > 1) staffIdx + 1 else null
                    writePieces(sb, measures[m], div, useFlats, staffNo, staffIdx + 1, spec.mode, tabMap)
                }
                if (m == nMeasures - 1) sb.append("<barline location=\"right\"><bar-style>light-heavy</bar-style></barline>")
                sb.append("</measure>")
            }
            sb.append("</part>")
        }
        sb.append("</score-partwise>\n")
        return sb.toString()
    }

    private fun harmonyKind(q: org.ampy.musicbrain.ChordQuality) = when (q) {
        org.ampy.musicbrain.ChordQuality.MAJOR -> "major"; org.ampy.musicbrain.ChordQuality.MINOR -> "minor"
        org.ampy.musicbrain.ChordQuality.DOM7 -> "dominant"; org.ampy.musicbrain.ChordQuality.MAJ7 -> "major-seventh"
        org.ampy.musicbrain.ChordQuality.MIN7 -> "minor-seventh"; org.ampy.musicbrain.ChordQuality.DIM -> "diminished"
        org.ampy.musicbrain.ChordQuality.HALF_DIM -> "half-diminished"; org.ampy.musicbrain.ChordQuality.AUG -> "augmented"
        org.ampy.musicbrain.ChordQuality.SUS2 -> "suspended-second"; org.ampy.musicbrain.ChordQuality.SUS4 -> "suspended-fourth"
        org.ampy.musicbrain.ChordQuality.POWER -> "power"
    }
}

/** Importazione MusicXML (partwise, anche compresso .mxl) in tracce di note. */
object MusicXmlReader {
    data class Result(val title: String?, val tracks: List<Track>, val tempo: List<TempoChange>, val key: KeySignature?, val time: TimeSignature?)

    fun readMxl(bytes: ByteArray): Result {
        val entries = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { if (!e.isDirectory) entries[e.name] = z.readBytes(); e = z.nextEntry }
        }
        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        val root = container?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?: entries.keys.firstOrNull { it.endsWith(".xml") && !it.startsWith("META-INF") }
            ?: throw IllegalArgumentException("Archivio .mxl senza partitura")
        return read(entries[root]!!.toString(Charsets.UTF_8))
    }

    fun read(xml: String): Result {
        val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = false
        runCatching { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
        val doc = dbf.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml.replace(Regex("<!DOCTYPE[^>]*>"), ""))))
        val root = doc.documentElement
        if (root.tagName != "score-partwise") throw IllegalArgumentException("Supportato solo score-partwise (trovato ${root.tagName})")
        fun org.w3c.dom.Element.child(n: String): org.w3c.dom.Element? { val l = getElementsByTagName(n); return if (l.length > 0) l.item(0) as org.w3c.dom.Element else null }
        fun org.w3c.dom.Element.kids(n: String? = null) = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().filter { n == null || it.tagName == n }
        val names = HashMap<String, String>()
        root.child("part-list")?.kids("score-part")?.forEach { sp -> names[sp.getAttribute("id")] = sp.child("part-name")?.textContent ?: sp.getAttribute("id") }
        val stepPc = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
        val tempos = ArrayList<TempoChange>(); var key: KeySignature? = null; var time: TimeSignature? = null
        val tracks = ArrayList<Track>()
        for ((pi, part) in root.kids("part").withIndex()) {
            var divisions = 1.0; var posQ = 0.0 // posizione in quarti
            var bpm = 120.0; var secAtBpm = 0.0; var qAtBpm = 0.0
            fun qToSec(q: Double) = secAtBpm + (q - qAtBpm) * 60.0 / bpm
            val notes = ArrayList<NoteEvent>(); val openTies = HashMap<Int, Pair<Double, Int>>()
            var lastStartQ = 0.0
            var isTab = false
            for (measure in part.kids("measure")) {
                for (el in measure.kids()) when (el.tagName) {
                    "attributes" -> {
                        el.child("divisions")?.let { divisions = it.textContent.trim().toDouble() }
                        if (pi == 0 || key == null) el.child("key")?.let { k -> val f = k.child("fifths")!!.textContent.trim().toInt(); val minor = k.child("mode")?.textContent?.trim() == "minor"; val maj = Math.floorMod(f * 7, 12); key = KeySignature(if (minor) (maj + 9) % 12 else maj, minor) }
                        if (time == null) el.child("time")?.let { t -> runCatching { time = TimeSignature(t.child("beats")!!.textContent.trim().toInt(), t.child("beat-type")!!.textContent.trim().toInt()) } }
                        if (el.child("clef")?.child("sign")?.textContent?.trim() == "TAB") isTab = true
                    }
                    "direction", "sound" -> {
                        val snd = if (el.tagName == "sound") el else el.child("sound")
                        snd?.getAttribute("tempo")?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { nb ->
                            val off = el.child("offset")?.textContent?.trim()?.toDoubleOrNull()?.div(divisions) ?: 0.0
                            val q = posQ + off; secAtBpm = qToSec(q); qAtBpm = q; bpm = nb
                            if (pi == 0) tempos.add(TempoChange(secAtBpm, nb))
                        }
                    }
                    "backup" -> posQ -= el.child("duration")!!.textContent.trim().toDouble() / divisions
                    "forward" -> posQ += el.child("duration")!!.textContent.trim().toDouble() / divisions
                    "note" -> {
                        val durQ = (el.child("duration")?.textContent?.trim()?.toDouble() ?: 0.0) / divisions
                        val isChord = el.kids("chord").isNotEmpty()
                        val startQ = if (isChord) lastStartQ else posQ
                        val p = el.child("pitch")
                        val up = el.child("unpitched")
                        if (up != null && el.kids("rest").isEmpty()) {
                            val st = up.child("display-step")?.textContent?.trim(); val oc = up.child("display-octave")?.textContent?.trim()?.toIntOrNull()
                            val drum = MusicXmlWriter.DRUM_DISPLAY.entries.firstOrNull { it.value == (st to oc) }?.key ?: 38
                            notes.add(NoteEvent(drum, qToSec(startQ), qToSec(startQ + durQ)))
                        }
                        if (p != null && el.kids("rest").isEmpty()) {
                            val pc = stepPc[p.child("step")!!.textContent.trim()]!!
                            val alter = p.child("alter")?.textContent?.trim()?.toDouble()?.toInt() ?: 0
                            val pitch = (p.child("octave")!!.textContent.trim().toInt() + 1) * 12 + pc + alter
                            val ties = el.kids("tie").map { it.getAttribute("type") }
                            val open = openTies.remove(pitch)
                            val startSec = open?.let { qToSec(it.first) } ?: qToSec(startQ)
                            if ("start" in ties) openTies[pitch] = (open?.first ?: startQ) to 80
                            else if (pitch in 0..127) notes.add(NoteEvent(pitch, startSec, qToSec(startQ + durQ).coerceAtLeast(startSec)))
                        }
                        if (!isChord) { lastStartQ = posQ; posQ += durQ }
                    }
                }
            }
            if (!isTab && notes.isNotEmpty()) {
                val id = part.getAttribute("id")
                val name = names[id] ?: id
                val kind = when {
                    Regex("(?i)drum|batter|perc").containsMatchIn(name) -> InstrumentKind.DRUMS
                    Regex("(?i)bass|basso").containsMatchIn(name) -> InstrumentKind.BASS
                    Regex("(?i)guit|chitarr").containsMatchIn(name) -> InstrumentKind.GUITAR
                    Regex("(?i)piano").containsMatchIn(name) -> InstrumentKind.PIANO
                    Regex("(?i)voc|voice|voce|sopran|tenor").containsMatchIn(name) -> InstrumentKind.VOICE
                    else -> InstrumentKind.OTHER
                }
                tracks.add(Track("xml-$id", name, kind, notes.sortedBy { it.startSec }))
            }
        }
        return Result(root.child("work-title")?.textContent, tracks, tempos.ifEmpty { listOf(TempoChange(0.0, 120.0)) }, key, time)
    }
}
