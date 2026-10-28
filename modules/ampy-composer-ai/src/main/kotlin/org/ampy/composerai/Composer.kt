package org.ampy.composerai

import org.ampy.core.InstrumentKind
import org.ampy.core.JobContext
import org.ampy.core.KeySignature
import org.ampy.core.ModuleInfo
import org.ampy.core.NoteEvent
import org.ampy.core.Project
import org.ampy.core.Section
import org.ampy.core.TempoChange
import org.ampy.core.TimeSignature
import org.ampy.core.Track
import org.ampy.musicbrain.Chord
import org.ampy.musicbrain.ChordQuality
import org.ampy.musicbrain.ChordSegment
import org.ampy.musicbrain.Scale
import org.ampy.musicbrain.ScaleType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

object ComposerAIModule {
    val info = ModuleInfo("AmpyComposerAI", "0.1.0", "Composizione gerarchica algoritmica (struttura, armonia, motivi, arrangiamento)")
}

enum class SectionType(val label: String) { INTRO("Introduzione"), VERSE("Strofa"), PRE_CHORUS("Pre-ritornello"), CHORUS("Ritornello"), BRIDGE("Bridge"), SOLO("Assolo"), OUTRO("Outro") }

enum class Character { ENERGICO, MALINCONICO, CALMO, AGGRESSIVO, GIOIOSO, MISTERIOSO }

enum class Genre(
    val label: String, val bpmRange: IntRange, val majorScale: ScaleType, val minorScale: ScaleType, val swing: Boolean,
    val instruments: List<InstrumentKind>, val sevenths: Boolean, val soloScale: ScaleType,
) {
    ROCK("Rock", 100..140, ScaleType.MAJOR, ScaleType.NATURAL_MINOR, false, listOf(InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.BASS, InstrumentKind.DRUMS, InstrumentKind.VOICE), false, ScaleType.MINOR_PENTATONIC),
    FUNK("Funk", 95..115, ScaleType.MIXOLYDIAN, ScaleType.DORIAN, false, listOf(InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.BASS, InstrumentKind.DRUMS, InstrumentKind.ELECTRIC_PIANO, InstrumentKind.BRASS), true, ScaleType.DORIAN),
    JAZZ("Jazz", 110..180, ScaleType.MAJOR, ScaleType.DORIAN, true, listOf(InstrumentKind.PIANO, InstrumentKind.BASS, InstrumentKind.DRUMS, InstrumentKind.BRASS), true, ScaleType.DORIAN),
    BLUES("Blues", 70..120, ScaleType.MIXOLYDIAN, ScaleType.NATURAL_MINOR, true, listOf(InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.BASS, InstrumentKind.DRUMS, InstrumentKind.PIANO), true, ScaleType.BLUES),
    PROG_ROCK("Progressive rock", 90..150, ScaleType.DORIAN, ScaleType.NATURAL_MINOR, false, listOf(InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.BASS, InstrumentKind.DRUMS, InstrumentKind.ORGAN, InstrumentKind.SYNTH_LEAD), true, ScaleType.DORIAN),
    METAL("Metal", 120..190, ScaleType.PHRYGIAN, ScaleType.PHRYGIAN, false, listOf(InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.BASS, InstrumentKind.DRUMS), false, ScaleType.HARMONIC_MINOR),
    CLASSICAL("Classica", 60..120, ScaleType.MAJOR, ScaleType.HARMONIC_MINOR, false, listOf(InstrumentKind.PIANO, InstrumentKind.STRINGS, InstrumentKind.WOODWIND), true, ScaleType.MAJOR),
    ELECTRONIC("Elettronica", 118..132, ScaleType.NATURAL_MINOR, ScaleType.NATURAL_MINOR, false, listOf(InstrumentKind.SYNTH_LEAD, InstrumentKind.SYNTH_PAD, InstrumentKind.BASS, InstrumentKind.DRUMS), false, ScaleType.NATURAL_MINOR),
    POP("Pop", 90..128, ScaleType.MAJOR, ScaleType.NATURAL_MINOR, false, listOf(InstrumentKind.PIANO, InstrumentKind.GUITAR, InstrumentKind.BASS, InstrumentKind.DRUMS, InstrumentKind.VOICE), false, ScaleType.MAJOR_PENTATONIC),
}

