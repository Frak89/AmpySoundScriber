package org.ampy.learninglab

import org.ampy.core.AmpyLog
import org.ampy.core.Json
import org.ampy.core.JobContext
import org.ampy.core.LogCategory
import org.ampy.core.NoteEvent
import org.ampy.core.Track
import org.ampy.core.InstrumentKind
import org.ampy.core.TempoChange
import org.ampy.core.KeySignature
import org.ampy.core.TimeSignature
import org.ampy.core.jsonObj
import org.ampy.midilab.MidiWriter
import org.ampy.transcriber.Activations
import org.ampy.transcriber.BasicPitchNotes
import org.ampy.transcriber.BasicPitchParams
import java.io.File

/**
 * Archivio dell'apprendimento. Dataset, feedback, checkpoint e configurazioni sono in cartelle separate,
 * come richiesto: <root>/feedback, <root>/datasets, <root>/checkpoints, <root>/config.
 */
class LearningStore(val root: File) {
    val feedbackDir = File(root, "feedback").apply { mkdirs() }
    val datasetDir = File(root, "datasets").apply { mkdirs() }
    val checkpointDir = File(root, "checkpoints").apply { mkdirs() }
    val configDir = File(root, "config").apply { mkdirs() }

    data class Correction(val id: String, val projectId: String, val trackId: String, val profile: String, val audioRef: String?,
                          val startSec: Double, val endSec: Double, val original: List<NoteEvent>, val corrected: List<NoteEvent>, val timeMs: Long)

    private fun notesJson(l: List<NoteEvent>) = l.map { jsonObj("p" to it.pitch, "s" to it.startSec, "e" to it.endSec, "v" to it.velocity, "c" to it.confidence.toDouble()) }
    private fun notesFrom(a: Json.Arr?) = a?.items?.map { val o = it as Json.Obj; NoteEvent(o.int("p")!!, o.num("s")!!, o.num("e")!!, o.int("v") ?: 80, (o.num("c") ?: 1.0).toFloat()) } ?: emptyList()

    /** Registra una correzione dell'utente (note prima/dopo nell'intervallo modificato). */
    fun saveCorrection(projectId: String, trackId: String, profile: String, audioRef: String?, original: List<NoteEvent>, corrected: List<NoteEvent>): Correction {
        val t0 = (original + corrected).minOfOrNull { it.startSec } ?: 0.0
        val t1 = (original + corrected).maxOfOrNull { it.endSec } ?: 0.0
        val c = Correction("fb-${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}", projectId, trackId, profile, audioRef, t0, t1, original, corrected, System.currentTimeMillis())
        val report = TranscriptionMetrics.evaluate(corrected, original)
        File(feedbackDir, "${c.id}.json").writeText(jsonObj(
            "id" to c.id, "projectId" to projectId, "trackId" to trackId, "profile" to profile, "audioRef" to audioRef,
            "startSec" to t0, "endSec" to t1, "timeMs" to c.timeMs,
            "noteF1BeforeCorrection" to report.noteOnset.f1,
            "original" to notesJson(original), "corrected" to notesJson(corrected),
        ).encode())
        AmpyLog.i(LogCategory.TRANSCRIPTION, "learning", "Correzione salvata ${c.id}: F1 della trascrizione rispetto alla correzione ${"%.3f".format(report.noteOnset.f1)}")
        return c
    }

