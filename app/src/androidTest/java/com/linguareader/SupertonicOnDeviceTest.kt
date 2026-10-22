package com.linguareader

import okio.Path.Companion.toOkioPath
import android.util.Log
import com.linguareader.core.audio.CachingSpeechSynthesizer
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModelDir
import com.linguareader.engine.supertonic.SupertonicModelManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Supertonic real en el dispositivo (ONNX Runtime Android, CPU). Se omite si el modelo no está
 * instalado: instálalo desde Ajustes o con `.\run.cmd -Supertonic` (adb push desde .models/).
 */
@HiltAndroidTest
class SupertonicOnDeviceTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var engine: SupertonicEngine
    @Inject lateinit var synthesizer: CachingSpeechSynthesizer
    @Inject lateinit var models: SupertonicModelManager

    @Before
    fun setUp() {
        hilt.inject()
        assumeTrue("Modelo Supertonic no instalado en ${models.modelDir}", SupertonicModelDir(models.modelDir.toOkioPath()).isComplete())
    }

    @Test
    fun shortEnglishTextIsSynthesizedOfflineWithMetrics() = runBlocking {
        assertEquals(EngineReadiness.Ready, engine.availability("en", "F1"))
        val out = File.createTempFile("supertonic-test", ".wav")
        val result = engine.synthesizeTo(SpeechRequest("Hello! This is a short Supertonic test on Android.", "en", "F1", 1.0f), out.toOkioPath())
        assertTrue(result.toString(), result is SynthesisOutcome.Written)
        val m = engine.stats.value.lastSynthesis!!
        Log.i("SupertonicTest", "load=${engine.stats.value.lastLoad} synth=${m.synthMs}ms audio=${m.audioMs}ms rtf=${m.rtf} pss=${m.memoryMb}MB")
        assertTrue("audio demasiado corto: ${m.audioMs}", m.audioMs in 1_500..15_000)
        assertTrue(out.length() > 44 + 44_100) // > 0,5 s de PCM16
    }

    @Test
    fun cachedSynthesisIsReusedAndSessionsCanBeReleased() = runBlocking {
        val req = SpeechRequest("Tom is eight years old.", "en", "M1", 1.0f, mapOf("steps" to "3"))
        val a = synthesizer.synthesize(req) as SpeechResult.Success
        val t0 = System.nanoTime()
        val b = synthesizer.synthesize(req) as SpeechResult.Success
        val hitMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(a.audio.path, b.audio.path)
        assertTrue("acierto de caché lento: $hitMs ms", hitMs < 200)
        engine.release()
        Thread.sleep(500)
        assertTrue(!engine.stats.value.loaded)
    }

    @Test
    fun frenchFromTheDemoBook() = runBlocking {
        val out = File.createTempFile("supertonic-fr", ".wav")
        val result = engine.synthesizeTo(SpeechRequest("Lila habite à Lyon avec sa grand-mère.", "fr", "F2", 1.0f), out.toOkioPath())
        assertTrue(result is SynthesisOutcome.Written)
    }
}