data class ComposeRequest(
    val genre: Genre = Genre.ROCK,
    val bpm: Double? = null,
    val tonic: Int = 9,
    val minor: Boolean = true,
    val durationSec: Double = 240.0,
    /** Struttura esplicita; se vuota viene pianificata automaticamente in base alla durata. */
    val structure: List<SectionType> = emptyList(),
    val instruments: List<InstrumentKind> = emptyList(),
    /** 0 = semplice, 1 = molto elaborato (densità ritmica, settime, cromatismi, fill). */
    val complexity: Double = 0.5,
    val character: Character = Character.ENERGICO,
    val seed: Long = 1,
    val title: String = "Composizione AmpyComposer",
)

/** Piano di una sezione: battute, armonia (un accordo per battuta o mezza battuta), energia 0..1. */
data class SectionPlan(val type: SectionType, val bars: Int, val chords: List<Chord>, val chordsPerBar: Int, val energy: Double, val startBar: Int, val transpose: Int = 0)

class HierarchicalComposer(private val req: ComposeRequest) {
    private val rnd = Random(req.seed)
    private val g = req.genre
    private val bpm = req.bpm ?: (g.bpmRange.first + (g.bpmRange.last - g.bpmRange.first) * when (req.character) {
        Character.CALMO, Character.MALINCONICO -> 0.2; Character.MISTERIOSO -> 0.4; Character.GIOIOSO -> 0.65; Character.ENERGICO -> 0.75; Character.AGGRESSIVO -> 0.95 })
    private val beatsPerBar = 4
    private val secPerBeat = 60.0 / bpm
    private val scale = Scale(req.tonic, if (req.minor) g.minorScale else g.majorScale)
    private val instruments = req.instruments.ifEmpty { g.instruments }

    // ---------------- 1. struttura ----------------
    fun planStructure(): List<Pair<SectionType, Int>> {
        val barSec = beatsPerBar * secPerBeat
        val targetBars = (req.durationSec / barSec).roundToInt().coerceAtLeast(16)
        val base = req.structure.ifEmpty {
            val core = mutableListOf(SectionType.INTRO, SectionType.VERSE, SectionType.PRE_CHORUS, SectionType.CHORUS, SectionType.VERSE, SectionType.PRE_CHORUS, SectionType.CHORUS)
            if (g == Genre.CLASSICAL) mutableListOf(SectionType.INTRO, SectionType.VERSE, SectionType.CHORUS, SectionType.VERSE, SectionType.BRIDGE, SectionType.CHORUS, SectionType.OUTRO)
            else core.apply { add(SectionType.BRIDGE); add(SectionType.SOLO); add(SectionType.CHORUS); add(SectionType.OUTRO) }
        }
        val nominal = mapOf(SectionType.INTRO to 4, SectionType.VERSE to 8, SectionType.PRE_CHORUS to 4, SectionType.CHORUS to 8, SectionType.BRIDGE to 8, SectionType.SOLO to 8, SectionType.OUTRO to 4)
        val sections = base.map { it to nominal[it]!! }.toMutableList()
        // adatta alla durata: scala le sezioni lunghe a multipli di 4 battute, poi aggiunge/rimuove ripetizioni
        val nominalTotal = sections.sumOf { it.second }
        val factor = targetBars.toDouble() / nominalTotal
        for (i in sections.indices) {
            val (t, b) = sections[i]
            val scaled = if (t == SectionType.INTRO || t == SectionType.OUTRO || t == SectionType.PRE_CHORUS) b * minOf(2.0, factor) else b * factor
            sections[i] = t to (Math.round(scaled / 4.0) * 4).toInt().coerceIn(4, 32)
        }
        var total = sections.sumOf { it.second }
        var guard = 0
        val adjustable = setOf(SectionType.VERSE, SectionType.CHORUS, SectionType.SOLO, SectionType.BRIDGE)
        while (abs(total - targetBars) >= 4 && guard++ < 200) {
            if (total < targetBars && sections.filter { it.first in adjustable }.all { it.second >= 32 }) {
                // brani molto lunghi: si aggiungono ripetizioni (strofa + ritornello, o assolo) prima del finale
                val insertAt = sections.indexOfLast { it.first == SectionType.CHORUS }.coerceAtLeast(1)
                val extra = if (guard % 2 == 0 && g != Genre.CLASSICAL) listOf(SectionType.SOLO to 8) else listOf(SectionType.VERSE to 8, SectionType.CHORUS to 8)
                sections.addAll(insertAt, extra)
                total = sections.sumOf { it.second }; continue
            }
            val idx = sections.indices.filter { sections[it].first in adjustable }.let { it[guard % it.size] }
            val (t, b) = sections[idx]
            sections[idx] = t to if (total < targetBars) (b + 4).coerceAtMost(32) else (b - 4).coerceAtLeast(4)
            total = sections.sumOf { it.second }
        }
        return sections
    }

