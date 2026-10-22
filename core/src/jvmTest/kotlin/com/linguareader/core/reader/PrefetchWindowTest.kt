package com.linguareader.core.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La generación no se detiene al final de la página: sigue con las siguientes (ventana de [PREFETCH_PAGES_AHEAD]). */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchWindowTest {

    private val all = TestPages.build(8)
    private val current = all[0]
    private val window = all.subList(1, 4)
    private val synth = FakeSynthesizer()
    private val player = FakeClipPlayer()

    private fun TestScope.controller() =
        RenderedReaderAudioController(synth, player, FakeClipPlayer(), StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }

    private fun texts(vararg pages: List<com.linguareader.core.model.Passage>) =
        pages.flatMap { it }.flatMap { p -> p.sentences.map { it.text } }

    @Test
    fun `the window is the next pages and shrinks at the end of the book`() {
        assertEquals(4, PREFETCH_PAGES_AHEAD)
        assertEquals(all.subList(1, 5), PrefetchWindow.after(all, 0))
        assertEquals(all.subList(all.size - 2, all.size), PrefetchWindow.after(all, all.size - 3))
        assertEquals(listOf(all.last()), PrefetchWindow.after(all, all.size - 2))
        assertTrue(PrefetchWindow.after(all, all.lastIndex).isEmpty())
        assertTrue(PrefetchWindow.after(all, 0, ahead = 0).isEmpty())
    }

    @Test
    fun `it generates the whole window in reading order after the current page`() = runTest {
        val c = controller()
        c.load(current, "en", null)
        c.play()
        c.prefetch(window, "en", null); advanceUntilIdle()
        assertEquals(texts(listOf(current), window), synth.requests.map { it.text })
        assertEquals("lo precargado no se encola en el reproductor", current.sentences.size, player.queue.size)
    }

    @Test
    fun `it does not start while the current page is still being generated`() = runTest {
        synth.gate = CompletableDeferred()
        val c = controller()
        c.load(current, "en", null)
        c.play()
        c.prefetch(window, "en", null); runCurrent()
        assertTrue("la página que suena tiene prioridad", synth.requests.isEmpty())
        synth.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(texts(listOf(current), window), synth.requests.map { it.text })
    }

    @Test
    fun `repeating the same window does not restart the one in progress`() = runTest {
        // La página actual pasa; la precarga se queda bloqueada en su primera petición.
        synth.gateAfter = current.sentences.size
        synth.gate = CompletableDeferred()
        val c = controller()
        c.load(current, "en", null)
        c.play()
        c.prefetch(window, "en", null); advanceUntilIdle()
        val startedBefore = synth.started
        assertEquals(current.sentences.size + 1, startedBefore)

        c.prefetch(window, "en", null); advanceUntilIdle()
        assertEquals("mismo plan: no se reinicia (no se pierde la frase en curso)", startedBefore, synth.started)

        synth.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(texts(listOf(current), window), synth.requests.map { it.text })
    }

    @Test
    fun `a shifted window replaces the running one`() = runTest {
        synth.gateAfter = current.sentences.size
        synth.gate = CompletableDeferred()
        val c = controller()
        c.load(current, "en", null)
        c.play()
        c.prefetch(window, "en", null); advanceUntilIdle()
        val startedBefore = synth.started

        // El lector pasó de página: la ventana se desplaza una página.
        c.prefetch(all.subList(2, 5), "en", null); advanceUntilIdle()
        assertEquals("el plan nuevo reinicia la precarga", startedBefore + 1, synth.started)

        synth.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(texts(listOf(current), all.subList(2, 5)), synth.requests.map { it.text })
    }

    @Test
    fun `pausing stops generating pages nobody is listening to, and resuming continues`() = runTest {
        synth.gateAfter = current.sentences.size
        synth.gate = CompletableDeferred()
        val c = controller()
        c.load(current, "en", null)
        c.play()
        c.prefetch(window, "en", null); advanceUntilIdle() // la precarga queda bloqueada en su primera petición

        c.pause()
        synth.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("en pausa no se genera nada más", texts(listOf(current)), synth.requests.map { it.text })

        c.play(); advanceUntilIdle()
        assertEquals("al reanudar continúa con la ventana", texts(listOf(current), window), synth.requests.map { it.text })
    }

    @Test
    fun `a synthesis failure stops the window instead of insisting`() = runTest {
        val c = controller()
        c.load(current, "en", null)
        c.play(); advanceUntilIdle() // la página actual se genera bien
        synth.requests.clear()
        synth.failWith = com.linguareader.core.engine.EngineUnavailableReason.OUT_OF_MEMORY
        c.prefetch(window, "en", null); advanceUntilIdle()
        assertEquals("falla en la primera frase y no sigue con las demás", 1, synth.requests.size)
    }
}
