package org.ampy.instrumentai

import org.ampy.audioengine.Stft
import org.ampy.core.InstrumentKind
import org.ampy.core.ModuleInfo
import org.ampy.core.NoteEvent
import kotlin.math.exp
import kotlin.math.ln

object InstrumentAIModule {
    val info = ModuleInfo("AmpyInstrumentAI", "0.1.0", "Riconoscimento euristico della famiglia strumentale da descrittori audio e note")
}

/** Descrittori calcolati su uno stem o una traccia. */
data class TimbreFeatures(
    val spectralCentroidHz: Double,
    val spectralFlatness: Double,
    val percussiveRatio: Double,
    val medianPitch: Double?,
    val pitchRange: Int,
    val polyphony: Double,
    val meanNoteSec: Double,
)

data class InstrumentGuess(val kind: InstrumentKind, val confidence: Double, val ranking: List<Pair<InstrumentKind, Double>>, val method: String = "euristico")

/**
 * Classificatore euristico trasparente (nessun modello addestrato): punteggi basati su registro,
 * polifonia, brillantezza e componente percussiva. La confidenza è volutamente prudente; per
 * un riconoscimento affidabile si consiglia di usare il nome dello stem prodotto dal separatore neurale.
 */
object InstrumentClassifier {
    fun features(x: FloatArray?, sr: Int, notes: List<NoteEvent>): TimbreFeatures {
        var centroid = 0.0; var flat = 0.0; var perc = 0.0
        if (x != null && x.size > 4096) {
            val spec = Stft(2048, 1024).forward(x)
            var cn = 0.0; var cd = 0.0; var fsum = 0.0; var fcount = 0; var prev: FloatArray? = null; var flux = 0.0; var energy = 0.0
            for (row in spec.mag) {
                var am = 0.0; var gm = 0.0; var e = 0.0
                for (b in 1 until row.size) {
                    val m = row[b].toDouble(); val f = b.toDouble() * sr / 2048
                    cn += f * m; cd += m; am += m; gm += ln(m + 1e-10); e += m * m
                    prev?.let { p -> val d = m - p[b]; if (d > 0) flux += d }
                }
                energy += kotlin.math.sqrt(e)
                if (am > 1e-6) { fsum += exp(gm / (row.size - 1)) / (am / (row.size - 1)); fcount++ }
                prev = row
            }
            centroid = if (cd > 0) cn / cd else 0.0
            flat = if (fcount > 0) fsum / fcount else 0.0
            perc = if (energy > 0) (flux / energy).coerceIn(0.0, 1.0) else 0.0
        }
        val pitches = notes.map { it.pitch }.sorted()
        val med = pitches.getOrNull(pitches.size / 2)?.toDouble()
        val range = if (pitches.isEmpty()) 0 else pitches.last() - pitches.first()
        val dur = notes.sumOf { it.durationSec }
        val span = if (notes.isEmpty()) 1.0 else (notes.maxOf { it.endSec } - notes.minOf { it.startSec }).coerceAtLeast(1e-3)
        return TimbreFeatures(centroid, flat, perc, med, range, dur / span, if (notes.isEmpty()) 0.0 else dur / notes.size)
    }

    fun classify(f: TimbreFeatures): InstrumentGuess {
        val s = LinkedHashMap<InstrumentKind, Double>()
        val med = f.medianPitch
        s[InstrumentKind.DRUMS] = 2.5 * f.spectralFlatness + 2.0 * f.percussiveRatio + (if (med == null) 1.0 else 0.0)
        if (med != null) {
            s[InstrumentKind.BASS] = gauss(med, 38.0, 7.0) * 2 + (if (f.polyphony < 1.3) 0.5 else -0.5)
            s[InstrumentKind.PIANO] = (f.pitchRange / 48.0).coerceAtMost(1.0) * 1.5 + (if (f.polyphony > 1.8) 1.0 else 0.0) + gauss(med, 62.0, 12.0) * 0.5
            s[InstrumentKind.GUITAR] = gauss(med, 57.0, 8.0) * 1.2 + (if (f.polyphony in 1.3..4.0) 0.6 else 0.0) + (if (f.meanNoteSec < 0.6) 0.3 else 0.0)
            s[InstrumentKind.VOICE] = gauss(med, 64.0, 7.0) * 1.0 + (if (f.polyphony < 1.15) 0.9 else -0.6) + (if (f.meanNoteSec > 0.25) 0.3 else 0.0)
            s[InstrumentKind.STRINGS] = gauss(med, 66.0, 10.0) * 0.6 + (if (f.meanNoteSec > 0.8) 0.6 else 0.0) + (if (f.polyphony > 1.5) 0.3 else 0.0)
            s[InstrumentKind.BRASS] = gauss(med, 62.0, 8.0) * 0.5 + (if (f.spectralCentroidHz > 1500) 0.4 else 0.0) + (if (f.polyphony < 1.2) 0.3 else 0.0)
            s[InstrumentKind.WOODWIND] = gauss(med, 72.0, 8.0) * 0.7 + (if (f.polyphony < 1.1) 0.4 else 0.0)
        }
        val ranking = s.entries.sortedByDescending { it.value }.map { it.key to it.value }
        val top = ranking.first(); val second = ranking.getOrNull(1)?.second ?: 0.0
        val conf = (1 / (1 + exp(-(top.second - second) * 2.5)) - 0.5) * 2 * 0.8 // prudente: max 0.8
        return InstrumentGuess(top.first, conf.coerceIn(0.0, 0.8), ranking)
    }

    private fun gauss(x: Double, mu: Double, sd: Double) = exp(-0.5 * ((x - mu) / sd) * ((x - mu) / sd))

    /** Programma General MIDI consigliato e nome italiano per la traccia. */
    fun displayName(k: InstrumentKind) = when (k) {
        InstrumentKind.PIANO -> "Pianoforte"; InstrumentKind.ELECTRIC_PIANO -> "Piano elettrico"; InstrumentKind.ORGAN -> "Organo"
        InstrumentKind.GUITAR -> "Chitarra"; InstrumentKind.ELECTRIC_GUITAR -> "Chitarra elettrica"; InstrumentKind.BASS -> "Basso"
        InstrumentKind.VOICE -> "Voce"; InstrumentKind.STRINGS -> "Archi"; InstrumentKind.BRASS -> "Ottoni"; InstrumentKind.WOODWIND -> "Legni"
        InstrumentKind.SYNTH_LEAD -> "Synth lead"; InstrumentKind.SYNTH_PAD -> "Synth pad"; InstrumentKind.DRUMS -> "Batteria"; InstrumentKind.OTHER -> "Altro"
    }
}