    // ---------------- 2. armonia ----------------
    private val progressionCache = HashMap<SectionType, List<Chord>>()

    private fun degreeChord(deg: Int): Chord {
        val c = if (scale.type.steps.size == 7) scale.diatonicChord(deg, seventh = g.sevenths && rnd.nextDouble() < 0.4 + req.complexity * 0.5) else Chord((scale.tonic + listOf(0, 5, 7, 3)[deg % 4]) % 12, ChordQuality.MINOR)
        return when (g) {
            Genre.METAL -> Chord(c.root, ChordQuality.POWER)
            Genre.BLUES -> Chord(c.root, ChordQuality.DOM7)
            else -> c
        }
    }

    private fun progressionFor(type: SectionType, length: Int): List<Chord> = progressionCache.getOrPut(type) {
        val pools: List<List<Int>> = when {
            g == Genre.BLUES -> listOf(listOf(0, 0, 0, 0, 3, 3, 0, 0, 4, 3, 0, 4))
            g == Genre.JAZZ -> listOf(listOf(1, 4, 0, 0), listOf(0, 5, 1, 4), listOf(2, 5, 1, 4))
            g == Genre.METAL -> listOf(listOf(0, 1, 0, 6), listOf(0, 5, 6, 0), listOf(0, 0, 1, 0))
            g == Genre.CLASSICAL -> listOf(listOf(0, 3, 4, 0), listOf(0, 5, 1, 4), listOf(0, 3, 1, 4))
            req.minor -> when (type) {
                SectionType.CHORUS -> listOf(listOf(0, 5, 2, 6), listOf(5, 6, 0, 0))
                SectionType.PRE_CHORUS -> listOf(listOf(3, 4, 5, 6))
                SectionType.BRIDGE -> listOf(listOf(5, 2, 6, 4), listOf(3, 0, 6, 4))
                else -> listOf(listOf(0, 6, 5, 6), listOf(0, 3, 6, 0), listOf(0, 5, 3, 4))
            }
            else -> when (type) {
                SectionType.CHORUS -> listOf(listOf(0, 4, 5, 3), listOf(3, 0, 4, 5))
                SectionType.PRE_CHORUS -> listOf(listOf(1, 3, 4, 4), listOf(5, 3, 1, 4))
                SectionType.BRIDGE -> listOf(listOf(5, 3, 0, 4), listOf(1, 4, 2, 5))
                else -> listOf(listOf(0, 5, 3, 4), listOf(0, 3, 0, 4), listOf(0, 2, 3, 4))
            }
        }
        val pat = pools[rnd.nextInt(pools.size)]
        val chords = (0 until length).map { degreeChord(pat[it % pat.size]) }.toMutableList()
        // cadenza: ultima battuta del ritornello/outro sulla tonica (o dominante prima della ripresa)
        if (type == SectionType.OUTRO || type == SectionType.CHORUS) chords[chords.size - 1] = degreeChord(0)
        if (type == SectionType.PRE_CHORUS && scale.type.steps.size == 7) chords[chords.size - 1] = scale.diatonicChord(4, seventh = true).let { if (req.minor) Chord(it.root, ChordQuality.DOM7) else it }
        chords
    }

