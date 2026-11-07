package com.linguareader

import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.engine.onnx.OnnxRuntime
import com.linguareader.engine.onnx.OnnxSession
import com.linguareader.engine.onnx.OnnxSessionOptions
import com.linguareader.engine.onnx.platformOnnxRuntime
import com.linguareader.engine.supertonic.MemoryProbe
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModelDir
import com.linguareader.engine.supertonic.SupertonicModelManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.security.MessageDigest

/**
 * Real Android CPU measurements; no timing threshold makes a slower phone fail.
 * Each block owns ONE engine, warms it, measures three requests, then closes it.
 * Mirrored blocks reduce order/temperature bias. No two model instances overlap.
 */
class SupertonicPerformanceDeviceTest : KoinComponent {
    private val appEngine: SupertonicEngine by inject()
    private val models: SupertonicModelManager by inject()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Candidate(val label: String, val threads: Int, val internalTimings: Boolean)
    private data class Measurement(
        val candidate: Candidate,
        val block: Int,
        val repetition: Int,
        val synthesisMs: Long,
        val wallMs: Long,
        val audioMs: Long,
        val rtf: Double,
        val pssMb: Long?,
        val marks: Int,
        val wavSha256: String,
    ) {
        fun json() = JSONObject()
            .put("candidate", candidate.label).put("threads", candidate.threads)
            .put("internalTimings", candidate.internalTimings).put("block", block)
            .put("repetition", repetition).put("synthesisMs", synthesisMs)
            .put("wallMsIncludingWavWrite", wallMs).put("audioMs", audioMs).put("rtf", rtf)
            .put("pssMb", pssMb ?: JSONObject.NULL).put("marks", marks).put("wavSha256", wavSha256)
    }

