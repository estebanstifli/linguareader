package com.linguareader.core.reader

import com.linguareader.core.alignment.ClipAligner
import com.linguareader.core.alignment.WordSpanMs
import com.linguareader.core.text.PassageBuilder
import com.linguareader.core.text.SentenceInput
import com.linguareader.core.text.speech.Icu4jNumberSpeller
import com.linguareader.core.text.speech.RuleBasedSpeechNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechNormalizationTest {

    private class RecordingAligner : ClipAligner {
        override val id = "rec"
        val words = mutableListOf<List<String>>()
        override fun signature(languageTag: String) = "rec"
        override suspend fun align(clipPath: String, words: List<String>, languageTag: String): List<WordSpanMs> {
            this.words += words
            return words.mapIndexed { i, _ -> WordSpanMs(i, 100L + 300 * i, 100L + 300 * i + 250, 0.9f) }
        }
        override fun release() {}
    }

    private val passage = PassageBuilder.build(
        "p1", "c1", 0, "es",
        listOf(SentenceInput("s1", "Tengo 42 gatos.", emptyMap()), SentenceInput("s2", "Hola.", emptyMap())),
        null,
    )
    private val synth = FakeSynthesizer()
    private val player = FakeClipPlayer()
    private val aligner = RecordingAligner()

    private fun TestScope.controller() = RenderedReaderAudioController(
        synth, player, FakeClipPlayer(), StandardTestDispatcher(testScheduler),
        aligner = aligner, normalizer = RuleBasedSpeechNormalizer(Icu4jNumberSpeller),
        alignDispatcher = StandardTestDispatcher(testScheduler), tickMs = 10, clockMs = { testScheduler.currentTime },
    ).also { advanceUntilIdle() }

    @Test
    fun `tts receives the spoken form and the aligner one entry per displayed token`() = runTest {
        val c = controller()
        c.load(passage, "es", null)
        c.play(); advanceTimeBy(50)
        assertEquals("Tengo cuarenta y dos gatos.", synth.requests.first().text)
        assertEquals(listOf("Tengo", "cuarenta y dos", "gatos"), aligner.words.first())

        // «cuarenta y dos» (segunda palabra alineada) ilumina el token «42».
        val tokens = passage.tokensOf(passage.sentences[0])
        player.position = ClipPosition(0, 100L + 300 + 10)
        advanceTimeBy(30)
        assertEquals(tokens[1].id, c.playbackState.value.activeTokenId)
        c.pause(); advanceUntilIdle()
    }

    @Test
    fun `word audio is normalized too`() = runTest {
        val c = controller()
        c.load(passage, "es", null)
        c.speakSnippet("42", "es"); advanceUntilIdle()
        assertEquals("cuarenta y dos", synth.requests.last().text)
    }
}
