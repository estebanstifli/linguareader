package com.linguareader.core.reader

import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.testing.SampleBooks
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
        assertTrue(c.warmUp(passage, "en", "F1", 1))
        assertEquals(listOf(passage.sentences.first().text), synth.requests.map { it.text })
        assertTrue(player.queue.isEmpty())
        assertFalse(player.playing)
    }

    @Test
    fun `warm-up reports failure when synthesis fails`() = runTest {
        synth.failWith = EngineUnavailableReason.SYNTHESIS_ERROR
        val c = controller()
        c.load(passage, "en", null)
        assertFalse(c.warmUp(passage, "en", null, 2))
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
