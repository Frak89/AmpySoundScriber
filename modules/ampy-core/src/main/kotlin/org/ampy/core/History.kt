package org.ampy.core

/**
 * Cronologia annulla/ripristina basata su snapshot immutabili del progetto.
 * Le data class immutabili rendono gli snapshot economici (condivisione strutturale delle liste non modificate).
 */
class History<T>(initial: T, private val maxDepth: Int = 200) {
    private val undo = ArrayDeque<Pair<String, T>>()
    private val redo = ArrayDeque<Pair<String, T>>()
    var current: T = initial
        private set

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val undoLabel: String? get() = undo.lastOrNull()?.first
    val redoLabel: String? get() = redo.lastOrNull()?.first

    /** Applica una modifica etichettata (es. "Sposta nota"). */
    fun apply(label: String, transform: (T) -> T): T {
        val next = transform(current)
        if (next == current) return current
        undo.addLast(label to current)
        if (undo.size > maxDepth) undo.removeFirst()
        redo.clear()
        current = next
        return current
    }

    fun undo(): T {
        val (label, prev) = undo.removeLastOrNull() ?: return current
        redo.addLast(label to current)
        current = prev
        return current
    }

    fun redo(): T {
        val (label, next) = redo.removeLastOrNull() ?: return current
        undo.addLast(label to current)
        current = next
        return current
    }

    /** Sostituisce lo stato senza registrare (es. caricamento progetto). */
    fun reset(value: T) { undo.clear(); redo.clear(); current = value }
}

/** Operazioni di editing sulle note di una traccia, pure e testabili. */
object NoteEdits {
    fun Project.updateTrack(trackId: String, f: (Track) -> Track): Project =
        copy(tracks = tracks.map { if (it.id == trackId) f(it) else it })

    fun Project.moveNote(trackId: String, noteId: Long, deltaSec: Double, deltaPitch: Int): Project = updateTrack(trackId) { t ->
        t.copy(notes = t.notes.map { n ->
            if (n.id != noteId) n else {
                val s = (n.startSec + deltaSec).coerceAtLeast(0.0)
                n.copy(startSec = s, endSec = s + n.durationSec, pitch = (n.pitch + deltaPitch).coerceIn(0, 127), confidence = 1f, userEdited = true)
            }
        })
    }

    fun Project.resizeNote(trackId: String, noteId: Long, newEndSec: Double): Project = updateTrack(trackId) { t ->
        t.copy(notes = t.notes.map { n -> if (n.id != noteId) n else n.copy(endSec = newEndSec.coerceAtLeast(n.startSec + 0.01), confidence = 1f, userEdited = true) })
    }

    fun Project.addNote(trackId: String, note: NoteEvent): Project = updateTrack(trackId) { t ->
        t.copy(notes = (t.notes + note.copy(confidence = 1f, userEdited = true)).sortedBy { it.startSec })
    }

    fun Project.deleteNote(trackId: String, noteId: Long): Project = updateTrack(trackId) { t -> t.copy(notes = t.notes.filterNot { it.id == noteId }) }

    fun Project.setVelocity(trackId: String, noteId: Long, velocity: Int): Project = updateTrack(trackId) { t ->
        t.copy(notes = t.notes.map { if (it.id == noteId) it.copy(velocity = velocity.coerceIn(1, 127), userEdited = true) else it })
    }
}
