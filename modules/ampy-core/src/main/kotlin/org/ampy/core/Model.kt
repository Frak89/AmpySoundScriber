package org.ampy.core

import java.util.concurrent.atomic.AtomicLong

/** Informazioni di versione di ciascun modulo (versionamento indipendente). */
data class ModuleInfo(val name: String, val version: String, val description: String)

object CoreModule {
    val info = ModuleInfo("AmpyCore", "0.1.0", "Modello dati, JSON, progresso, cancellazione, interfacce di inferenza")
}

private val noteIdGen = AtomicLong(1)

/** Riserva un id univoco per una nota (usato da editor e undo/redo). */
fun nextNoteId(): Long = noteIdGen.getAndIncrement()

/** Garantisce che i nuovi id non collidano con quelli caricati da un progetto salvato. */
fun reserveNoteIdsAbove(maxId: Long) {
    while (true) {
        val cur = noteIdGen.get()
        if (cur > maxId) return
        if (noteIdGen.compareAndSet(cur, maxId + 1)) return
    }
}

/**
 * Evento nota. I tempi sono in secondi (verità assoluta); la quantizzazione in battute
 * avviene solo in fase di notazione.
 *
 * @property confidence stima 0..1 della confidenza del riconoscitore (1 = inserita/corretta dall'utente)
 * @property pitchBends sequenza di bend equispaziati in unità di 1/3 di semitono (formato Basic Pitch)
 */
data class NoteEvent(
    val pitch: Int,
    val startSec: Double,
    val endSec: Double,
    val velocity: Int = 80,
    val confidence: Float = 1f,
    val pitchBends: List<Int>? = null,
    val id: Long = nextNoteId(),
    val userEdited: Boolean = false,
) {
    init {
        require(pitch in 0..127) { "pitch fuori range: $pitch" }
        require(endSec >= startSec) { "endSec < startSec ($startSec, $endSec)" }
    }
    val durationSec: Double get() = endSec - startSec
}

enum class InstrumentKind(val gmProgram: Int, val isDrum: Boolean = false, val clef: Clef = Clef.TREBLE, val transposeOctaveDisplay: Int = 0) {
    PIANO(0, clef = Clef.GRAND),
    ELECTRIC_PIANO(4, clef = Clef.GRAND),
    ORGAN(16, clef = Clef.GRAND),
    GUITAR(25, clef = Clef.TREBLE, transposeOctaveDisplay = -1),
    ELECTRIC_GUITAR(29, clef = Clef.TREBLE, transposeOctaveDisplay = -1),
    BASS(33, clef = Clef.BASS, transposeOctaveDisplay = -1),
    VOICE(52),
    STRINGS(48),
    BRASS(61),
    WOODWIND(73),
    SYNTH_LEAD(81),
    SYNTH_PAD(89),
    DRUMS(0, isDrum = true, clef = Clef.PERCUSSION),
    OTHER(0),
}

enum class Clef { TREBLE, BASS, GRAND, PERCUSSION }

/** Accordatura per tablature: altezze MIDI delle corde a vuoto, dalla più grave alla più acuta. */
data class Tuning(val name: String, val openStrings: List<Int>) {
    companion object {
        val GUITAR_STANDARD = Tuning("Chitarra standard (E A D G B E)", listOf(40, 45, 50, 55, 59, 64))
        val GUITAR_DROP_D = Tuning("Chitarra Drop D", listOf(38, 45, 50, 55, 59, 64))
        val GUITAR_EB = Tuning("Chitarra mezzo tono sotto", listOf(39, 44, 49, 54, 58, 63))
        val GUITAR_DADGAD = Tuning("Chitarra DADGAD", listOf(38, 45, 50, 55, 57, 62))
        val BASS_4 = Tuning("Basso 4 corde (E A D G)", listOf(28, 33, 38, 43))
        val BASS_5 = Tuning("Basso 5 corde (B E A D G)", listOf(23, 28, 33, 38, 43))
        val BASS_DROP_D = Tuning("Basso Drop D", listOf(26, 33, 38, 43))
        val ALL = listOf(GUITAR_STANDARD, GUITAR_DROP_D, GUITAR_EB, GUITAR_DADGAD, BASS_4, BASS_5, BASS_DROP_D)
    }
}

data class Track(
    val id: String,
    val name: String,
    val instrument: InstrumentKind = InstrumentKind.OTHER,
    val notes: List<NoteEvent> = emptyList(),
    /** File audio dello stem (relativo alla cartella del progetto) se la traccia deriva da separazione o registrazione. */
    val audioFile: String? = null,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val tuning: Tuning? = null,
    /** Avvertenze di affidabilità (es. separazione non affidabile) mostrate in UI. */
    val warnings: List<String> = emptyList(),
)

data class TempoChange(val timeSec: Double, val bpm: Double)

data class KeySignature(val tonicPitchClass: Int, val isMinor: Boolean) {
    /** Numero di diesis (positivo) o bemolle (negativo) dell'armatura. */
    val fifths: Int get() {
        val majorTonic = if (isMinor) (tonicPitchClass + 3) % 12 else tonicPitchClass
        // posizione nel circolo delle quinte, preferendo armature con <= 6 alterazioni
        val f = (majorTonic * 7) % 12
        return if (f > 6) f - 12 else f
    }
    val name: String get() = PITCH_NAMES[tonicPitchClass] + if (isMinor) " minore" else " maggiore"

    companion object {
        val PITCH_NAMES = listOf("Do", "Do#", "Re", "Mib", "Mi", "Fa", "Fa#", "Sol", "Lab", "La", "Sib", "Si")
        val C_MAJOR = KeySignature(0, false)
    }
}

data class TimeSignature(val numerator: Int = 4, val denominator: Int = 4)

data class Section(val label: String, val startSec: Double, val endSec: Double)

/** Sigla d'accordo riconosciuta o pianificata (root/bass = classe d'altezza 0..11, quality = nome ChordQuality). */
data class ChordMark(val startSec: Double, val endSec: Double, val root: Int, val quality: String, val bass: Int, val confidence: Double = 1.0)

data class Project(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val id: String,
    val title: String,
    val sourceAudio: String? = null,
    val sampleRate: Int = 44100,
    val durationSec: Double = 0.0,
    val tempo: List<TempoChange> = listOf(TempoChange(0.0, 120.0)),
    val key: KeySignature = KeySignature.C_MAJOR,
    val timeSignature: TimeSignature = TimeSignature(),
    val tracks: List<Track> = emptyList(),
    val sections: List<Section> = emptyList(),
    val chords: List<ChordMark> = emptyList(),
    /** Avvisi a livello di progetto (es. affidabilità della separazione). */
    val notes: List<String> = emptyList(),
    val createdAtMs: Long = 0,
    val modifiedAtMs: Long = 0,
) {
    companion object { const val CURRENT_SCHEMA = 1 }
    val initialBpm: Double get() = tempo.firstOrNull()?.bpm ?: 120.0
}
