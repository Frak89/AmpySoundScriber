pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AmpySoundScriber"

include(":app")

// Moduli indipendenti (Kotlin/JVM puro, testabili senza emulatore)
val ampyModules = listOf(
    "ampy-core",
    "ampy-diagnostics",
    "ampy-audio-engine",
    "ampy-rhythm-lab",
    "ampy-music-brain",
    "ampy-midi-lab",
    "ampy-score-engine",
    "ampy-transcriber",
    "ampy-separator",
    "ampy-instrument-ai",
    "ampy-composer-ai",
    "ampy-model-manager",
    "ampy-learning-lab",
)
ampyModules.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("modules/$name")
}
