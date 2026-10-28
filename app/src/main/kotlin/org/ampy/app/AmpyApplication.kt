package org.ampy.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.ampy.app.data.ProjectRepository
import org.ampy.app.data.Settings
import org.ampy.app.jobs.JobManager
import org.ampy.app.pipeline.AmpyPipeline
import org.ampy.app.platform.AndroidAudioDecoder
import org.ampy.app.platform.AudioPlayerEngine
import org.ampy.app.platform.AudioRecorderEngine
import org.ampy.app.platform.DeviceInfo
import org.ampy.app.platform.OrtTensorRunner
import org.ampy.core.AmpyLog
import org.ampy.core.LogCategory
import org.ampy.core.LogLevel
import org.ampy.diagnostics.DiagnosticsHub
import org.ampy.learninglab.LearningStore
import org.ampy.modelmanager.ModelManifest
import org.ampy.modelmanager.ModelRegistry
import org.ampy.modelmanager.ModelTask
import java.io.File

/** Contenitore delle dipendenze dell'app (creato una volta all'avvio del processo). */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val diagnostics = DiagnosticsHub(File(app.filesDir, "logs"), memoryProbe = DeviceInfo.memoryProbe(app))
    val repo = ProjectRepository(File(app.filesDir, "projects"))
    val models = ModelRegistry(File(app.filesDir, "models"))
    val learning = LearningStore(File(app.filesDir, "learning"))
    val jobs = JobManager(app, scope)
    val decoder = AndroidAudioDecoder(app)
    val recorder = AudioRecorderEngine(app)
    val player = AudioPlayerEngine(app)
    val pipeline = AmpyPipeline(repo, models, learning, OrtTensorRunner.factory, { settings.device },
        segmentSec = { settings.segmentSec.toDouble() }, batchSize = { settings.batchSize })

    /** Installa (una sola volta) il modello Basic Pitch incluso negli asset, verificandone lo SHA-256. */
    fun ensureBundledModels() {
        if (models.list().any { it.manifest.task == ModelTask.TRANSCRIPTION_BASIC_PITCH }) return
        runCatching {
            val manifest = ModelManifest.parse(app.assets.open("models/basic-pitch/manifest.json").bufferedReader().readText())
            app.assets.open("models/basic-pitch/${manifest.file}").use { models.install(manifest, it) }
        }.onFailure { AmpyLog.e(LogCategory.MODEL, "bundle", "Installazione del modello incluso fallita: ${it.message}", it) }
    }
}

class AmpyApplication : Application() {
    lateinit var container: AppContainer; private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AmpyLog.sink = container.diagnostics
        AmpyLog.minLevel = if (container.settings.verboseLog) LogLevel.DEBUG else LogLevel.INFO
        container.diagnostics.installCrashHandler()
        AmpyLog.i(LogCategory.GENERAL, "app", "Avvio: ${DeviceInfo.summary(this)}")
        container.jobs.launch("Preparazione modelli") { container.ensureBundledModels() }
    }
}