    fun planSections(): List<SectionPlan> {
        var bar = 0
        val struct = planStructure()
        val lastChorus = struct.indexOfLast { it.first == SectionType.CHORUS }
        return struct.mapIndexed { i, (t, bars) ->
            val chordsPerBar = if (g == Genre.JAZZ && req.complexity > 0.6) 2 else 1
            val n = bars * chordsPerBar
            val prog = progressionFor(t, minOf(n, if (g == Genre.BLUES) 12 else 4 * chordsPerBar))
            val chords = (0 until n).map { prog[it % prog.size] }
            val energy = when (t) {
                SectionType.INTRO -> 0.3; SectionType.VERSE -> 0.5; SectionType.PRE_CHORUS -> 0.65; SectionType.CHORUS -> 0.85
                SectionType.BRIDGE -> 0.55; SectionType.SOLO -> 0.9; SectionType.OUTRO -> 0.35
            } * (if (req.character == Character.CALMO) 0.7 else 1.0)
            // modulazione dell'ultimo ritornello (+2 semitoni) per pop/rock con complessità medio-alta
            val transpose = if (i == lastChorus && req.complexity > 0.55 && g in setOf(Genre.POP, Genre.ROCK)) 2 else 0
            SectionPlan(t, bars, chords, chordsPerBar, energy, bar, transpose).also { bar += bars }
        }
    }

    // ---------------- 3-6. generazione delle parti ----------------
    private fun sec(beat: Double) = beat * secPerBeat
    private fun swingBeat(beat: Double): Double {
        if (!g.swing) return beat
        val frac = beat - Math.floor(beat)
        return if (abs(frac - 0.5) < 1e-6) Math.floor(beat) + 2.0 / 3 else beat
    }
    private fun note(p: Int, startBeat: Double, durBeats: Double, vel: Int, transpose: Int = 0): NoteEvent {
        val s = sec(swingBeat(startBeat)); val e = sec(swingBeat(startBeat) + durBeats)
        return NoteEvent((p + transpose).coerceIn(0, 127), s, maxOf(e, s + 0.02), vel.coerceIn(1, 127))
    }
    private fun chordAt(plan: SectionPlan, barInSection: Int, half: Int): Chord = plan.chords[(barInSection * plan.chordsPerBar + if (plan.chordsPerBar > 1) half else 0).coerceAtMost(plan.chords.size - 1)]

    /** Motivo: ritmo (onset in beat entro 2 battute, durate) + profilo melodico in gradi relativi. */
    private data class Motif(val onsets: List<Double>, val durs: List<Double>, val contour: List<Int>)

    private val motifCache = HashMap<SectionType, Motif>()

    private fun motifFor(t: SectionType, energy: Double): Motif = motifCache.getOrPut(t) {
        val density = 0.35 + 0.45 * req.complexity + 0.2 * energy
        val grid = if (req.complexity > 0.7 && g != Genre.CLASSICAL) 0.25 else 0.5
        val onsets = ArrayList<Double>()
        var b = 0.0
        while (b < 8.0 - 1e-9) {
            val strong = abs(b - Math.round(b)) < 1e-9
            if (rnd.nextDouble() < (if (strong) density + 0.2 else density * 0.7)) onsets.add(b)
            b += grid
        }
        if (onsets.isEmpty() || onsets[0] > 0) onsets.add(0, 0.0)
        val durs = onsets.mapIndexed { i, o -> ((onsets.getOrNull(i + 1) ?: 8.0) - o).coerceAtMost(2.0) * (if (rnd.nextDouble() < 0.2) 0.6 else 0.95) }
        // profilo: passi congiunti con salti occasionali, ritorno verso il centro
        var deg = 0
        val contour = onsets.map { val step = if (rnd.nextDouble() < 0.75) listOf(-1, 1, 1, -1, 0, 2, -2).random(rnd) else listOf(3, -3, 4, -4).random(rnd); deg = (deg + step).coerceIn(-4, 6); deg }
        Motif(onsets, durs, contour)
    }

    /** Sviluppa il motivo: ripetizione, sequenza, inversione, variazione ritmica. */
    private fun develop(m: Motif, variant: Int): Motif = when (variant % 4) {
        0 -> m
        1 -> m.copy(contour = m.contour.map { it + 2 })                                // sequenza
        2 -> m.copy(contour = m.contour.map { -it + m.contour.first() * 2 })           // inversione
        else -> {                                                                      // variazione ritmica: ultime note allungate
            val keep = (m.onsets.size * 0.7).toInt().coerceAtLeast(1)
            Motif(m.onsets.take(keep), m.durs.take(keep).mapIndexed { i, d -> if (i == keep - 1) 8.0 - m.onsets[keep - 1] else d }, m.contour.take(keep))
        }
    }

    private fun nearestChordTone(p: Int, chord: Chord): Int {
        var best = p; var bd = 99
        for (d in -6..6) if (((p + d) % 12 + 12) % 12 in chord.pitchClasses && abs(d) < bd) { bd = abs(d); best = p + d }
        return best
    }

