package org.ampy.composerai

import org.ampy.core.NoteEvent
import org.ampy.musicbrain.ChordQuality
import org.ampy.musicbrain.ChordSegment
import org.ampy.musicbrain.KeyDetector

/**
 * Analisi di un brano di riferimento fornito dall'utente: estrae SOLO caratteristiche generali
 * (tempo, modo, densità, vocabolario accordale, registro). Melodie e arrangiamenti non vengono
 * memorizzati né riutilizzati, così da non ricopiare materiale protetto.
 */
data class ReferenceStyle(
    val bpm: Double,
    val minor: Boolean,
    val tonic: Int,
    val notesPerSecond: Double,
    val seventhRatio: Double,
    val meanPitch: Double,
    val suggestedComplexity: Double,
) {
    fun applyTo(req: ComposeRequest, keepKey: Boolean = false): ComposeRequest = req.copy(
        bpm = bpm, minor = minor, tonic = if (keepKey) req.tonic else tonic,
        complexity = suggestedComplexity,
    )

    companion object {
        fun analyze(notes: List<NoteEvent>, bpm: Double, chords: List<ChordSegment>): ReferenceStyle {
            val key = KeyDetector.detect(notes).key
            val dur = (notes.maxOfOrNull { it.endSec } ?: 1.0) - (notes.minOfOrNull { it.startSec } ?: 0.0)
            val nps = notes.size / dur.coerceAtLeast(1.0)
            val withChord = chords.mapNotNull { it.chord }
            val sevenths = withChord.count { it.quality in setOf(ChordQuality.DOM7, ChordQuality.MAJ7, ChordQuality.MIN7, ChordQuality.HALF_DIM) }
            val ratio = if (withChord.isEmpty()) 0.0 else sevenths.toDouble() / withChord.size
            val complexity = (0.25 + nps / 20.0 + ratio * 0.4).coerceIn(0.1, 0.95)
            return ReferenceStyle(bpm, key.isMinor, key.tonicPitchClass, nps, ratio, notes.map { it.pitch }.average().takeIf { !it.isNaN() } ?: 60.0, complexity)
        }
    }
}
