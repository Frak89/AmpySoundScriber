package org.ampy.app.data

import android.content.Context
import org.ampy.core.InferenceDevice
import org.ampy.transcriber.TranscriptionQuality

enum class ThemeMode(val label: String) { LIGHT("Chiaro"), DARK("Scuro"), CYBERPUNK("Cyberpunk") }

/** Preferenze persistenti (SharedPreferences). */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("ampy_settings", Context.MODE_PRIVATE)

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString("theme", ThemeMode.CYBERPUNK.name)!!) }.getOrDefault(ThemeMode.CYBERPUNK)
        set(v) = sp.edit().putString("theme", v.name).apply()

    var device: InferenceDevice
        get() = runCatching { InferenceDevice.valueOf(sp.getString("device", InferenceDevice.XNNPACK.name)!!) }.getOrDefault(InferenceDevice.CPU)
        set(v) = sp.edit().putString("device", v.name).apply()

    var quality: TranscriptionQuality
        get() = runCatching { TranscriptionQuality.valueOf(sp.getString("quality", TranscriptionQuality.QUALITY.name)!!) }.getOrDefault(TranscriptionQuality.QUALITY)
        set(v) = sp.edit().putString("quality", v.name).apply()

    /** Durata dei segmenti di separazione in secondi (minore = meno memoria). */
    var segmentSec: Float
        get() = sp.getFloat("segmentSec", 20f)
        set(v) = sp.edit().putFloat("segmentSec", v).apply()

    var batchSize: Int
        get() = sp.getInt("batch", 4)
        set(v) = sp.edit().putInt("batch", v).apply()

    var autosaveSec: Int
        get() = sp.getInt("autosaveSec", 20)
        set(v) = sp.edit().putInt("autosaveSec", v).apply()

    var verboseLog: Boolean
        get() = sp.getBoolean("verbose", true)
        set(v) = sp.edit().putBoolean("verbose", v).apply()
}
