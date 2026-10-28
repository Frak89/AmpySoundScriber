// AGP 9.x: il supporto Kotlin è integrato (non applicare org.jetbrains.kotlin.android).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.ampy.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.ampy.soundscriber"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // Pixel 7 Pro = arm64; x86_64 per l'emulatore
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Il modello e il toolkit Verovio non vanno ricompressi (letti in streaming dagli asset)
    androidResources { noCompress += listOf("onnx", "ort") }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(project(":ampy-core"))
    implementation(project(":ampy-diagnostics"))
    implementation(project(":ampy-audio-engine"))
    implementation(project(":ampy-rhythm-lab"))
    implementation(project(":ampy-music-brain"))
    implementation(project(":ampy-midi-lab"))
    implementation(project(":ampy-score-engine"))
    implementation(project(":ampy-transcriber"))
    implementation(project(":ampy-separator"))
    implementation(project(":ampy-instrument-ai"))
    implementation(project(":ampy-composer-ai"))
    implementation(project(":ampy-model-manager"))
    implementation(project(":ampy-learning-lab"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
