package com.linguareader.core.reader

import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.narration.Narration
import com.linguareader.core.narration.NarrationAligner
import com.linguareader.core.narration.TimedWord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pista continua simulada: la posición avanza con el reloj virtual del test mientras suena. */
private class FakeTrackPlayer(private val now: () -> Long) : ClipPlayer {
    override val events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 8)
    var queued: String? = null
    var playing = false
    val seeks = mutableListOf<Long>()
    private var basePos = 0L
    private var since = 0L
    private val pos get() = if (playing) basePos + (now() - since) else basePos
    override fun enqueue(tag: Int, path: String) { queued = path }
    override fun enqueueSilence(durationMs: Long) {}
    override fun clear() { queued = null }
    override fun play() { if (!playing) { since = now(); playing = true } }
    override fun pause() { basePos = pos; playing = false }
    override fun seekToTag(tag: Int) = false
    override fun seekTo(tag: Int, positionMs: Long): Boolean { seeks += positionMs; basePos = positionMs; since = now(); return true }
    override fun position() = if (queued != null) ClipPosition(0, pos) else null
    override fun release() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class NarratedReaderAudioControllerTest {

    private val book = BookManifestParser.parse(
        """
        { "formatVersion": 1, "id": "en-narr", "title": "T", "learningLanguage": "en", "cefrLevel": "A1",
          "license": { "id": "LicenseRef-Test" }, "sourceAttribution": "Test",
          "chapters": [ { "id": "c1", "title": "One", "passages": [
            { "id": "p1", "sentences": [ { "id": "s1", "text": "Once upon a time there is a pig." },
                                         { "id": "s2", "text": "She lives in a little house." } ] },
            { "id": "p2", "sentences": [ { "id": "s1", "text": "The house is very small." } ] },
            { "id": "p3", "sentences": [ { "id": "s1", "text": "Every night the four pigs sleep in one bed." } ] }
          ] } ] }
        """.trimIndent(),
    )
    private val p0 = book.passages[0]
    private val p1 = book.passages[1]

    /** Narración sintética: 300 ms por palabra, 1 s de silencio entre pasajes; el primero empieza en 500 ms. */
    private val narration: Narration = run {
        var t = 500L
        val words = mutableListOf<TimedWord>()
        for (p in book.passages) {
            for (tok in p.tokens) { words += TimedWord(tok.text, t, t + 280); t += 300 }
            t += 1000
        }
        Narration(book.id, "asset:///n.mp3", NarrationAligner.align(book, words))
    }

    /** El ticker del controlador vive mientras suena: se pausa al final para que runTest pueda terminar. */
    private fun narrated(continuous: Boolean = true, body: suspend TestScope.(NarratedReaderAudioController, FakeTrackPlayer) -> Unit) = runTest {
        val (c, player) = controller(continuous)
        try { body(c, player) } finally { c.pause() }
    }

    private fun TestScope.controller(continuous: Boolean = true): Pair<NarratedReaderAudioController, FakeTrackPlayer> {
        val player = FakeTrackPlayer { testScheduler.currentTime }
        val c = NarratedReaderAudioController(player, StandardTestDispatcher(testScheduler), { continuous }, clockMs = { testScheduler.currentTime })
        return c to player
    }

    @Test
    fun `play starts at the first word and highlights words as the track plays`() = narrated() { c, player ->
        c.attach(narration)
        c.load(p0, "en", null, 0)
        c.play()
        assertEquals(listOf(500L), player.seeks)
        advanceTimeBy(300 * 2 + 50)
        runCurrent()
        assertEquals(HighlightGranularity.WORD, c.playbackState.value.granularity)
        assertEquals(p0.tokens[2].id, c.playbackState.value.activeTokenId)
    }

    @Test
    fun `end of passage completes it and the next passage continues without seeking`() = narrated() { c, player ->
        c.attach(narration)
        c.load(p0, "en", null, 0)
        c.play()
        advanceTimeBy(p0.tokens.size * 300L + 100)
        runCurrent()
        assertEquals(PlaybackStatus.COMPLETED, c.playbackState.value.status)
        assertTrue(player.playing)
        c.load(p1, "en", null, 0)
        c.play()
        assertEquals(1, player.seeks.size) // solo el salto inicial
        advanceTimeBy(1000 + 50)
        runCurrent()
        assertEquals(p1.id, c.playbackState.value.passageId)
        assertEquals(p1.tokens[0].id, c.playbackState.value.activeTokenId)
    }

    @Test
    fun `without auto continue the track pauses at the end of the passage`() = narrated(false) { c, player ->
        c.attach(narration)
        c.load(p0, "en", null, 0)
        c.play()
        advanceTimeBy(p0.tokens.size * 300L + 100)
        runCurrent()
        assertEquals(PlaybackStatus.COMPLETED, c.playbackState.value.status)
        assertFalse(player.playing)
    }

    @Test
    fun `read from here seeks to the exact word`() = narrated() { c, player ->
        c.attach(narration)
        c.load(p0, "en", null, 0)
        c.play()
        val target = p0.tokens[3]
        c.seekToOffset(target.rangeStart)
        assertEquals(500L + 3 * 300, player.seeks.last())
        assertEquals(target.rangeStart, c.playbackState.value.resumeOffset)
    }

    @Test
    fun `manual jump to another passage while playing seeks to it`() = narrated() { c, player ->
        c.attach(narration)
        c.load(p0, "en", null, 0)
        c.play()
        val p = book.passages.last()
        c.load(p, "en", null, 0)
        assertEquals(narration.timeline.passageRange(p.index)!!.first, player.seeks.last())
    }
}
