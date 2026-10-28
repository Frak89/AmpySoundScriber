plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":ampy-core"))
    api(project(":ampy-audio-engine"))
    api(project(":ampy-rhythm-lab"))
    api(project(":ampy-transcriber"))
    api(project(":ampy-midi-lab"))
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("ampy.testdata", rootProject.file("testdata").absolutePath)
}
