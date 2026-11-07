package com.linguareader.engine.onnx

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.dataWithLength
import platform.posix.memcpy

/*
 * iOS: la app enlaza ONNX Runtime con su API Objective-C (pod `onnxruntime-objc`, ORT estático) e implementa en Swift
 * este puente pequeño (`ios/LinguaReader/Speech/OrtBridge.swift`); Kotlin no usa cinterop (así el framework sigue
 * compilando en Windows y en el job de simulador sin pods). Los tensores viajan como bytes (`NSData`, float32 o int64
 * en el orden de la máquina) con su forma.
 */

/** Lo que implementa la app con ONNX Runtime. Las excepciones Swift llegan como `Throwable`. */
interface OnnxNativeBridge {
    /** @param optimization 0 básico, 1 extendido, 2 todo. @param interOpThreads ≤ 0 = predeterminado. */
    @Throws(Throwable::class)
    fun createSession(modelPath: String, intraOpThreads: Int, interOpThreads: Int, optimization: Int): OnnxNativeSession
}

interface OnnxNativeSession {
    val inputNames: List<String>

    /**
     * Ejecuta el modelo. Las listas van en paralelo: nombre, bytes, tipo ([OnnxNativeTensor.FLOAT] o
     * [OnnxNativeTensor.INT64]) y forma. Devuelve todas las salidas en el orden del modelo.
     */
    @Throws(Throwable::class)
    fun run(names: List<String>, data: List<NSData>, kinds: List<Int>, shapes: List<List<Long>>): List<OnnxNativeTensor>

    fun close()
}

/** Tensor de salida que devuelve el puente (y que puede volver a entrar como entrada). */
class OnnxNativeTensor(val data: NSData, val kind: Int, val shape: List<Long>) {
    companion object {
        const val FLOAT = 0
        const val INT64 = 1
    }
}

/** Puente instalado por la app al arrancar (null en el simulador sin pods o si ORT no se pudo iniciar). */
object IosOnnx {
    var bridge: OnnxNativeBridge? = null
    val available: Boolean get() = bridge != null
}

actual fun platformOnnxRuntime(): OnnxRuntime = IosOnnx.bridge?.let(::BridgedOnnxRuntime) ?: throw UnsupportedOperationException(
    "ONNX Runtime is not linked in this build (the simulator build has no pods)",
)

@OptIn(ExperimentalForeignApi::class)
private class BridgedOnnxRuntime(private val bridge: OnnxNativeBridge) : OnnxRuntime {

    override fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        BridgedTensor(bytes(data.size * 4L) { dst -> data.usePinned { memcpy(dst, it.addressOf(0), (data.size * 4).toULong()) } }, OnnxNativeTensor.FLOAT, shape)

    override fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        BridgedTensor(bytes(data.size * 8L) { dst -> data.usePinned { memcpy(dst, it.addressOf(0), (data.size * 8).toULong()) } }, OnnxNativeTensor.INT64, shape)

    override fun createSession(modelPath: String, options: OnnxSessionOptions): OnnxSession = BridgedSession(
        bridge.createSession(
            modelPath, options.intraOpThreads, options.interOpThreads ?: 0,
            when (options.optimization) {
                OnnxOptLevel.BASIC -> 0
                OnnxOptLevel.EXTENDED -> 1
                OnnxOptLevel.ALL -> 2
            },
        ),
    )

    /** Bytes nuevos (sin copia intermedia): Swift los pasa a ORT tal cual (`NSMutableData`). */
    private fun bytes(size: Long, fill: (COpaquePointer) -> Unit): NSData {
        val data = NSMutableData.dataWithLength(size.toULong()) ?: error("Out of memory for a $size-byte tensor")
        if (size > 0) data.mutableBytes?.let(fill)
        return data
    }
}

@OptIn(ExperimentalForeignApi::class)
private class BridgedTensor(val data: NSData, val kind: Int, override val shape: LongArray) : OnnxTensor {
    override fun floats(): FloatArray {
        check(kind == OnnxNativeTensor.FLOAT) { "Tensor is not float" }
        val n = (data.length / 4u).toInt()
        val out = FloatArray(n)
        val src = data.bytes
        if (n > 0 && src != null) out.usePinned { memcpy(it.addressOf(0), src, (n * 4).toULong()) }
        return out
    }

    override fun close() {}
}

private class BridgedSession(private val session: OnnxNativeSession) : OnnxSession {
    override val inputNames: List<String> get() = session.inputNames

    override fun run(inputs: Map<String, OnnxTensor>): List<OnnxTensor> {
        val names = inputs.keys.toList()
        val tensors = names.map { inputs.getValue(it) as BridgedTensor }
        return session.run(names, tensors.map { it.data }, tensors.map { it.kind }, tensors.map { it.shape.toList() })
            .map { BridgedTensor(it.data, it.kind, it.shape.toLongArray()) }
    }

    override fun close() = session.close()
}
