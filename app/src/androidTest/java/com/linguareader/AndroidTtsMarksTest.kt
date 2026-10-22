package com.linguareader

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.engine.tts.AndroidTtsEngine
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Diagnóstico del TTS del sistema: sintetiza una frase en inglés a fichero con el motor instalado y deja en el log
 * (`TtsMarks`) las marcas `onRangeStart` convertidas a ms, para compararlas entre builds. Se omite si no hay voz.
 */
class AndroidTtsMarksTest {

    @Test
    fun englishSentenceGivesWordMarks() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AndroidTtsEngine(ctx, com.linguareader.di.EngineSettingsHolder())
        val text = "Tom is eight years old. He lives in a small house near the sea with his mother and his dog."
        val request = SpeechRequest(text = text, languageTag = "en", voiceId = null, speed = 1.0f)
        val ready = engine.availability("en", null)
        Log.i("TtsMarks", "disponibilidad: $ready")
        assumeTrue("sin voz en inglés: $ready", ready is com.linguareader.core.engine.EngineReadiness.Ready)
        repeat(2) { round ->
            val target = File(ctx.cacheDir, "tts-marks-$round.wav")
            target.delete()
            val t0 = System.currentTimeMillis()
            val outcome = engine.synthesizeTo(request, target.toOkioPath())
            val ms = System.currentTimeMillis() - t0
            when (outcome) {
                is SynthesisOutcome.Failed -> Log.i("TtsMarks", "ronda $round: FALLO ${outcome.failure}")
                is SynthesisOutcome.Written -> {
                    val marks = outcome.marks.orEmpty()
                    Log.i("TtsMarks", "ronda $round: ${target.length()} bytes, ${outcome.durationMs} ms de audio en $ms ms, ${marks.size} marcas")
                    marks.forEach { m -> Log.i("TtsMarks", "  ${m.ms} ms  [${m.start},${m.end}) '${text.substring(m.start, m.end)}'") }
                    if (marks.isEmpty()) Log.w("TtsMarks", "el motor instalado no da marcas de palabra: el lector resaltará por frase o con el alineador")
                }
            }
        }
        engine.release()
    }
}
