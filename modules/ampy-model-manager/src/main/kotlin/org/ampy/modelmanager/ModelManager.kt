package org.ampy.modelmanager

import org.ampy.core.AmpyLog
import org.ampy.core.InferenceDevice
import org.ampy.core.Json
import org.ampy.core.LogCategory
import org.ampy.core.ModuleInfo
import org.ampy.core.jsonObj
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object ModelManagerModule {
    val info = ModuleInfo("AmpyModelManager", "0.1.0", "Registro, installazione, verifica e selezione dei modelli IA")
}

enum class ModelTask { TRANSCRIPTION_BASIC_PITCH, SEPARATION_SPECTRAL_MASK, SEPARATION_WAVEFORM }

enum class ModelFormat { ONNX, ORT, TFLITE }

/**
 * Manifest di un modello. [mobileVerified] è vero solo se il modello è stato realmente eseguito e
 * misurato su dispositivo Android: la UI lo mostra per non dare per scontata la compatibilità mobile.
 */
data class ModelManifest(
    val id: String,
    val name: String,
    val task: ModelTask,
    val format: ModelFormat,
    val file: String,
    val sha256: String?,
    val version: String,
    val license: String,
    val source: String,
    val sampleRate: Int,
    val stems: List<String> = emptyList(),
    val inputName: String? = null,
    val outputNames: List<String> = emptyList(),
    val params: Map<String, Double> = emptyMap(),
    val devices: List<InferenceDevice> = listOf(InferenceDevice.CPU),
    val mobileVerified: Boolean = false,
    val notes: String = "",
) {
    fun toJson(): String = jsonObj(
        "id" to id, "name" to name, "task" to task.name, "format" to format.name, "file" to file, "sha256" to sha256,
        "version" to version, "license" to license, "source" to source, "sampleRate" to sampleRate, "stems" to stems,
        "inputName" to inputName, "outputNames" to outputNames, "params" to params, "devices" to devices.map { it.name },
        "mobileVerified" to mobileVerified, "notes" to notes,
    ).encode()

    companion object {
        fun parse(text: String): ModelManifest {
            val o = Json.parse(text) as Json.Obj
            fun strs(k: String) = o.arr(k)?.items?.map { (it as Json.Str).value } ?: emptyList()
            return ModelManifest(
                id = o.str("id") ?: error("manifest senza id"), name = o.str("name") ?: o.str("id")!!,
                task = ModelTask.valueOf(o.str("task")!!), format = ModelFormat.valueOf(o.str("format") ?: "ONNX"),
                file = o.str("file")!!, sha256 = o.str("sha256"), version = o.str("version") ?: "?", license = o.str("license") ?: "sconosciuta",
                source = o.str("source") ?: "", sampleRate = o.int("sampleRate") ?: 44100, stems = strs("stems"),
                inputName = o.str("inputName"), outputNames = strs("outputNames"),
                params = o.obj("params")?.map?.mapValues { (it.value as Json.Num).value } ?: emptyMap(),
                devices = strs("devices").mapNotNull { runCatching { InferenceDevice.valueOf(it) }.getOrNull() }.ifEmpty { listOf(InferenceDevice.CPU) },
                mobileVerified = o.bool("mobileVerified") ?: false, notes = o.str("notes") ?: "",
            )
        }
    }
}

data class InstalledModel(val manifest: ModelManifest, val dir: File) {
    val modelFile: File get() = File(dir, manifest.file)
}

class ModelVerificationException(msg: String) : Exception(msg)

/** Registro su disco: <root>/<id>/manifest.json + file del modello. Le preferenze attive sono in <root>/active.json. */
class ModelRegistry(val root: File) {
    init { root.mkdirs() }
    private val activeFile = File(root, "active.json")

    fun list(): List<InstalledModel> = root.listFiles { f -> f.isDirectory }?.mapNotNull { d ->
        val mf = File(d, "manifest.json")
        if (!mf.exists()) null else runCatching { InstalledModel(ModelManifest.parse(mf.readText()), d) }
            .onFailure { AmpyLog.w(LogCategory.MODEL, "registry", "manifest non valido in ${d.name}: ${it.message}") }.getOrNull()
    }?.sortedBy { it.manifest.name } ?: emptyList()

    fun get(id: String): InstalledModel? = list().firstOrNull { it.manifest.id == id }

