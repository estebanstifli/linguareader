package com.linguareader.engine.supertonic

import okio.Path.Companion.toOkioPath
import com.linguareader.core.audio.CachingSpeechSynthesizer
import com.linguareader.core.audio.FileAudioCache
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Prueba funcional con el modelo real de Supertonic 3 en el PC (ONNX Runtime CPU).
 * Se omite si el modelo no está en `.models/` (salvo con -Dsupertonic.download=true, que lo descarga).
 *   ./gradlew :engine:supertonic:smokeTest
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SupertonicSmokeTest {

    companion object {
        private val modelDir = File(System.getProperty("supertonic.modelDir", ".models/none"))
        private val outDir = File(System.getProperty("supertonic.outDir", "build/supertonic-smoke")).apply { mkdirs() }
        private lateinit var engine: SupertonicEngine

        @BeforeClass @JvmStatic
        fun setUp() {
            if (System.getProperty("supertonic.download") == "true" && !SupertonicModelDir(modelDir.toOkioPath()).isComplete()) {
                println("Descargando Supertonic 3 (${SupertonicModel.TOTAL_BYTES / 1_000_000} MB) en $modelDir …")
                var lastPct = -1
                val r = runBlocking {
                    ModelDownloader(SupertonicModel.BASE_URL, SupertonicModel.FILES).download(modelDir.toOkioPath()) { p ->
                        val pct = (p.fraction * 100).toInt()
                        if (pct / 10 != lastPct / 10) { lastPct = pct; println("  $pct% (${p.currentFile})") }
                    }
                }
                println("Descarga: $r")
            }
            assumeTrue("Modelo Supertonic no descargado en $modelDir", SupertonicModelDir(modelDir.toOkioPath()).isComplete())
            engine = SupertonicEngine(modelDir = { modelDir.toOkioPath() }, memory = MemoryProbe.PROCESS, log = { println("[supertonic] $it") })
        }

        @AfterClass @JvmStatic
        fun tearDown() {
            if (::engine.isInitialized) engine.shutdown()
        }
    }

    private fun wavStats(file: File): Pair<Double, Double> {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = (file.length().toInt() - 44) / 2
        var sum = 0.0
        for (i in 0 until n) { val s = b.getShort(44 + i * 2) / 32768.0; sum += s * s }
        return n / 44_100.0 to sqrt(sum / n)
    }

    @Test
    fun `1 short english text produces audible speech with metrics`() = runBlocking {
        assertEquals(EngineReadiness.Ready, engine.availability("en", "F1"))
        val load = engine.warmUp()
        println("Carga: $load")
        val text = "Hello! This is a short test of the Supertonic voice, running offline on this device."
        for (steps in listOf(5, 8)) {
            val out = File(outDir, "en_F1_steps$steps.wav")
            val r = engine.synthesizeTo(SpeechRequest(text, "en", "F1", 1.0f, mapOf("steps" to "$steps")), out.toOkioPath())
            val m = engine.stats.value.lastSynthesis!!
            val (seconds, rms) = wavStats(out)
            println("pasos=$steps  síntesis=${m.synthMs} ms  audio=${m.audioMs} ms  RTF=${"%.3f".format(m.rtf)}  mem=${m.memoryMb} MB  rms=${"%.3f".format(rms)}  -> ${out.path}")
            assertTrue(r.toString(), r is com.linguareader.core.audio.SynthesisOutcome.Written)
            assertTrue("duración razonable: $seconds s", seconds in 2.0..15.0)
            assertTrue("no es silencio: rms=$rms", rms > 0.01)
        }
    }

    @Test
    fun `2 french sentence from the demo book`() = runBlocking {
        val out = File(outDir, "fr_M1.wav")
        val r = engine.synthesizeTo(SpeechRequest("Lila habite à Lyon avec sa grand-mère.", "fr", "M1", 1.0f), out.toOkioPath())
        val m = engine.stats.value.lastSynthesis!!
        val (seconds, rms) = wavStats(out)
        println("fr: síntesis=${m.synthMs} ms audio=${m.audioMs} ms RTF=${"%.3f".format(m.rtf)} rms=${"%.3f".format(rms)} -> ${out.path}")
        assertTrue(r is com.linguareader.core.audio.SynthesisOutcome.Written)
        assertTrue(seconds in 1.0..10.0 && rms > 0.01)
    }

    @Test
    fun `3 cache returns the same file without resynthesis`() = runBlocking {
        val cache = FileAudioCache(File(outDir, "cache").toOkioPath(), 50L * 1024 * 1024)
        cache.clear()
        var hits = 0
        val synth = CachingSpeechSynthesizer(engine, cache, onCacheHit = { hits++ })
        val req = SpeechRequest("Tom is eight years old.", "en", "F2", 1.0f)
        val t0 = System.nanoTime()
        val a = synth.synthesize(req) as SpeechResult.Success
        val first = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val b = synth.synthesize(req) as SpeechResult.Success
        val second = (System.nanoTime() - t1) / 1_000_000
        println("caché: primera=$first ms, segunda=$second ms (acierto)")
        assertEquals(1, hits)
        assertEquals(a.audio.path, b.audio.path)
        assertTrue(second < first)
    }

    @Test
    fun `4 sessions are released and can be reloaded`() = runBlocking {
        engine.release()
        Thread.sleep(500)
        assertTrue(!engine.stats.value.loaded)
        val r = engine.synthesizeTo(SpeechRequest("Again.", "en", null, 1.0f), File(outDir, "reload.wav").toOkioPath())
        assertTrue(r is com.linguareader.core.audio.SynthesisOutcome.Written)
        assertTrue(engine.stats.value.loaded)
    }
}
