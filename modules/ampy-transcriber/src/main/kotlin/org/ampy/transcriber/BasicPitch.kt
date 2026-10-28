package org.ampy.transcriber

import org.ampy.audioengine.Windows
import org.ampy.core.NoteEvent
import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Port fedele in Kotlin della post-elaborazione di Spotify Basic Pitch (Apache-2.0,
 * basic_pitch/note_creation.py e inference.py, v0.4.0). Le costanti e l'ordine delle operazioni
 * sono identici all'originale; la conformità è verificata da tools/reference/compare_basic_pitch.py.
 */
object BasicPitchConstants {
    const val FFT_HOP = 256
    const val SAMPLE_RATE = 22050
    const val WINDOW_SECONDS = 2
    const val ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP            // 86 (divisione intera come nell'originale)
    const val ANNOT_N_FRAMES = ANNOTATIONS_FPS * WINDOW_SECONDS  // 172
    const val AUDIO_N_SAMPLES = SAMPLE_RATE * WINDOW_SECONDS - FFT_HOP // 43844
    const val N_OVERLAPPING_FRAMES = 30
    const val OVERLAP_LEN = N_OVERLAPPING_FRAMES * FFT_HOP       // 7680
    const val HOP_SIZE = AUDIO_N_SAMPLES - OVERLAP_LEN           // 36164
    const val N_NOTES = 88
    const val N_CONTOUR = 264
    const val MIDI_OFFSET = 21
    const val MAX_FREQ_IDX = 87
    const val CONTOURS_BINS_PER_SEMITONE = 3
    const val FRAMES_KEPT_PER_WINDOW = ANNOT_N_FRAMES - N_OVERLAPPING_FRAMES // 142

    const val INPUT_NAME = "serving_default_input_2:0"
    const val OUTPUT_NOTE = "StatefulPartitionedCall:1"
    const val OUTPUT_ONSET = "StatefulPartitionedCall:2"
    const val OUTPUT_CONTOUR = "StatefulPartitionedCall:0"
}

/** Parametri di decodifica (valori predefiniti = quelli di basic_pitch.inference.predict). */
data class BasicPitchParams(
    val onsetThreshold: Double = 0.5,
    val frameThreshold: Double = 0.3,
    val minimumNoteLengthMs: Double = 127.70,
    val minimumFrequency: Double? = null,
    val maximumFrequency: Double? = null,
    val inferOnsets: Boolean = true,
    val melodiaTrick: Boolean = true,
    val includePitchBends: Boolean = true,
    val energyTolerance: Int = 11,
) {
    val minNoteLenFrames: Int get() = (minimumNoteLengthMs / 1000.0 * (BasicPitchConstants.SAMPLE_RATE.toDouble() / BasicPitchConstants.FFT_HOP)).roundToInt()
}

/** Matrice di attivazioni (frames x bins) in un unico FloatArray row-major. */
class Activations(val nFrames: Int, val nBins: Int, val data: FloatArray) {
    init { require(data.size == nFrames * nBins) }
    operator fun get(t: Int, f: Int) = data[t * nBins + f]
    fun slice(from: Int, to: Int) = Activations(to - from, nBins, data.copyOfRange(from * nBins, to * nBins))
}

/** Evento grezzo in indici di frame, come la tupla (start_idx, end_idx, pitch, amplitude) dell'originale. */
data class FrameNote(val startFrame: Int, val endFrame: Int, val pitch: Int, val amplitude: Double, val bends: List<Int>? = null)

object BasicPitchFraming {
    /** Numero di finestre per un segnale di [n] campioni (dopo il padding iniziale di overlap/2). */
    fun windowCount(n: Long): Int {
        val padded = n + BasicPitchConstants.OVERLAP_LEN / 2
        return ((padded + BasicPitchConstants.HOP_SIZE - 1) / BasicPitchConstants.HOP_SIZE).toInt().coerceAtLeast(1)
    }

    /**
     * Restituisce la finestra [index] (lunghezza AUDIO_N_SAMPLES, zero-padding a destra) leggendo i campioni
     * tramite [read](start, count) del segnale originale. Equivalente a get_audio_input/window_audio_file.
     */
    fun window(index: Int, totalSamples: Long, read: (Long, Int) -> FloatArray): FloatArray {
        val out = FloatArray(BasicPitchConstants.AUDIO_N_SAMPLES)
        val padStart = BasicPitchConstants.OVERLAP_LEN / 2
        val wStart = index.toLong() * BasicPitchConstants.HOP_SIZE - padStart // in coordinate del segnale originale
        val srcFrom = maxOf(0L, wStart)
        val srcTo = minOf(totalSamples, wStart + BasicPitchConstants.AUDIO_N_SAMPLES)
        if (srcTo > srcFrom) {
            val chunk = read(srcFrom, (srcTo - srcFrom).toInt())
            chunk.copyInto(out, (srcFrom - wStart).toInt())
        }
        return out
    }

