package com.linguareader.core.reader

import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.testing.SampleBooks
import com.linguareader.core.text.PassageBuilder
import com.linguareader.core.text.SentenceInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarmUpTest {

    private val passage = SampleBooks.english.passages.first()
    private val next = SampleBooks.french.passages.first()
    private val synth = FakeSynthesizer()
    private val player = FakeClipPlayer()

    private fun TestScope.controller() =
        RenderedReaderAudioController(synth, player, FakeClipPlayer(), StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }

    @Test
    fun `warm-up synthesizes only the first sentences and does not enqueue or play`() = runTest {
        val c = controller()
        c.load(passage, "en", "F1")
        assertTrue(c.warmUp(passage, "en", "F1", WarmUpPolicy(maxSentences = 1)))
        assertEquals(listOf(passage.sentences.first().text), synth.requests.map { it.text })
        assertTrue(player.queue.isEmpty())
        assertFalse(player.playing)
    }

    @Test
    fun `warm-up reports failure when synthesis fails`() = runTest {
        synth.failWith = EngineUnavailableReason.SYNTHESIS_ERROR
        val c = controller()
        c.load(passage, "en", null)
        assertFalse(c.warmUp(passage, "en", null))
    }

    private val longSentence = (1..42).joinToString(" ") { "word$it" } + "."

    private val longFirst = PassageBuilder.build(
        "p-long", "c1", 0, "en",
        listOf(SentenceInput("s1", longSentence, emptyMap()), SentenceInput("s2", "A short one.", emptyMap())),
        image = null,
    )

    @Test
    fun `a first sentence of 15 s or more is enough and the second is not generated`() = runTest {
        synth.durationMs = 16_000
        val c = controller()
        c.load(passage, "en", null)
        assertTrue(c.warmUp(passage, "en", null))
        assertEquals(listOf(passage.sentences[0].text), synth.requests.map { it.text })
    }

    @Test
    fun `a short first sentence also prepares the second`() = runTest {
        synth.durationMs = 4_000
        val c = controller()
        c.load(passage, "en", null)
        assertTrue(c.warmUp(passage, "en", null))
        assertEquals(passage.sentences.take(2).map { it.text }, synth.requests.map { it.text })
    }

    @Test
    fun `the limit is the accumulated duration, not each sentence`() = runTest {
        // 9 s + 9 s = 18 s: con un objetivo de 15 s hacen falta las dos; con 8 s basta la primera.
        synth.durationMs = 9_000
        val c = controller()
        c.load(passage, "en", null)
        c.warmUp(passage, "en", null, WarmUpPolicy(targetDurationMs = 15_000, maxSentences = 5))
        assertEquals(2, synth.requests.size)
        synth.requests.clear()
        c.warmUp(passage, "en", null, WarmUpPolicy(targetDurationMs = 8_000, maxSentences = 5))
        assertEquals("con 8 s de objetivo, los 9 s de la primera frase bastan", 1, synth.requests.size)
    }

    @Test
    fun `it never waits for more sentences than the limit even if all are short`() = runTest {
        synth.durationMs = 500
        val c = controller()
        c.load(passage, "en", null)
        c.warmUp(passage, "en", null, WarmUpPolicy(targetDurationMs = 60_000, maxSentences = 1))
        assertEquals(1, synth.requests.size)
    }

    @Test
    fun `without a reported duration it is estimated from the word count`() = runTest {
        synth.durationMs = null
        val c = controller()
        c.load(longFirst, "en", null)
        // 42 palabras ≈ 16,8 s ≥ 15 s: no hace falta la segunda frase.
        assertTrue(c.warmUp(longFirst, "en", null))
        assertEquals(listOf(longSentence), synth.requests.map { it.text })
    }

    @Test
    fun `it starts from the resume sentence`() = runTest {
        synth.durationMs = 2_000
        val c = controller()
        c.load(passage, "en", null)
        c.warmUp(passage, "en", null, fromSentence = 1)
        assertEquals(listOf(passage.sentences[1].text), synth.requests.map { it.text })
    }

    @Test
    fun `prefetch of the next passage waits for the current one and does not run while idle`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.prefetch(next, "fr", null); advanceUntilIdle()
        assertTrue("sin reproducir no se precarga", synth.requests.isEmpty())
        c.play(); advanceUntilIdle()
        // Primero todas las frases del pasaje actual y después las del siguiente.
        assertEquals(passage.sentences.map { it.text } + next.sentences.map { it.text }, synth.requests.map { it.text })
    }
}