    private fun melody(plans: List<SectionPlan>, range: IntRange, vel: Int): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        val center = (range.first + range.last) / 2
        val centerDegree = run { var d = 0; while (scale.degreeToPitch(d, 0) < center - 12) d++; d }
        val occurrence = HashMap<SectionType, Int>()
        for (pl in plans) {
            if (pl.type == SectionType.INTRO || pl.type == SectionType.SOLO) continue
            val occ = occurrence.merge(pl.type, 1, Int::plus)!! - 1
            val m = motifFor(pl.type, pl.energy)
            for (phrase in 0 until pl.bars / 2) {
                val dm = develop(m, if (pl.type == SectionType.CHORUS) listOf(0, 1, 0, 3)[phrase % 4] else phrase + occ)
                for (k in dm.onsets.indices) {
                    val beatInPhrase = dm.onsets[k]
                    val barInSec = phrase * 2 + (beatInPhrase / beatsPerBar).toInt()
                    if (barInSec >= pl.bars) continue
                    val chord = chordAt(pl, barInSec, if ((beatInPhrase % beatsPerBar) >= 2) 1 else 0)
                    var p = scale.degreeToPitch(centerDegree + dm.contour[k], 0)
                    val strong = abs(beatInPhrase - Math.round(beatInPhrase)) < 1e-9 && Math.round(beatInPhrase) % 2 == 0L
                    if (strong) p = nearestChordTone(p, chord)
                    while (p > range.last) p -= 12; while (p < range.first) p += 12
                    // fine frase: risoluzione su nota dell'accordo, durata lunga
                    val last = k == dm.onsets.size - 1 && phrase == pl.bars / 2 - 1
                    if (last) p = nearestChordTone(p, chord)
                    val start = (pl.startBar + phrase * 2) * beatsPerBar + beatInPhrase
                    out.add(note(p, start, dm.durs[k], vel + (if (strong) 8 else 0) + (pl.energy * 20).toInt() - 10, pl.transpose))
                }
            }
        }
        return out
    }

    private fun solo(plans: List<SectionPlan>, range: IntRange, vel: Int): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        val sScale = Scale(scale.tonic, g.soloScale)
        for (pl in plans.filter { it.type == SectionType.SOLO }) {
            var p = (range.first + range.last) / 2
            for (bar in 0 until pl.bars) {
                val dens = 0.4 + 0.5 * (bar.toDouble() / pl.bars) + 0.2 * req.complexity // intensità crescente
                val grid = if (dens > 0.8) 0.25 else 0.5
                var b = 0.0
                val chord = chordAt(pl, bar, 0)
                while (b < beatsPerBar) {
                    if (rnd.nextDouble() < dens) {
                        p = sScale.snap(p + listOf(-2, -1, 1, 2, 3, -3, 5).random(rnd))
                        if (abs(b - Math.round(b)) < 1e-9 && rnd.nextDouble() < 0.5) p = nearestChordTone(p, chord)
                        while (p > range.last) p -= 12; while (p < range.first) p += 12
                        out.add(note(p, (pl.startBar + bar) * beatsPerBar + b, grid * 0.9, vel + rnd.nextInt(-8, 9)))
                    }
                    b += grid
                }
            }
        }
        return out
    }

    private fun bass(plans: List<SectionPlan>): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        for (pl in plans) for (bar in 0 until pl.bars) {
            val base = (pl.startBar + bar) * beatsPerBar.toDouble()
            val c = chordAt(pl, bar, 0); val c2 = chordAt(pl, bar, 1)
            val root = 36 + ((c.bass - 36 % 12) % 12 + 12) % 12 // E1..D#2 circa
            val r = if (root > 45) root - 12 else root
            val fifth = r + 7
            val v = (70 + pl.energy * 40).toInt()
            if (pl.type == SectionType.INTRO && pl.energy < 0.4) { out.add(note(r, base, 4.0, v - 15, pl.transpose)); continue }
            when (g) {
                Genre.JAZZ, Genre.BLUES -> { // walking bass: toniche, note dell'accordo, avvicinamento cromatico
                    val next = plans.flatMap { p -> List(p.bars) { b -> chordAt(p, b, 0) } }.getOrNull(pl.startBar + bar + 1) ?: c
                    val nextRoot = 36 + ((next.bass - 0) % 12)
                    val tones = listOf(r, r + c.quality.intervals.getOrElse(1) { 4 }, r + 7, nextRoot + (if (rnd.nextBoolean()) -1 else 1))
                    tones.forEachIndexed { i, p -> out.add(note(if (p > 52) p - 12 else p, base + i, 0.95, v, pl.transpose)) }
                }
                Genre.FUNK -> listOf(0.0 to r, 0.75 to r + 12, 1.5 to r, 2.0 to fifth, 2.75 to r + 10, 3.5 to r).forEach { (b, p) -> if (b == 0.0 || rnd.nextDouble() < 0.5 + req.complexity * 0.4) out.add(note(p, base + b, 0.22, v + if (b == 0.0) 10 else 0, pl.transpose)) }
                Genre.METAL -> for (i in 0 until 8) out.add(note(r, base + i * 0.5, 0.45, v, pl.transpose))
                Genre.ELECTRONIC -> for (i in 0 until 4) out.add(note(r, base + i + 0.5, 0.45, v, pl.transpose))
                Genre.CLASSICAL -> { out.add(note(r, base, 2.0, v - 10, pl.transpose)); out.add(note(fifth - 12, base + 2, 2.0, v - 15, pl.transpose)) }
                else -> {
                    val pattern = if (pl.energy > 0.7) listOf(0.0, 1.0, 1.5, 2.0, 3.0, 3.5) else listOf(0.0, 2.0, 2.5)
                    pattern.forEachIndexed { i, b -> val cc = if (b >= 2 && pl.chordsPerBar > 1) c2 else c; val rr = 36 + (cc.bass % 12); out.add(note(if (i % 3 == 2) rr + 7 else rr, base + b, 0.45, v, pl.transpose)) }
                }
            }
        }
        return out
    }

    private fun drums(plans: List<SectionPlan>): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        val kick = 36; val snare = 38; val hat = 42; val open = 46; val ride = 51; val crash = 49
        fun hit(p: Int, beat: Double, v: Int) { out.add(NoteEvent(p, sec(swingBeat(beat)), sec(swingBeat(beat)) + 0.08, v.coerceIn(1, 127), 1f)) }
        for ((pi, pl) in plans.withIndex()) {
            if (g == Genre.CLASSICAL) continue
            for (bar in 0 until pl.bars) {
                val base = (pl.startBar + bar) * beatsPerBar.toDouble()
                val e = pl.energy; val v = (60 + e * 50).toInt()
                if (bar == 0 && pi > 0) hit(crash, base, v + 10)
                if (pl.type == SectionType.INTRO && e < 0.4) { hit(hat, base, v - 20); hit(hat, base + 2, v - 20); continue }
                val lastBar = bar == pl.bars - 1 && pi < plans.size - 1
                when (g) {
                    Genre.JAZZ, Genre.BLUES -> {
                        listOf(0.0, 1.0, 1.5, 2.0, 3.0, 3.5).forEach { hit(ride, base + it, v - 10) }
                        hit(44, base + 1, v - 25); hit(44, base + 3, v - 25)
                        if (rnd.nextDouble() < 0.3 * req.complexity) hit(snare, base + 2.5, v - 30)
                    }
                    Genre.FUNK -> {
                        for (i in 0 until 16) hit(hat, base + i * 0.25, if (i % 2 == 0) v - 10 else v - 30)
                        hit(kick, base, v); hit(kick, base + 1.75, v - 10); hit(kick, base + 2.5, v - 5)
                        hit(snare, base + 1, v + 5); hit(snare, base + 3, v + 5)
                        if (req.complexity > 0.4) { hit(snare, base + 2.25, v - 45); hit(snare, base + 3.75, v - 45) } // ghost notes
                    }
                    Genre.METAL -> {
                        for (i in 0 until 16) hit(kick, base + i * 0.25, v - 5)
                        hit(snare, base + 1, v + 10); hit(snare, base + 3, v + 10)
                        for (i in 0 until 4) hit(if (e > 0.8) ride else hat, base + i, v - 10)
                    }
                    Genre.ELECTRONIC -> {
                        for (i in 0 until 4) { hit(kick, base + i, v + 5); hit(open, base + i + 0.5, v - 15) }
                        if (e > 0.5) { hit(snare, base + 1, v); hit(snare, base + 3, v) }
                        if (req.complexity > 0.5) for (i in 0 until 8) hit(hat, base + i * 0.5 + 0.25, v - 35)
                    }
                    else -> {
                        val sub = if (e > 0.75) 0.5 else 1.0
                        var b = 0.0; while (b < 4) { hit(if (e > 0.85 && pl.type == SectionType.CHORUS) ride else hat, base + b, v - 15); b += sub }
                        hit(kick, base, v); hit(kick, base + 2, v - 5); if (e > 0.6) hit(kick, base + 2.5, v - 15)
                        hit(snare, base + 1, v + 5); hit(snare, base + 3, v + 5)
                    }
                }
                if (lastBar && req.complexity > 0.25) { // fill di fine sezione
                    val toms = listOf(snare, 48, 47, 45, 43, 41)
                    val n = if (req.complexity > 0.6) 8 else 4
                    for (i in 0 until n) hit(toms[(i * toms.size) / n], base + 2 + i * (2.0 / n), v + i * 2)
                }
            }
        }
        return out
    }

    private fun comping(plans: List<SectionPlan>, kind: InstrumentKind): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        var prevVoicing = listOf(60, 64, 67)
        fun voiceLead(c: Chord, center: Int): List<Int> {
            // sceglie il rivolto più vicino al precedente (condotta delle parti)
            val base = c.voicing(center)
            val cands = (0 until base.size).map { inv -> base.mapIndexed { i, p -> if (i < inv) p + 12 else p }.sorted() } + listOf(base.map { it - 12 })
            return cands.minBy { v -> v.zip(prevVoicing).sumOf { (a, b) -> abs(a - b) } + abs(v.average() - center) * 0.5 }.also { prevVoicing = it }
        }
        for (pl in plans) for (bar in 0 until pl.bars) {
            val base = (pl.startBar + bar) * beatsPerBar.toDouble()
            val v = (50 + pl.energy * 45).toInt()
            for (half in 0 until pl.chordsPerBar) {
                val c = chordAt(pl, bar, half)
                val hb = base + half * (4.0 / pl.chordsPerBar); val len = 4.0 / pl.chordsPerBar
                val center = when (kind) { InstrumentKind.STRINGS, InstrumentKind.SYNTH_PAD -> 62; InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.GUITAR -> 57; else -> 60 }
                val voicing = voiceLead(c, center)
                when {
                    kind == InstrumentKind.STRINGS || kind == InstrumentKind.SYNTH_PAD || kind == InstrumentKind.ORGAN ->
                        voicing.forEach { out.add(note(it, hb, len * 0.98, v - 10, pl.transpose)) }
                    g == Genre.CLASSICAL || (kind == InstrumentKind.PIANO && pl.energy < 0.5) -> { // arpeggio (basso albertino semplificato)
                        val arp = listOf(voicing[0], voicing.last(), voicing[1 % voicing.size], voicing.last())
                        val step = len / (if (req.complexity > 0.5) 8 else 4)
                        var b = 0.0; var i = 0
                        while (b < len - 1e-9) { out.add(note(arp[i % arp.size], hb + b, step * 0.95, v - 5, pl.transpose)); b += step; i++ }
                    }
                    g == Genre.FUNK -> listOf(0.5, 1.25, 2.5, 3.25).filter { it < len }.forEach { b -> voicing.forEach { out.add(note(it + 12, hb + b, 0.18, v, pl.transpose)) } }
                    g == Genre.JAZZ -> listOf(0.0, 1.5, 2.5).filter { it < len && rnd.nextDouble() < 0.8 }.forEach { b -> voicing.forEach { out.add(note(it, hb + b, 0.7, v - 10, pl.transpose)) } }
                    g == Genre.METAL || g == Genre.ROCK && kind == InstrumentKind.ELECTRIC_GUITAR -> {
                        val power = listOf(36 + 12 + c.root % 12, 36 + 12 + c.root % 12 + 7, 36 + 24 + c.root % 12)
                        val stepB = if (pl.energy > 0.7) 0.5 else 1.0
                        var b = 0.0; while (b < len - 1e-9) { power.forEach { out.add(note(it, hb + b, stepB * 0.9, v, pl.transpose)) }; b += stepB }
                    }
                    else -> listOf(0.0, 1.0, 2.0, 3.0).filter { it < len }.forEachIndexed { i, b -> if (i == 0 || pl.energy > 0.45) voicing.forEach { out.add(note(it, hb + b, 0.9, v - (if (i % 2 == 1) 10 else 0), pl.transpose)) } }
                }
            }
        }
        return out
    }

    fun compose(ctx: JobContext = JobContext()): Project {
        val plans = planSections()
        ctx.progress.report(0.2, "Struttura e armonia pianificate")
        val tracks = ArrayList<Track>()
        val melodic = instruments.firstOrNull { it in setOf(InstrumentKind.VOICE, InstrumentKind.SYNTH_LEAD, InstrumentKind.BRASS, InstrumentKind.WOODWIND) }
            ?: instruments.firstOrNull { it != InstrumentKind.BASS && !it.isDrum } ?: InstrumentKind.PIANO
        val soloInst = instruments.firstOrNull { it in setOf(InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.SYNTH_LEAD, InstrumentKind.BRASS) } ?: melodic
        var tid = 0
        fun add(name: String, kind: InstrumentKind, notes: List<NoteEvent>) { if (notes.isNotEmpty()) tracks.add(Track("c${tid++}", name, kind, notes.sortedBy { it.startSec })) }
        val melRange = when (melodic) { InstrumentKind.VOICE -> 57..76; InstrumentKind.BRASS -> 55..79; else -> 60..84 }
        add("Melodia", melodic, melody(plans, melRange, 92))
        ctx.progress.report(0.4, "Melodia sviluppata")
        add("Assolo", soloInst, solo(plans, 62..88, 96))
        if (InstrumentKind.BASS in instruments) add("Basso", InstrumentKind.BASS, bass(plans))
        ctx.progress.report(0.6, "Linea di basso")
        if (instruments.any { it.isDrum }) add("Batteria", InstrumentKind.DRUMS, drums(plans))
        ctx.progress.report(0.8, "Ritmica")
        for (k in instruments.filter { !it.isDrum && it != InstrumentKind.BASS && it != melodic }.distinct()) add("Accompagnamento ${italianName(k)}", k, comping(plans, k))
        val totalBars = plans.sumOf { it.bars }
        val duration = sec(totalBars * beatsPerBar.toDouble())
        ctx.progress.report(1.0, "Composizione completata")
        return Project(
            id = "comp-${req.seed}", title = req.title, durationSec = duration,
            tempo = listOf(TempoChange(0.0, Math.round(bpm * 10) / 10.0)), key = KeySignature(req.tonic, req.minor), timeSignature = TimeSignature(4, 4),
            tracks = tracks,
            sections = plans.map { Section(it.type.label, sec(it.startBar * beatsPerBar.toDouble()), sec((it.startBar + it.bars) * beatsPerBar.toDouble())) },
            createdAtMs = System.currentTimeMillis(), modifiedAtMs = System.currentTimeMillis(),
        )
    }

    private fun italianName(k: InstrumentKind) = when (k) {
        InstrumentKind.PIANO -> "pianoforte"; InstrumentKind.ELECTRIC_PIANO -> "piano elettrico"; InstrumentKind.ORGAN -> "organo"
        InstrumentKind.GUITAR -> "chitarra"; InstrumentKind.ELECTRIC_GUITAR -> "chitarra elettrica"; InstrumentKind.STRINGS -> "archi"
        InstrumentKind.BRASS -> "ottoni"; InstrumentKind.WOODWIND -> "legni"; InstrumentKind.SYNTH_LEAD -> "synth"; InstrumentKind.SYNTH_PAD -> "pad"
        InstrumentKind.VOICE -> "voce"; else -> k.name.lowercase()
    }

    /** Accordi pianificati come segmenti (per sigle sullo spartito). */
    fun chordSegments(plans: List<SectionPlan>): List<ChordSegment> = plans.flatMap { pl ->
        pl.chords.mapIndexed { i, c ->
            val beat = pl.startBar * beatsPerBar + i * (beatsPerBar.toDouble() / pl.chordsPerBar)
            ChordSegment(sec(beat), sec(beat + beatsPerBar.toDouble() / pl.chordsPerBar), Chord((c.root + pl.transpose) % 12, c.quality, (c.bass + pl.transpose) % 12), 1.0)
        }
    }
}