    fun corrections(): List<Correction> = feedbackDir.listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
        runCatching {
            val o = Json.parse(f.readText()) as Json.Obj
            Correction(o.str("id")!!, o.str("projectId")!!, o.str("trackId")!!, o.str("profile") ?: "GENERIC", o.str("audioRef"),
                o.num("startSec") ?: 0.0, o.num("endSec") ?: 0.0, notesFrom(o.arr("original")), notesFrom(o.arr("corrected")), o.long("timeMs") ?: 0)
        }.getOrNull()
    }?.sortedBy { it.timeMs } ?: emptyList()

    /** Statistiche degli errori di riconoscimento aggregate su tutte le correzioni. */
    data class ErrorStats(val corrections: Int, val meanF1: Double, val falsePositives: Int, val misses: Int, val octaveErrors: Int, val semitoneErrors: Int, val lowRegisterMisses: Int, val shortNoteMisses: Int)

    fun errorStats(): ErrorStats {
        val cs = corrections()
        var fp = 0; var fn = 0; var oct = 0; var semi = 0; var low = 0; var short = 0; var f1 = 0.0
        for (c in cs) {
            val m = TranscriptionMetrics.match(c.corrected, c.original)
            val matchedRef = m.map { it.first }.toSet(); val matchedEst = m.map { it.second }.toSet()
            fp += c.original.size - matchedEst.size
            val missed = c.corrected.indices.filter { it !in matchedRef }
            fn += missed.size
            for (r in missed) {
                val rn = c.corrected[r]
                if (rn.pitch < 48) low++
                if (rn.durationSec < 0.12) short++
                val near = c.original.firstOrNull { kotlin.math.abs(it.startSec - rn.startSec) <= 0.05 }
                if (near != null) { val d = kotlin.math.abs(near.pitch - rn.pitch); if (d % 12 == 0 && d > 0) oct++ else if (d in 1..2) semi++ }
            }
            f1 += TranscriptionMetrics.evaluate(c.corrected, c.original).noteOnset.f1
        }
        return ErrorStats(cs.size, if (cs.isEmpty()) 0.0 else f1 / cs.size, fp, fn, oct, semi, low, short)
    }

    /**
     * Esporta un dataset (MIDI di riferimento corretti + indice CSV degli audio) per l'addestramento
     * o fine-tuning su desktop con gli script ufficiali di Basic Pitch. Il fine-tuning su smartphone
     * non è eseguito: richiede TensorFlow e risorse non disponibili sul dispositivo.
     */
    fun exportDataset(name: String): File {
        val dir = File(datasetDir, name).apply { mkdirs() }
        val idx = StringBuilder("id,audio,start_sec,end_sec,midi\n")
        for (c in corrections()) {
            val midi = File(dir, "${c.id}.mid")
            val shifted = c.corrected.map { it.copy(startSec = it.startSec - c.startSec, endSec = it.endSec - c.startSec) }
            midi.writeBytes(MidiWriter.write(listOf(Track("t", "ref", InstrumentKind.PIANO, shifted)), listOf(TempoChange(0.0, 120.0)), KeySignature.C_MAJOR, TimeSignature()))
            idx.append("${c.id},${c.audioRef ?: ""},${c.startSec},${c.endSec},${midi.name}\n")
        }
        File(dir, "index.csv").writeText(idx.toString())
        File(dir, "LEGGIMI.txt").writeText("Dataset generato da AmpySoundScriber dalle correzioni dell'utente.\nOgni riga di index.csv associa un intervallo audio a un MIDI corretto.\nVerificare i diritti sugli audio prima di condividere il dataset.\n")
        return dir
    }
}

/**
 * Adattamento verificabile dei parametri di decodifica (soglie onset/frame, durata minima) sulle correzioni
 * dell'utente: ricerca a griglia che massimizza la Note F1 misurata. NON modifica i pesi del modello.
 */
class ParameterTuner(val store: LearningStore) {
    data class Case(val note: Activations, val onset: Activations, val frameOffset: Int, val reference: List<NoteEvent>)
    data class Result(val profile: String, val before: Double, val after: Double, val params: BasicPitchParams, val evaluated: Int)

    fun tune(profile: String, cases: List<Case>, base: BasicPitchParams, ctx: JobContext = JobContext(),
             onsetGrid: List<Double> = listOf(0.3, 0.4, 0.5, 0.6, 0.7), frameGrid: List<Double> = listOf(0.2, 0.25, 0.3, 0.35, 0.4), minLenMsGrid: List<Double> = listOf(58.0, 93.0, 127.7)): Result {
        fun score(p: BasicPitchParams): Double {
            var f = 0.0
            for (c in cases) {
                val notes = BasicPitchNotes.toNoteEvents(BasicPitchNotes.outputToNotesPolyphonic(c.note, c.onset, p.copy(includePitchBends = false)), c.frameOffset)
                f += TranscriptionMetrics.evaluate(c.reference, notes).noteOnset.f1
            }
            return f / cases.size.coerceAtLeast(1)
        }
        val before = score(base)
        var best = base; var bestScore = before; var n = 0
        val total = onsetGrid.size * frameGrid.size * minLenMsGrid.size
        for (o in onsetGrid) for (fr in frameGrid) for (ml in minLenMsGrid) {
            ctx.cancel.throwIfCancelled()
            val p = base.copy(onsetThreshold = o, frameThreshold = fr, minimumNoteLengthMs = ml)
            val s = score(p); n++
            if (s > bestScore + 1e-9) { bestScore = s; best = p }
            ctx.progress.report(n.toDouble() / total, "Adattamento parametri: $n/$total")
        }
        val res = Result(profile, before, bestScore, best, n)
        File(store.configDir, "transcription_$profile.json").writeText(jsonObj(
            "profile" to profile, "onsetThreshold" to best.onsetThreshold, "frameThreshold" to best.frameThreshold,
            "minimumNoteLengthMs" to best.minimumNoteLengthMs, "f1Before" to before, "f1After" to bestScore, "cases" to cases.size,
            "timeMs" to System.currentTimeMillis(),
        ).encode())
        AmpyLog.i(LogCategory.TRANSCRIPTION, "tuner", "Profilo $profile: F1 ${"%.3f".format(before)} -> ${"%.3f".format(bestScore)} su ${cases.size} casi")
        return res
    }

    /** Applica il profilo adattato (se esiste) ai parametri di base. */
    fun load(profile: String, base: BasicPitchParams): BasicPitchParams {
        val f = File(store.configDir, "transcription_$profile.json")
        if (!f.exists()) return base
        val o = Json.parse(f.readText()) as Json.Obj
        return base.copy(onsetThreshold = o.num("onsetThreshold") ?: base.onsetThreshold, frameThreshold = o.num("frameThreshold") ?: base.frameThreshold,
            minimumNoteLengthMs = o.num("minimumNoteLengthMs") ?: base.minimumNoteLengthMs)
    }
}
