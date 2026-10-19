package com.linguareader.core.alignment

import com.linguareader.core.audio.SpokenMark
import com.linguareader.core.audio.WavSilenceTrim
import com.linguareader.core.model.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MarkTimingsTest {

    private fun tokens(vararg words: String): List<Token> {
        var pos = 0
        return words.mapIndexed { i, w -> Token("t$i", i, w, pos, pos + w.length, "s").also { pos += w.length + 1 } }
    }

    @Test
    fun `engine marks become word times and the last word ends with the clip`() {
        val t = tokens("Tom", "is", "eight")
        val spoken = "Tom is eight."
        val marks = listOf(SpokenMark(0, 3, 50), SpokenMark(4, 6, 400), SpokenMark(7, 12, 600))
        val timed = MarkTimings.toTimed(t, MarkTimings.spokenSpans(spoken, t.map { it.text }), marks, 1200)
        assertEquals(listOf(50L to 400L, 400L to 600L, 600L to 1200L), timed.map { it.startMs to it.endMs })
    }

    @Test
    fun `a number is found by its spoken form`() {
        val t = tokens("I", "am", "42")
        val spoken = "I am forty-two"
        val spans = MarkTimings.spokenSpans(spoken, listOf("I", "am", "forty-two"))
        assertEquals(5 until 14, spans[2])
        // El motor marca «forty» y «two» por separado: cuenta la primera marca del token.
        val marks = listOf(SpokenMark(0, 1, 0), SpokenMark(2, 4, 200), SpokenMark(5, 10, 500), SpokenMark(11, 14, 800))
        val timed = MarkTimings.toTimed(t, spans, marks, 1000)
        assertEquals(500L, timed.single { it.tokenId == "t2" }.startMs)
    }

    @Test
    fun `an unmarked word keeps room between its neighbours and backwards marks are dropped`() {
        val t = tokens("aaaa", "bbbb", "cccc")
        val spans = MarkTimings.spokenSpans("aaaa bbbb cccc", t.map { it.text })
        val marks = listOf(SpokenMark(0, 4, 100), SpokenMark(10, 14, 900), SpokenMark(5, 9, 300))
        val timed = MarkTimings.toTimed(t, spans, marks, 1500)
        assertEquals(listOf("t0", "t2"), timed.map { it.tokenId })
        assertEquals(500L, timed[0].endMs) // la mitad del tramo 100–900 queda para «bbbb»
        val filled = GapFiller.fill(t, timed, 1500)
        assertTrue(filled.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }

    @Test
    fun `wav silence is trimmed in place and the removed lead is reported`() {
        val rate = 1000
        val samples = ShortArray(1000) { i -> if (i in 300 until 600) 10_000 else 0 }
        val f = File.createTempFile("trim", ".wav")
        try {
            val buf = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            buf.put("data".toByteArray()).putInt(samples.size * 2)
            samples.forEach { buf.putShort(it) }
            f.writeBytes(buf.array())
            val lead = WavSilenceTrim.trim(f)
            assertEquals(260L, lead) // 300 ms de silencio menos 40 de margen
            assertEquals(380L, GapFiller.wavDurationMs(f)) // 300 de voz + 2 × 40 de margen
            assertNull(WavSilenceTrim.trim(File.createTempFile("bad", ".wav").apply { writeText("not a wav"); deleteOnExit() }))
        } finally {
            f.delete()
        }
    }
}
