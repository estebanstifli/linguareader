package com.linguareader.engine.onnx

/**
 * Puerto de inferencia ONNX que usan los motores compartidos. Es deliberadamente pequeño: lo que necesitan
 * el alineador wav2vec2 (un tensor float de entrada, un tensor float de salida) y Supertonic (tensores
 * float e int64, varias sesiones, salidas que vuelven a entrar como entradas).
 *
 * Los tensores son recursos nativos: hay que cerrarlos ([AutoCloseable]). Las sesiones no son seguras para uso
 * concurrente; cada motor serializa sus llamadas.
 */
interface OnnxTensor : AutoCloseable {
    val shape: LongArray

    /** Copia del contenido como float (para salidas). */
    fun floats(): FloatArray
}

enum class OnnxOptLevel { BASIC, EXTENDED, ALL }

data class OnnxSessionOptions(
    val intraOpThreads: Int,
    val interOpThreads: Int = 1,
    val optimization: OnnxOptLevel = OnnxOptLevel.ALL,
)

interface OnnxSession : AutoCloseable {
    val inputNames: List<String>

    /** Ejecuta el modelo; las salidas devueltas son del llamante (debe cerrarlas). */
    fun run(inputs: Map<String, OnnxTensor>): List<OnnxTensor>
}

interface OnnxRuntime {
    fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor
    fun longTensor(data: LongArray, shape: LongArray): OnnxTensor
    fun createSession(modelPath: String, options: OnnxSessionOptions): OnnxSession
}

/** ONNX Runtime de la plataforma (API Java en JVM/Android; API C en iOS cuando esté enlazada). */
expect fun platformOnnxRuntime(): OnnxRuntime

/** Cierra todo en orden inverso sin propagar fallos (limpieza de tensores intermedios). */
fun closeAll(resources: List<AutoCloseable>) {
    resources.asReversed().forEach { runCatching { it.close() } }
}
