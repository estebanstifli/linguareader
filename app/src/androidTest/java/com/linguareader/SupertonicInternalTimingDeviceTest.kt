package com.linguareader

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.audio.CachingSpeechSynthesizer
import com.linguareader.core.audio.SpokenMark
import com.linguareader.core.audio.SpokenMarksSidecar
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.text.Tokenizer
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.engine.supertonic.SupertonicModelDir
import com.linguareader.engine.supertonic.SupertonicModelManager
import com.linguareader.engine.supertonic.SupertonicSampleTexts
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** Real ORT Android + model + persistent sidecars. No ASR, mocked inference, or alignment model. */
class SupertonicInternalTimingDeviceTest : KoinComponent {
    private val engine: SupertonicEngine by inject()
    private val cached: CachingSpeechSynthesizer by inject()
    private val models: SupertonicModelManager by inject()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = File(context.filesDir, "internal-timing-test").apply { mkdirs() }

    @Before fun requireRealModel() {
        // This suite must fail, not silently skip, when deployment was not completed.
        assertTrue("Install the real model before this suite: ${models.modelDir}", SupertonicModelDir(models.modelDir).isComplete())
        assertTrue(engine.info.providesWordTimings)
    }

    @After fun releaseModel() { engine.release() }

    @Test fun all31LanguagesProduceBoundedInternalMarksOnAndroid() = runBlocking {
        val results = JSONArray()
        for ((index, lang) in SupertonicModel.LANGUAGES.sorted().withIndex()) {
            val text = SupertonicSampleTexts.sample(lang)
            val voice = if (index % 2 == 0) "F1" else "M1"
            val file = File(evidence, "sample-$lang.wav")
            val result = engine.synthesizeTo(SpeechRequest(text, lang, voice, 1f, mapOf("steps" to "5")), file.toOkioPath())
            assertTrue("$lang synthesis: $result", result is SynthesisOutcome.Written)
            result as SynthesisOutcome.Written
            verifyMarks(lang, text, result.durationMs, result.marks)
            val metrics = engine.stats.value.lastSynthesis!!
            val row = JSONObject().put("language", lang).put("voice", voice).put("text", text)
                .put("audioMs", result.durationMs).put("synthesisMs", metrics.synthMs)
                .put("rtf", metrics.rtf).put("memoryMb", metrics.memoryMb).put("marks", result.marks!!.size)
                .put("intervals", JSONArray(result.marks!!.map { mark -> JSONObject()
                    .put("start", mark.start).put("end", mark.end).put("startMs", mark.ms).put("endMs", mark.endMs) }))
            results.put(row)
            File(evidence, "languages.json").writeText(results.toString(2))
            Log.i("InternalTimingTest", row.toString())
        }
        assertEquals(31, results.length())
    }

    @Test fun cachedAudioRetainsExactInternalEndTimes() = runBlocking {
        val request = SpeechRequest("El jardín está tranquilo y la fuente suena despacio.", "es", "F1", 1f, mapOf("steps" to "5"))
        val first = cached.synthesize(request)
        assertTrue(first.toString(), first is SpeechResult.Success)
        first as SpeechResult.Success
        val path = File(first.audio.path).toOkioPath()
        val marks = SpokenMarksSidecar.read(FileSystem.SYSTEM, path)
        assertNotNull(marks)
        assertTrue(marks!!.all { it.endMs != null && it.endMs!! > it.ms })
        val before = engine.stats.value.lastSynthesis
        val second = cached.synthesize(request) as SpeechResult.Success
        assertEquals(first.audio.path, second.audio.path)
        assertEquals(marks, SpokenMarksSidecar.read(FileSystem.SYSTEM, path))
        assertSame("A cache hit must not perform another inference", before, engine.stats.value.lastSynthesis)
    }

    @Test fun unicodePunctuationSpeedAndMultipleChunksPreserveOriginalOffsets() = runBlocking {
        val cases = listOf(
            Triple("ar", "الجو جميل؛ نفتح النافذة ونشرب الشاي.", 1.15f),
            Triple("hi", "यह किताब मेरी मेज़ पर रखी है।", 0.85f),
            Triple("fr", "L’école est près d’ici, aujourd’hui il fait beau.", 1.15f),
            Triple("es", List(12) { "Ana lee un libro junto a la ventana." }.joinToString(" "), 1f),
        )
        cases.forEachIndexed { index, (lang, text, speed) ->
            val result = engine.synthesizeTo(SpeechRequest(text, lang, "F1", speed, mapOf("steps" to "5")),
                File(evidence, "stress-$index.wav").toOkioPath())
            assertTrue("$lang: $result", result is SynthesisOutcome.Written)
            result as SynthesisOutcome.Written
            verifyMarks(lang, text, result.durationMs, result.marks)
            if (lang == "es") assertTrue("The test must actually exercise chunk concatenation", engine.stats.value.lastSynthesis!!.chunks > 1)
        }
    }

    private fun verifyMarks(lang: String, text: String, audioMs: Long, marks: List<SpokenMark>?) {
        assertNotNull("$lang missing internal timings", marks)
        val expected = Tokenizer.tokenize(text, lang)
        assertEquals("$lang source token coverage", expected.map { it.start to it.end }, marks!!.map { it.start to it.end })
        var previousEnd = 0L
        marks.forEach { mark ->
            assertTrue("$lang invalid source span $mark", mark.start >= 0 && mark.end > mark.start && mark.end <= text.length)
            val end = requireNotNull(mark.endMs) { "$lang missing final boundary: $mark" }
            assertTrue("$lang bounds $mark, audio=$audioMs", mark.ms >= 0 && end > mark.ms && end <= audioMs)
            assertTrue("$lang overlap: $mark", mark.ms >= previousEnd)
            previousEnd = end
        }
    }
}
