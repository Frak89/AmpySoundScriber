package org.ampy.app.data

import org.ampy.audioengine.RawFloatFile
import org.ampy.core.AmpyLog
import org.ampy.core.Json
import org.ampy.core.LogCategory
import org.ampy.core.Project
import org.ampy.core.ProjectCodec
import org.ampy.core.jsonObj
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archivio dei progetti sul disco interno:
 *   projects/<id>/project.json      stato salvato
 *   projects/<id>/autosave.json     salvataggio automatico (recuperabile dopo un crash)
 *   projects/<id>/source.f32 (+ source.json)   audio importato decodificato
 *   projects/<id>/stems/(nome).f32   sorgenti separate
 *   projects/<id>/render/(id).f32    ricostruzioni sonore delle tracce MIDI
 *   projects/<id>/cache/            attivazioni dei modelli, checkpoint, audio 22 kHz
 */
class ProjectRepository(val root: File) {
    init { root.mkdirs() }

    data class Summary(val id: String, val title: String, val modifiedAtMs: Long, val durationSec: Double, val tracks: Int, val hasAutosave: Boolean)

    fun dir(id: String) = File(root, id)
    fun stemsDir(id: String) = File(dir(id), "stems").apply { mkdirs() }
    fun renderDir(id: String) = File(dir(id), "render").apply { mkdirs() }
    fun cacheDir(id: String) = File(dir(id), "cache").apply { mkdirs() }
    fun sourceFile(id: String) = File(dir(id), "source.f32")

    fun newId(): String = "p" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + "-" + (100..999).random()

    fun writeSourceMeta(id: String, sampleRate: Int, channels: Int, originalName: String, mime: String) {
        File(dir(id), "source.json").writeText(jsonObj("sampleRate" to sampleRate, "channels" to channels, "name" to originalName, "mime" to mime).encode())
    }

    fun openSource(id: String): RawFloatFile? {
        val meta = File(dir(id), "source.json"); val f = sourceFile(id)
        if (!meta.exists() || !f.exists()) return null
        val o = Json.parse(meta.readText()) as Json.Obj
        return RawFloatFile(f, o.int("sampleRate")!!, o.int("channels")!!)
    }

    /** Apre un file .f32 di stem/render: il numero di canali e la frequenza sono nel file .json affiancato. */
    fun openPcm(id: String, relative: String): RawFloatFile? {
        val f = File(dir(id), relative); val meta = File(f.path.removeSuffix(".f32") + ".json")
        if (!f.exists()) return null
        val o = if (meta.exists()) Json.parse(meta.readText()) as Json.Obj else null
        val src = openSource(id)
        return RawFloatFile(f, o?.int("sampleRate") ?: src?.sampleRate ?: 44100, o?.int("channels") ?: 1).also { src?.close() }
    }

    fun writePcmMeta(f: File, sampleRate: Int, channels: Int) =
        File(f.path.removeSuffix(".f32") + ".json").writeText(jsonObj("sampleRate" to sampleRate, "channels" to channels).encode())

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun save(p: Project): Project {
        val saved = p.copy(modifiedAtMs = System.currentTimeMillis())
        dir(p.id).mkdirs()
        atomicWrite(File(dir(p.id), "project.json"), ProjectCodec.encode(saved))
        File(dir(p.id), "autosave.json").delete()
        AmpyLog.i(LogCategory.PROJECT, "repo", "Progetto salvato: ${p.id}")
        return saved
    }

    fun autosave(p: Project) {
        runCatching { dir(p.id).mkdirs(); atomicWrite(File(dir(p.id), "autosave.json"), ProjectCodec.encode(p.copy(modifiedAtMs = System.currentTimeMillis()))) }
            .onFailure { AmpyLog.w(LogCategory.PROJECT, "repo", "Salvataggio automatico fallito: ${it.message}") }
    }

    /** Carica il progetto; se esiste un salvataggio automatico più recente lo restituisce come [recovered]. */
    data class Loaded(val project: Project, val recovered: Project?)

    fun load(id: String): Loaded {
        val main = File(dir(id), "project.json"); val auto = File(dir(id), "autosave.json")
        val p = if (main.exists()) ProjectCodec.decode(main.readText()) else null
        val a = if (auto.exists()) runCatching { ProjectCodec.decode(auto.readText()) }.getOrNull() else null
        val base = p ?: a ?: throw IllegalStateException("Progetto $id non trovato")
        // si propone il recupero solo se l'autosalvataggio è più recente E contiene modifiche reali
        val differs = a != null && p != null && a.copy(modifiedAtMs = 0) != p.copy(modifiedAtMs = 0)
        return Loaded(base, if (differs && a!!.modifiedAtMs > p!!.modifiedAtMs) a else null)
    }

    fun list(): List<Summary> = root.listFiles { f -> f.isDirectory }?.mapNotNull { d ->
        val main = File(d, "project.json"); val auto = File(d, "autosave.json")
        val f = if (main.exists()) main else if (auto.exists()) auto else return@mapNotNull null
        runCatching {
            val o = Json.parse(f.readText()) as Json.Obj
            Summary(d.name, o.str("title") ?: d.name, o.long("modifiedAtMs") ?: f.lastModified(), o.num("durationSec") ?: 0.0, o.arr("tracks")?.items?.size ?: 0, auto.exists())
        }.getOrNull()
    }?.sortedByDescending { it.modifiedAtMs } ?: emptyList()

    fun delete(id: String) { dir(id).deleteRecursively() }

    fun diskUsageBytes(id: String): Long = dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearCache(id: String) { File(dir(id), "cache").deleteRecursively() }
}
