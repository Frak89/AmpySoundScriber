plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Versioni indipendenti dei moduli (sezione 1 del capitolato).
 * Ogni modulo legge la propria versione da qui; aggiornare il CHANGELOG ad ogni bump.
 */
val ampyModuleVersions = mapOf(
    "ampy-core" to "0.1.0",
    "ampy-diagnostics" to "0.1.0",
    "ampy-audio-engine" to "0.1.0",
    "ampy-rhythm-lab" to "0.1.0",
    "ampy-music-brain" to "0.1.0",
    "ampy-midi-lab" to "0.1.0",
    "ampy-score-engine" to "0.1.0",
    "ampy-transcriber" to "0.1.0",
    "ampy-separator" to "0.1.0",
    "ampy-instrument-ai" to "0.1.0",
    "ampy-composer-ai" to "0.1.0",
    "ampy-model-manager" to "0.1.0",
    "ampy-learning-lab" to "0.1.0",
)

subprojects {
    group = "org.ampy"
    ampyModuleVersions[name]?.let { version = it }
}