    /** Frame di uscita totali dopo l'unwrap (n_output_frames_original). */
    fun outputFrames(totalSamples: Long): Int = floor(totalSamples * (BasicPitchConstants.ANNOTATIONS_FPS.toDouble() / BasicPitchConstants.SAMPLE_RATE)).toInt()

    /** Rimuove i 15 frame di sovrapposizione a inizio/fine di una finestra (172 -> 142 frame). */
    fun trimWindow(win: FloatArray, nBins: Int): FloatArray {
        val nOlap = BasicPitchConstants.N_OVERLAPPING_FRAMES / 2
        return win.copyOfRange(nOlap * nBins, (BasicPitchConstants.ANNOT_N_FRAMES - nOlap) * nBins)
    }
}

object BasicPitchNotes {

    /** Tempi in secondi dei frame (model_frames_to_time), incluso il "magic number" 0.0018 dell'originale. */
    fun frameTime(frame: Int): Double {
        val c = BasicPitchConstants
        val original = frame * c.FFT_HOP.toDouble() / c.SAMPLE_RATE
        val windowNumber = floor(frame.toDouble() / c.ANNOT_N_FRAMES)
        val windowOffset = (c.FFT_HOP.toDouble() / c.SAMPLE_RATE) * (c.ANNOT_N_FRAMES - c.AUDIO_N_SAMPLES.toDouble() / c.FFT_HOP) + 0.0018
        return original - windowOffset * windowNumber
    }

    private fun hzToMidi(hz: Double) = 12 * (ln(hz / 440.0) / ln(2.0)) + 69

    /** output_to_notes_polyphonic. Non modifica gli array in ingresso. */
    fun outputToNotesPolyphonic(frames: Activations, onsetsIn: Activations, p: BasicPitchParams): List<FrameNote> {
        val c = BasicPitchConstants
        val nFrames = frames.nFrames; val nF = frames.nBins
        val fr = DoubleArray(frames.data.size) { frames.data[it].toDouble() }
        var on = DoubleArray(onsetsIn.data.size) { onsetsIn.data[it].toDouble() }
        // constrain_frequency
        p.maximumFrequency?.let { mf ->
            val idx = (hzToMidi(mf) - c.MIDI_OFFSET).roundToInt()
            for (t in 0 until nFrames) for (f in maxOf(0, idx) until nF) { on[t * nF + f] = 0.0; fr[t * nF + f] = 0.0 }
        }
        p.minimumFrequency?.let { mf ->
            val idx = (hzToMidi(mf) - c.MIDI_OFFSET).roundToInt()
            for (t in 0 until nFrames) for (f in 0 until minOf(idx, nF)) { on[t * nF + f] = 0.0; fr[t * nF + f] = 0.0 }
        }
        if (p.inferOnsets) on = inferredOnsets(on, fr, nFrames, nF)

        // picchi locali lungo il tempo (scipy.signal.argrelmax, axis=0: bordi esclusi)
        val onsetList = ArrayList<Int>() // indici flat t*nF+f
        for (t in 1 until nFrames - 1) for (f in 0 until nF) {
            val v = on[t * nF + f]
            if (v > on[(t - 1) * nF + f] && v > on[(t + 1) * nF + f] && v >= p.onsetThreshold) onsetList.add(t * nF + f)
        }
        val remaining = fr.copyOf()
        val notes = ArrayList<FrameNote>()
        val energyTol = p.energyTolerance; val minLen = p.minNoteLenFrames
        fun zeroAround(t: Int, f: Int) {
            remaining[t * nF + f] = 0.0
            if (f < c.MAX_FREQ_IDX) remaining[t * nF + f + 1] = 0.0
            if (f > 0) remaining[t * nF + f - 1] = 0.0
        }
        fun meanFrames(a: Int, b: Int, f: Int): Double { var s = 0.0; for (t in a until b) s += frames[t, f]; return if (b > a) s / (b - a) else 0.0 }

        // ciclo sugli onset all'indietro nel tempo (ordine di np.where invertito)
        for (k in onsetList.indices.reversed()) {
            val flat = onsetList[k]
            val start = flat / nF; val f = flat % nF
            if (start >= nFrames - 1) continue
            var i = start + 1; var miss = 0
            while (i < nFrames - 1 && miss < energyTol) {
                if (remaining[i * nF + f] < p.frameThreshold) miss++ else miss = 0
                i++
            }
            i -= miss
            if (i - start <= minLen) continue
            for (t in start until i) zeroAround(t, f)
            notes.add(FrameNote(start, i, f + c.MIDI_OFFSET, meanFrames(start, i, f)))
        }

        if (p.melodiaTrick) {
            // argmax ripetuto dell'originale realizzato con una coda di priorità a cancellazione pigra:
            // i valori possono solo diventare 0, quindi una voce è valida se coincide col valore attuale.
            val heap = PriorityQueue<IndexedValue>(compareByDescending<IndexedValue> { it.v }.thenBy { it.i })
            for (idx in remaining.indices) if (remaining[idx] > p.frameThreshold) heap.add(IndexedValue(idx, remaining[idx]))
            while (heap.isNotEmpty()) {
                val top = heap.poll()
                if (remaining[top.i] != top.v || top.v <= p.frameThreshold) continue
                val iMid = top.i / nF; val f = top.i % nF
                remaining[top.i] = 0.0
                // passo in avanti
                var i = iMid + 1; var miss = 0
                while (i < nFrames - 1 && miss < energyTol) {
                    if (remaining[i * nF + f] < p.frameThreshold) miss++ else miss = 0
                    zeroAround(i, f); i++
                }
                val iEnd = i - 1 - miss
                // passo all'indietro
                i = iMid - 1; miss = 0
                while (i > 0 && miss < energyTol) {
                    if (remaining[i * nF + f] < p.frameThreshold) miss++ else miss = 0
                    zeroAround(i, f); i--
                }
                val iStart = i + 1 + miss
                if (iEnd - iStart <= minLen) continue
                notes.add(FrameNote(iStart, iEnd, f + c.MIDI_OFFSET, meanFrames(iStart, iEnd, f)))
            }
        }
        return notes
    }

