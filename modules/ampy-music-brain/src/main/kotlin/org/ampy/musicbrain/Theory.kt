package org.ampy.musicbrain

import org.ampy.core.ModuleInfo

object MusicBrainModule {
    val info = ModuleInfo("AmpyMusicBrain", "0.1.0", "Tonalità, accordi, cambi armonici, struttura del brano, teoria")
}

enum class ChordQuality(val symbol: String, val intervals: IntArray) {
    MAJOR("", intArrayOf(0, 4, 7)),
    MINOR("m", intArrayOf(0, 3, 7)),
    DOM7("7", intArrayOf(0, 4, 7, 10)),
    MAJ7("maj7", intArrayOf(0, 4, 7, 11)),
    MIN7("m7", intArrayOf(0, 3, 7, 10)),
    DIM("dim", intArrayOf(0, 3, 6)),
    HALF_DIM("m7b5", intArrayOf(0, 3, 6, 10)),
    AUG("aug", intArrayOf(0, 4, 8)),
    SUS2("sus2", intArrayOf(0, 2, 7)),
    SUS4("sus4", intArrayOf(0, 5, 7)),
    POWER("5", intArrayOf(0, 7)),
}

data class Chord(val root: Int, val quality: ChordQuality, val bass: Int = root) {
    val pitchClasses: List<Int> get() = quality.intervals.map { (root + it) % 12 }
    val symbol: String get() {
        val s = NAMES_SHARP[root] + quality.symbol
        return if (bass != root) s + "/" + NAMES_SHARP[bass] else s
    }
    /** Note MIDI in posizione stretta attorno a [around]. */
    fun voicing(around: Int = 60): List<Int> {
        val base = around - ((around - root) % 12 + 12) % 12
        return quality.intervals.map { base + it }
    }
    companion object {
        val NAMES_SHARP = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    }
}

enum class ScaleType(val steps: IntArray) {
    MAJOR(intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR(intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    HARMONIC_MINOR(intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    DORIAN(intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    MIXOLYDIAN(intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    PHRYGIAN(intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    BLUES(intArrayOf(0, 3, 5, 6, 7, 10)),
    MINOR_PENTATONIC(intArrayOf(0, 3, 5, 7, 10)),
    MAJOR_PENTATONIC(intArrayOf(0, 2, 4, 7, 9)),
}

data class Scale(val tonic: Int, val type: ScaleType) {
    fun contains(pitch: Int) = ((pitch - tonic) % 12 + 12) % 12 in type.steps
    /** Grado (0-based, può essere negativo o > 6) -> altezza MIDI con ottava di riferimento [octaveBase]. */
    fun degreeToPitch(degree: Int, octaveBase: Int = 60): Int {
        val n = type.steps.size
        val oct = Math.floorDiv(degree, n); val idx = Math.floorMod(degree, n)
        return octaveBase + tonic % 12 + oct * 12 + type.steps[idx]
    }
    /** Altezza della scala più vicina a [pitch]. */
    fun snap(pitch: Int): Int {
        for (d in 0..6) { if (contains(pitch - d)) return pitch - d; if (contains(pitch + d)) return pitch + d }
        return pitch
    }
    /** Accordo diatonico (triade o settima) costruito sul grado [degree] (solo scale eptatoniche). */
    fun diatonicChord(degree: Int, seventh: Boolean = false): Chord {
        require(type.steps.size == 7)
        val pcs = (0 until if (seventh) 4 else 3).map { (tonic + type.steps[(degree + 2 * it) % 7]) % 12 }
        val root = pcs[0]
        val ints = pcs.map { ((it - root) % 12 + 12) % 12 }.toIntArray()
        val q = ChordQuality.entries.firstOrNull { it.intervals.contentEquals(ints) } ?: ChordQuality.MAJOR
        return Chord(root, q)
    }
}
