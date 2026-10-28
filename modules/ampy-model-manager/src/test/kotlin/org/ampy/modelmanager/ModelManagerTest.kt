package org.ampy.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelManagerTest {
    @Test fun installVerifyActivateRemove() {
        val root = kotlin.io.path.createTempDirectory("models").toFile()
        val reg = ModelRegistry(root)
        val data = ByteArray(1000) { it.toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val m = ModelManifest("bp", "Basic Pitch", ModelTask.TRANSCRIPTION_BASIC_PITCH, ModelFormat.ONNX, "nmp.onnx", sha, "0.4.0", "Apache-2.0", "https://github.com/spotify/basic-pitch", 22050)
        val inst = reg.install(m, data.inputStream())
        assertTrue(reg.verify(inst))
        assertEquals("bp", reg.active(ModelTask.TRANSCRIPTION_BASIC_PITCH)!!.manifest.id)
        // corruzione rilevata
        File(inst.dir, "nmp.onnx").appendBytes(byteArrayOf(1))
        assertFalse(reg.verify(reg.get("bp")!!))
        reg.remove("bp")
        assertEquals(0, reg.list().size)
        root.deleteRecursively()
    }

    @Test(expected = ModelVerificationException::class)
    fun rejectsWrongChecksum() {
        val reg = ModelRegistry(kotlin.io.path.createTempDirectory("models").toFile())
        reg.install(ModelManifest("x", "x", ModelTask.SEPARATION_WAVEFORM, ModelFormat.ONNX, "m.onnx", "00", "1", "MIT", "", 44100), ByteArray(10).inputStream())
    }

    @Test fun manifestRoundTrip() {
        val m = ModelManifest("htdemucs", "HTDemucs", ModelTask.SEPARATION_WAVEFORM, ModelFormat.ONNX, "htdemucs.onnx", null, "4", "MIT", "src", 44100,
            stems = listOf("DRUMS", "BASS", "OTHER", "VOCALS"), inputName = "mix", params = mapOf("segment" to 343980.0))
        assertEquals(m, ModelManifest.parse(m.toJson()))
    }

    @Test fun bundledManifestIsValid() {
        val f = File(System.getProperty("ampy.testdata", "testdata"), "../app/src/main/assets/models/basic-pitch/manifest.json")
        if (!f.exists()) return
        val m = ModelManifest.parse(f.readText())
        assertEquals(ModelTask.TRANSCRIPTION_BASIC_PITCH, m.task)
        val model = File(f.parentFile, m.file)
        assertEquals(m.sha256, ModelRegistry.sha256(model))
    }
}