    /** Installa copiando il file e verificando l'impronta SHA-256 dichiarata nel manifest. */
    fun install(manifest: ModelManifest, data: InputStream): InstalledModel {
        require(manifest.id.matches(Regex("[A-Za-z0-9._-]+"))) { "id modello non valido" }
        val dir = File(root, manifest.id).apply { mkdirs() }
        val tmp = File(dir, manifest.file + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = data.read(buf); if (n <= 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (manifest.sha256 != null && !manifest.sha256.equals(sha, ignoreCase = true)) {
            tmp.delete()
            throw ModelVerificationException("Impronta SHA-256 non corrispondente: atteso ${manifest.sha256}, ottenuto $sha")
        }
        val dest = File(dir, manifest.file)
        dest.delete(); tmp.renameTo(dest)
        val stored = manifest.copy(sha256 = sha)
        File(dir, "manifest.json").writeText(stored.toJson())
        AmpyLog.i(LogCategory.MODEL, "registry", "Installato ${manifest.id} (${dest.length() / 1024} KB, sha256 $sha)")
        return InstalledModel(stored, dir)
    }

    fun verify(model: InstalledModel): Boolean {
        val expected = model.manifest.sha256 ?: return model.modelFile.exists()
        return model.modelFile.exists() && sha256(model.modelFile).equals(expected, ignoreCase = true)
    }

    fun remove(id: String) { File(root, id).deleteRecursively(); setActive(activeMap().filterValues { it != id }) }

    private fun activeMap(): Map<String, String> = if (!activeFile.exists()) emptyMap() else
        (Json.parse(activeFile.readText()) as Json.Obj).map.mapValues { (it.value as Json.Str).value }
    private fun setActive(m: Map<String, String>) = activeFile.writeText(jsonObj(*m.map { it.key to it.value }.toTypedArray()).encode())

    fun setActive(task: ModelTask, id: String) = setActive(activeMap() + (task.name to id))

    fun active(task: ModelTask): InstalledModel? {
        val id = activeMap()[task.name]
        return (id?.let { get(it) }) ?: list().firstOrNull { it.manifest.task == task }
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n <= 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Catalogo dei progetti open source valutati (sezioni 3-4 del capitolato). È informativo: indica licenza,
 * stato e cosa serve per l'uso su Android. Nessuna voce viene dichiarata funzionante se non verificata.
 */
object ModelCatalog {
    data class Entry(val name: String, val task: String, val license: String, val status: String, val android: String)

    val entries = listOf(
        Entry("Spotify Basic Pitch (ICASSP 2022)", "Trascrizione polifonica", "Apache-2.0", "Integrato (ONNX incluso nell'app)", "Eseguibile con ONNX Runtime Android; post-elaborazione portata in Kotlin e verificata contro l'originale"),
        Entry("Demucs / Hybrid Transformer Demucs", "Separazione 4/6 sorgenti", "MIT", "Supportato tramite modello ONNX esterno (WaveformModelSeparator)", "Richiede esportazione ONNX e test su dispositivo; modello ~80-300 MB, lento su CPU mobile"),
        Entry("Open-Unmix (UMX/UMXL)", "Separazione 4 sorgenti", "MIT (pesi UMX: MIT; UMXL: CC BY-NC-SA)", "Supportato tramite modello ONNX esterno (SpectralMaskModelSeparator)", "Esportazione via tools/export/export_openunmix_onnx.py; non verificato su dispositivo"),
        Entry("Spleeter (Deezer)", "Separazione 2/4/5 sorgenti", "MIT", "Valutato: modelli TensorFlow 1.x", "Conversione a ONNX/TFLite non banale; non integrato"),
        Entry("MDX-Net / UVR", "Separazione voce/strumentale", "MIT (codice); pesi variabili", "Valutato", "Licenze dei pesi da verificare caso per caso; non integrato"),
        Entry("MT3 (Google Magenta)", "Trascrizione multi-strumento", "Apache-2.0", "Valutato", "Modello T5 in JAX (~ centinaia di MB): non praticabile su smartphone senza distillazione"),
        Entry("Omnizart", "Trascrizione (piano, batteria, accordi, voce)", "MIT", "Valutato", "Basato su TensorFlow 2; conversione possibile ma non verificata"),
        Entry("piano_transcription_inference (ByteDance)", "Trascrizione pianoforte ad alta risoluzione", "Apache-2.0 (codice)", "Valutato", "Modello PyTorch ~170 MB; candidato per modalità desktop"),
        Entry("Onsets and Frames (Magenta)", "Trascrizione pianoforte/batteria", "Apache-2.0", "Valutato", "TensorFlow 1.x; esiste versione TFLite per pianoforte"),
        Entry("Sheet Sage", "Melodia + armonia da audio", "MIT (codice)", "Valutato", "Dipende da Jukebox (modello molto grande): solo desktop/server"),
        Entry("AudiTranscribe", "Applicazione di trascrizione assistita", "GPL-3.0", "Valutato come riferimento UX", "Applicazione desktop JavaFX; licenza GPL incompatibile con l'inclusione diretta di codice"),
        Entry("Verovio", "Rendering notazione (MusicXML/MEI -> SVG)", "LGPL-3.0", "Integrato (JS/WASM in WebView, file separato)", "Funziona offline; libreria non modificata, sostituibile dall'utente come richiesto dalla LGPL"),
        Entry("MuseScore", "Editor di partiture", "GPL-3.0", "Interoperabilità tramite MusicXML/MIDI", "Non incluso; i file esportati si aprono in MuseScore"),
    )
}
