package org.ampy.diagnostics

import org.ampy.core.AmpyLog
import org.ampy.core.LogCategory
import org.ampy.core.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticsTest {
    @Test fun ringBufferAndFile() {
        val dir = kotlin.io.path.createTempDirectory("ampydiag").toFile()
        val hub = DiagnosticsHub(dir, capacity = 3)
        AmpyLog.sink = hub
        repeat(5) { AmpyLog.i(LogCategory.GENERAL, "t", "msg $it") }
        AmpyLog.e(LogCategory.MODEL, "ort", "boom", IllegalStateException("x"))
        val recs = hub.records()
        assertEquals(3, recs.size)
        assertEquals(1, hub.records(LogLevel.ERROR).size)
        assertTrue(hub.currentLogFile!!.readText().contains("msg 0"))
        val report = File(dir, "report.txt")
        hub.exportReport(report, mapOf("device" to "test"))
        assertTrue(report.readText().contains("Analisi automatica"))
        dir.deleteRecursively()
    }

    @Test fun analyzerFindsOomAndFiles() {
        val log = """
            2026-10-28 10:00:00.000 INFO  GENERAL/app [main] avvio
            2026-10-28 10:00:01.000 ERROR TRANSCRIPTION/bp [job-1] Trascrizione fallita
            java.lang.OutOfMemoryError: Failed to allocate 400000000 bytes
                at org.ampy.transcriber.BasicPitchTranscriber.transcribe(BasicPitchTranscriber.kt:120)
                at org.ampy.app.jobs.JobManager.run(JobManager.kt:55)
                at java.lang.Thread.run(Thread.java:1012)
        """.trimIndent()
        val f = LogAnalyzer.analyze(log)
        assertTrue(f.primaryError!!.contains("Trascrizione fallita"))
        assertTrue(f.probableCauses.any { it.contains("Memoria") })
        assertEquals(listOf("BasicPitchTranscriber.kt:120 (BasicPitchTranscriber.transcribe)", "JobManager.kt:55 (JobManager.run)"), f.files)
    }

    @Test fun analyzerCleanLog() {
        val f = LogAnalyzer.analyze("2026 INFO  GENERAL/x [m] ok")
        assertEquals(null, f.primaryError)
        assertTrue(f.probableCauses.isEmpty())
    }
}
