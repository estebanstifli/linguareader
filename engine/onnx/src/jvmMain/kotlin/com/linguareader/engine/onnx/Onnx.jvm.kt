package com.linguareader.engine.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import ai.onnxruntime.OnnxTensor as OrtTensor

/** API Java de ONNX Runtime (`ai.onnxruntime`), la misma en Android (onnxruntime-android) y en el PC. */
class JvmOnnxRuntime(private val env: OrtEnvironment = OrtEnvironment.getEnvironment()) : OnnxRuntime {

    override fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        JvmTensor(OrtTensor.createTensor(env, FloatBuffer.wrap(data), shape))

    override fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        JvmTensor(OrtTensor.createTensor(env, LongBuffer.wrap(data), shape))

    override fun createSession(modelPath: String, options: OnnxSessionOptions): OnnxSession {
        val session = OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(options.intraOpThreads)
            options.interOpThreads?.let { o.setInterOpNumThreads(it) }
            o.setOptimizationLevel(
                when (options.optimization) {
                    OnnxOptLevel.BASIC -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
                    OnnxOptLevel.EXTENDED -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
                    OnnxOptLevel.ALL -> OrtSession.SessionOptions.OptLevel.ALL_OPT
                },
            )
            env.createSession(modelPath, o)
        }
        return JvmSession(session)
    }

    private class JvmTensor(val tensor: OrtTensor) : OnnxTensor {
        override val shape: LongArray get() = tensor.info.shape
        override fun floats(): FloatArray {
            val fb = tensor.floatBuffer
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
        override fun close() = tensor.close()
    }

    private class JvmSession(val session: OrtSession) : OnnxSession {
        override val inputNames: List<String> get() = session.inputNames.toList()

        override fun run(inputs: Map<String, OnnxTensor>): List<OnnxTensor> {
            val native = inputs.mapValues { (it.value as JvmTensor).tensor }
            val result = session.run(native)
            // Las salidas se desligan del Result para que el llamante las cierre una a una.
            val outputs = ArrayList<OnnxTensor>(result.size())
            try {
                for (i in 0 until result.size()) outputs += JvmTensor(result.get(i) as OrtTensor)
            } catch (t: Throwable) {
                result.close()
                throw t
            }
            return outputs
        }

        override fun close() = session.close()
    }
}

actual fun platformOnnxRuntime(): OnnxRuntime = JvmOnnxRuntime()
