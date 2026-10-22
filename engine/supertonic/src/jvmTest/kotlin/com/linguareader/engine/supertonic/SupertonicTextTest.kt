package com.linguareader.engine.supertonic

import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SupertonicTextTest {

    @Test
    fun `wraps with language tags and adds final period`() {
        assertEquals("<en>Hello world.</en>", SupertonicTextProcessor.preprocess("Hello   world", "en"))
        assertEquals("<en>Hi!</en>", SupertonicTextProcessor.preprocess("Hi!", "en"))
    }

    @Test
    fun `normalizes quotes dashes and spacing like the reference`() {
        assertEquals("<en>\"It's a kite\" - he says.</en>", SupertonicTextProcessor.preprocess("“It’s a kite” — he says .", "en"))
        assertEquals("<en>for example, this.</en>", SupertonicTextProcessor.preprocess("e.g., this", "en"))
    }

    @Test
    fun `french guillemets are kept as final punctuation`() {
        assertEquals("<fr>« C'est à toi? »</fr>", SupertonicTextProcessor.preprocess("« C'est à toi ? »", "fr").let {
            java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC)
        })
    }

    @Test
    fun `nfkd decomposes accents so the indexer knows them`() {
        val p = SupertonicTextProcessor.preprocess("é", "fr")
        assertTrue(p.contains("é"))
    }

    @Test
    fun `emojis are removed`() {
        assertEquals("<en>Good day.</en>", SupertonicTextProcessor.preprocess("Good day 😀", "en"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported language is rejected`() {
        SupertonicTextProcessor.preprocess("Hola", "xx")
    }

    @Test
    fun `unknown characters are dropped instead of sent as minus one`() {
        val indexer = LongArray(256) { -1 }
        "<en>Hi.</>".forEach { indexer[it.code] = it.code.toLong() }
        val enc = SupertonicTextProcessor(indexer).encode("Hi§", "en") // § no está en el índice
        assertEquals(1, enc.droppedChars)
        assertTrue(enc.ids.all { it >= 0 })
    }

    @Test
    fun `long text is chunked under the limit and short text is untouched`() {
        assertEquals(listOf("Short sentence."), SupertonicTextProcessor.chunk("Short sentence.", 300))
        val long = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val chunks = SupertonicTextProcessor.chunk(long, 120)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(long.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun `trim removes leading and trailing silence keeping a small margin`() {
        val sr = 1000 // 1 muestra = 1 ms
        val samples = FloatArray(2000)
        for (i in 500 until 1200) samples[i] = 0.5f // voz entre 500 y 1200 ms
        val out = AudioTrim.trim(samples, sr, marginMs = 40, windowMs = 10)
        assertTrue("longitud ${out.size}", out.size in 700..800)
        assertEquals(0f, out.first())
        assertTrue(out.any { it == 0.5f })
    }

    @Test
    fun `trim leaves fully silent audio untouched`() {
        val silent = FloatArray(500)
        assertEquals(500, AudioTrim.trim(silent, 1000).size)
    }

    @Test
    fun `wav header is valid pcm16 mono`() {
        val f = File.createTempFile("stwav", ".wav")
        WavWriter.write(f.toOkioPath(), floatArrayOf(0f, 1f, -1f, 2f), 44_100)
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 8, f.length().toInt())
        assertEquals("RIFF", String(f.readBytes(), 0, 4))
        assertEquals(44_100, b.getInt(24))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(32767, b.getShort(46).toInt())
        assertEquals(32767, b.getShort(50).toInt()) // recortado
        f.delete()
    }
}
