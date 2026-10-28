package org.ampy.app.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import org.ampy.core.AmpyLog
import org.ampy.core.FloatTensor
import org.ampy.core.InferenceDevice
import org.ampy.core.LogCategory
import org.ampy.core.TensorRunner
import org.ampy.core.TensorRunnerFactory
import java.nio.FloatBuffer

/**
 * Implementazione di [TensorRunner] con ONNX Runtime Android.
 * Backend: CPU (sempre), XNNPACK (CPU ottimizzata ARM), NNAPI (deprecato da Android 15, mantenuto per i
 * dispositivi in cui funziona). In caso di errore del provider richiesto si ricade su CPU e lo si registra.
 */
class OrtTensorRunner(modelPath: String, preferred: InferenceDevice) : TensorRunner {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    override val activeDevice: InferenceDevice

    init {
        var dev = preferred
        val s = try {
            env.createSession(modelPath, options(preferred))
        } catch (e: OrtException) {
            AmpyLog.w(LogCategory.MODEL, "ort", "Provider $preferred non disponibile (${e.message}); uso CPU", e)
            dev = InferenceDevice.CPU
            env.createSession(modelPath, options(InferenceDevice.CPU))
        }
        session = s
        activeDevice = dev
        AmpyLog.i(LogCategory.MODEL, "ort", "Sessione ONNX aperta: $modelPath, dispositivo $dev, input ${session.inputNames}, output ${session.outputNames}")
    }

    private fun options(d: InferenceDevice) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6))
        when (d) {
            InferenceDevice.NNAPI -> addNnapi()
            InferenceDevice.XNNPACK -> addXnnpack(emptyMap())
            else -> {}
        }
    }

    override val inputNames: List<String> get() = session.inputNames.toList()
    override val outputNames: List<String> get() = session.outputNames.toList()

    override fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor> {
        val tensors = inputs.mapValues { (_, t) -> OnnxTensor.createTensor(env, FloatBuffer.wrap(t.data), LongArray(t.shape.size) { t.shape[it].toLong() }) }
        try {
            session.run(tensors).use { result ->
                return outputNames.associateWith { name ->
                    val v = result.get(name).orElseThrow { IllegalStateException("uscita $name assente") } as OnnxTensor
                    val fb = v.floatBuffer
                    val arr = FloatArray(fb.remaining()).also { fb.get(it) }
                    FloatTensor(v.info.shape.map { it.toInt() }.toIntArray(), arr)
                }
            }
        } catch (e: OrtException) {
            AmpyLog.e(LogCategory.MODEL, "ort", "Errore di inferenza ONNX: ${e.message}", e); throw e
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    override fun close() { session.close() }

    companion object {
        val factory = TensorRunnerFactory { path, dev -> OrtTensorRunner(path, dev) }
    }
}
