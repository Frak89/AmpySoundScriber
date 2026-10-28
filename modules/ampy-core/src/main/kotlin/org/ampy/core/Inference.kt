package org.ampy.core

/** Tensore float denso in ordine row-major. */
class FloatTensor(val shape: IntArray, val data: FloatArray) {
    init {
        val n = shape.fold(1L) { a, b -> a * b }
        require(n == data.size.toLong()) { "shape ${shape.contentToString()} incompatibile con ${data.size} elementi" }
    }
    val size: Int get() = data.size
}

enum class InferenceDevice { CPU, NNAPI, XNNPACK, CUDA, DIRECTML, CORE_ML }

/**
 * Astrazione del motore di inferenza. I moduli IA dipendono solo da questa interfaccia;
 * l'implementazione concreta (ONNX Runtime Android, ecc.) vive nella piattaforma.
 */
interface TensorRunner : AutoCloseable {
    val inputNames: List<String>
    val outputNames: List<String>
    /** Dispositivo realmente in uso (dopo eventuale fallback). */
    val activeDevice: InferenceDevice
    fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor>
}

fun interface TensorRunnerFactory {
    fun open(modelPath: String, preferred: InferenceDevice): TensorRunner
}