    private data class IndexedValue(val i: Int, val v: Double)

    /** get_infered_onsets con n_diff = 2. */
    private fun inferredOnsets(on: DoubleArray, fr: DoubleArray, nFrames: Int, nF: Int, nDiff: Int = 2): DoubleArray {
        val diff = DoubleArray(fr.size)
        for (t in 0 until nFrames) for (f in 0 until nF) {
            var m = Double.MAX_VALUE
            for (n in 1..nDiff) {
                val prev = if (t - n >= 0) fr[(t - n) * nF + f] else 0.0
                val d = fr[t * nF + f] - prev
                if (d < m) m = d
            }
            diff[t * nF + f] = if (m < 0) 0.0 else m
        }
        for (t in 0 until minOf(nDiff, nFrames)) for (f in 0 until nF) diff[t * nF + f] = 0.0
        val maxOn = on.maxOrNull() ?: 0.0; val maxDiff = diff.maxOrNull() ?: 0.0
        return DoubleArray(on.size) { i ->
            val scaled = if (maxDiff > 0) maxOn * diff[i] / maxDiff else 0.0
            maxOf(on[i], scaled)
        }
    }

    private val gaussian51 = Windows.gaussian(51, 5.0)

    /** get_pitch_bends (n_bins_tolerance = 25), bend in unità di 1/3 di semitono. */
    fun pitchBends(contours: Activations, notes: List<FrameNote>, nBinsTolerance: Int = 25): List<FrameNote> {
        val nC = BasicPitchConstants.N_CONTOUR
        val windowLength = nBinsTolerance * 2 + 1
        return notes.map { n ->
            val freqIdx = (12.0 * BasicPitchConstants.CONTOURS_BINS_PER_SEMITONE * (ln(440.0 * Math.pow(2.0, (n.pitch - 69) / 12.0) / 27.5) / ln(2.0))).roundToInt()
            val fs = maxOf(freqIdx - nBinsTolerance, 0)
            val fe = minOf(nC, freqIdx + nBinsTolerance + 1)
            val gStart = maxOf(0, nBinsTolerance - freqIdx)
            val gEnd = windowLength - maxOf(0, freqIdx - (nC - nBinsTolerance - 1))
            val pbShift = nBinsTolerance - maxOf(0, nBinsTolerance - freqIdx)
            val width = minOf(fe - fs, gEnd - gStart)
            val bends = (n.startFrame until n.endFrame).map { t ->
                var best = 0; var bv = -1.0
                for (j in 0 until width) {
                    val v = contours[t, fs + j] * gaussian51[gStart + j]
                    if (v > bv) { bv = v; best = j }
                }
                best - pbShift
            }
            n.copy(bends = bends)
        }
    }

    /** model_output_to_notes: frame -> secondi, ampiezza -> velocity (round(127*a)) e confidenza. */
    fun toNoteEvents(notes: List<FrameNote>, frameOffset: Int = 0): List<NoteEvent> = notes.map { n ->
        val s = frameTime(n.startFrame + frameOffset); val e = frameTime(n.endFrame + frameOffset)
        NoteEvent(
            pitch = n.pitch, startSec = s, endSec = maxOf(e, s),
            velocity = (127 * n.amplitude).roundToInt().coerceIn(1, 127),
            confidence = n.amplitude.toFloat().coerceIn(0f, 1f),
            pitchBends = n.bends,
        )
    }
}
