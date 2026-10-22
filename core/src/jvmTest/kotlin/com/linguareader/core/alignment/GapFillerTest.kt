package com.linguareader.core.alignment

import okio.Path.Companion.toOkioPath
import com.linguareader.core.engine.TimedToken
import com.linguareader.core.model.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GapFillerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun tokens(vararg words: String) = words.mapIndexed { i, w -> Token("t$i", i, w, 0, w.length, "s0") }
    private fun real(i: Int, start: Long, end: Long) = TimedToken("t$i", start, end, 0.9f)

    private fun List<TimedToken>.span(i: Int) = first { it.tokenId == "t$i" }.let { it.startMs to it.endMs }

    @Test
    fun `leading word starts at the beginning of the audio and ends where the next starts`() {
        val out = GapFiller.fill(tokens("Uno", "dos", "tres"), listOf(real(1, 500, 800), real(2, 900, 1300)), 1500)
        assertEquals(40L to 500L, out.span(0))
        assertTrue(out.first().estimated)
        assertFalse(out[1].estimated)
    }

    @Test
    fun `middle word fills the gap between its neighbours`() {
        val out = GapFiller.fill(tokens("Uno", "dos", "tres"), listOf(real(0, 40, 400), real(2, 800, 1200)), 1300)
        assertEquals(400L to 800L, out.span(1))
    }

    @Test
    fun `several consecutive gaps share the time proportionally to their letters`() {
        // «ab» (2 letras) y «abcd» (4 letras) entre 400 y 1000 → 400..600 y 600..1000
        val out = GapFiller.fill(tokens("x", "ab", "abcd", "y"), listOf(real(0, 40, 400), real(3, 1000, 1200)), 1300)
        assertEquals(400L to 600L, out.span(1))
        assertEquals(600L to 1000L, out.span(2))
    }

    @Test
    fun `trailing words extend to the end of the audio`() {
        val out = GapFiller.fill(tokens("Uno", "dos"), listOf(real(0, 40, 500)), 1000)
        assertEquals(500L to 960L, out.span(1))
    }

    @Test
    fun `a sentence without any signal spans the whole audio`() {
        val out = GapFiller.fill(tokens("aa", "bbbb", "aa"), emptyList(), 1040)
        assertEquals(40L to 280L, out.span(0))
        assertEquals(280L to 760L, out.span(1))
        assertEquals(760L to 1000L, out.span(2))
        assertTrue(out.all { it.estimated })
    }

    @Test
    fun `neighbours that touch leave an empty interval, never going backwards`() {
        val out = GapFiller.fill(tokens("a", "gap", "b"), listOf(real(0, 40, 700), real(2, 700, 900)), 1000)
        assertEquals(3, out.size)
        assertTrue(out.zipWithNext().all { (x, y) -> y.startMs >= x.startMs && x.endMs >= x.startMs })
        assertEquals(700L to 700L, out.span(1))
    }

    @Test
    fun `fully aligned sentence is returned unchanged`() {
        val r = listOf(real(0, 40, 300), real(1, 350, 700))
        assertEquals(r, GapFiller.fill(tokens("a", "b"), r, 800))
    }

    @Test
    fun `wav duration comes from the header`() {
        val sr = 44_100
        val samples = sr / 2 // 0,5 s
        val f = File(tmp.root, "x.wav")
        val b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sr).putInt(sr * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        f.writeBytes(b.array())
        assertEquals(500L, GapFiller.wavDurationMs(f.toOkioPath()))
        assertEquals(null, GapFiller.wavDurationMs(File(tmp.root, "no-existe.wav").toOkioPath()))
    }
}