    @Test fun compareCpuThreadsAndInternalTimingOverhead() = runBlocking {
        assertTrue("Install the real Supertonic model first", SupertonicModelDir(models.modelDir).isComplete())
        appEngine.release()
        withTimeout(30_000) { appEngine.stats.first { !it.loaded } }

        val output = File(context.filesDir, "internal-timing-test").apply { mkdirs() }
        val request = SpeechRequest(
            "Ana lee un libro junto a la ventana mientras escucha la lluvia.",
            "es", "F1", 1f, mapOf("steps" to "5"),
        )
        val includeOne = InstrumentationRegistry.getArguments()
            .getString("supertonicPerformanceOneThread") == "true"
        val candidates = buildList {
            if (includeOne) add(Candidate("timings_1", 1, true))
            add(Candidate("timings_2", 2, true))
            add(Candidate("timings_4", 4, true))
            add(Candidate("original_4", 4, false))
        }
        val order = candidates + candidates.reversed()
        val rows = mutableListOf<Measurement>()
        val warmups = JSONArray()
        val report = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("api", Build.VERSION.SDK_INT)
            .put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("text", request.text).put("language", request.languageTag).put("voice", request.voiceId)
            .put("speed", request.speed.toDouble()).put("steps", 5)
            .put("method", "Mirrored candidate blocks; one warmup and three measured requests per loaded engine; engines closed between blocks; no parallel inference; no audio cache")
            .put("synthesisMetric", "engine.stats.lastSynthesis.synthMs includes inference, timing extraction and audio trimming; excludes model load and WAV write")
            .put("originalComparison", "Same new text frontend and app code, but routes vector session to original single-output ONNX; measures graph/output/decoder delta, not entire historical app")
            .put("performanceIsNotArmPhonePrediction", true)
            .put("order", JSONArray(order.map { it.label }))

        fun persist(complete: Boolean) {
            report.put("complete", complete).put("warmups", warmups)
                .put("measurements", JSONArray(rows.map { it.json() }))
            val summaries = JSONObject()
            candidates.forEach { candidate ->
                val selected = rows.filter { it.candidate == candidate }
                if (selected.isNotEmpty()) {
                    summaries.put(candidate.label, JSONObject()
                        .put("count", selected.size)
                        .put("threads", candidate.threads)
                        .put("medianSynthesisMs", median(selected.map { it.synthesisMs.toDouble() }))
                        .put("medianRtf", median(selected.map { it.rtf }))
                        .put("minSynthesisMs", selected.minOf { it.synthesisMs })
                        .put("maxSynthesisMs", selected.maxOf { it.synthesisMs })
                        .put("wavHashes", JSONArray(selected.map { it.wavSha256 }.distinct())))
                }
            }
            report.put("summaries", summaries)
            if (complete) {
                val two = median(rows.filter { it.candidate.label == "timings_2" }.map { it.synthesisMs.toDouble() })
                val four = median(rows.filter { it.candidate.label == "timings_4" }.map { it.synthesisMs.toDouble() })
                val original = median(rows.filter { it.candidate.label == "original_4" }.map { it.synthesisMs.toDouble() })
                report.put("twoThreadsVsFourPercent", (two / four - 1.0) * 100)
                    .put("internalTimingVsOriginalFourThreadsPercent", (four / original - 1.0) * 100)
            }
            File(output, "performance.json").writeText(report.toString(2))
        }

        persist(false)
        for ((block, candidate) in order.withIndex()) {
            val delegate = platformOnnxRuntime()
            val runtime = if (candidate.internalTimings) delegate else object : OnnxRuntime by delegate {
                override fun createSession(modelPath: String, options: OnnxSessionOptions): OnnxSession {
                    val path = if (modelPath.endsWith("/vector_estimator.timing.onnx")) {
                        (models.modelDir / "onnx/vector_estimator.onnx").toString()
                    } else modelPath
                    return delegate.createSession(path, options)
                }
            }
            val engine = SupertonicEngine(
                modelDir = { models.modelDir },
                intraOpThreads = candidate.threads,
                memory = MemoryProbe { Debug.getPss() * 1024L },
                idleReleaseMs = 600_000,
                onnx = { runtime },
                log = { Log.i("SupertonicPerformance", "${candidate.label}: $it") },
            )
            try {
                val warmFile = File(output, "performance-${candidate.label}-$block-warm.wav")
                val warm = engine.synthesizeTo(request, warmFile.toOkioPath())
                assertTrue("warmup ${candidate.label}: $warm", warm is SynthesisOutcome.Written)
                val warmMetrics = requireNotNull(engine.stats.value.lastSynthesis)
                warmups.put(JSONObject().put("candidate", candidate.label).put("block", block)
                    .put("loadMs", engine.stats.value.lastLoad?.loadMs ?: JSONObject.NULL)
                    .put("verifyMs", engine.stats.value.lastLoad?.verifyMs ?: JSONObject.NULL)
                    .put("synthesisMs", warmMetrics.synthMs).put("audioMs", warmMetrics.audioMs))
                repeat(3) { repetition ->
                    val file = File(output, "performance-${candidate.label}-$block-$repetition.wav")
                    val started = System.nanoTime()
                    val result = engine.synthesizeTo(request, file.toOkioPath())
                    val wallMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue("${candidate.label}: $result", result is SynthesisOutcome.Written)
                    result as SynthesisOutcome.Written
                    val metrics = requireNotNull(engine.stats.value.lastSynthesis)
                    assertNull("Measured requests must use already loaded sessions", metrics.loadMs)
                    assertEquals(5, metrics.steps)
                    assertEquals(1, metrics.chunks)
                    assertTrue(metrics.audioMs > 1_000)
                    if (candidate.internalTimings) {
                        assertNotNull(result.marks)
                        assertTrue(result.marks!!.isNotEmpty())
                        assertTrue(result.marks!!.all { it.endMs != null && it.endMs!! > it.ms })
                    } else {
                        assertNull("Original ONNX must expose no word-attention output", result.marks)
                    }
                    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    val row = Measurement(candidate, block, repetition, metrics.synthMs, wallMs,
                        metrics.audioMs, metrics.rtf, metrics.memoryMb, result.marks?.size ?: 0, digest)
                    rows += row
                    Log.i("SupertonicPerformance", row.json().toString())
                    persist(false)
                }
            } finally {
                engine.shutdown() // Synchronous close: the next block cannot overlap native models.
                assertFalse(engine.stats.value.loaded)
            }
        }
        assertEquals(candidates.size * 6, rows.size)
        val pairedAudio = rows.filter { it.candidate.threads == 4 }.map { it.wavSha256 }.distinct()
        assertEquals("Internal timing extraction must preserve original PCM at the same thread count", 1, pairedAudio.size)
        persist(true)
        Log.i("SupertonicPerformance", report.toString())
        Unit // JUnit 4 requires a void test method; Log.i returns an Int.
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }
}
