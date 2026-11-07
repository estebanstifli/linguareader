package com.linguareader.engine.supertonic

import com.linguareader.core.text.TokenSpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SupertonicWordTimingsTest {
    private val processor = SupertonicTextProcessor(LongArray(65536) { it.toLong() })

    @Test fun `frozen decoder agrees with Python goldens in eight scripts and languages`() {
        val fixture = javaClass.getResourceAsStream("/word-timing-golden.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = Json.parseToJsonElement(fixture).jsonObject["cases"]!!.jsonArray
        for (element in cases) {
            val row = element.jsonObject
            val id = row["id"]!!.jsonPrimitive.content
            val text = row["text"]!!.jsonPrimitive.content
            val lang = row["lang"]!!.jsonPrimitive.content
            val expected = row["words"]!!.jsonArray.map { it.jsonObject }
            // The Japanese lab fixture has explicit lexical units. Production retains the reader's
            // tokenizer contract, which is tested separately and does not claim general segmentation.
            val encoded = processor.encode(text, lang).copy(words = expected.map {
                TokenSpan(it["start"]!!.jsonPrimitive.int, it["end"]!!.jsonPrimitive.int)
            })
            assertEquals(id, row["characters"]!!.jsonPrimitive.int, encoded.ids.size)
            val sr = row["sampleRate"]!!.jsonPrimitive.int
            val actual = SupertonicWordTimings.decodeEnvelope(
                row["attention"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                row["frames"]!!.jsonPrimitive.int, encoded, row["samples"]!!.jsonPrimitive.int,
                sr, row["hopSamples"]!!.jsonPrimitive.int,
                row["rms"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
            )
            assertEquals("$id complete word coverage", expected.size, actual.size)
            actual.zip(expected).forEach { (word, reference) ->
                assertTrue("$id start: $word", abs(word.startSample / sr.toDouble() - reference["startSeconds"]!!.jsonPrimitive.double) <= 1.1 / sr)
                assertTrue("$id end: $word", abs(word.endSample / sr.toDouble() - reference["endSeconds"]!!.jsonPrimitive.double) <= 1.1 / sr)
                assertEquals(id, reference["confidence"]!!.jsonPrimitive.float, word.confidence, 0.0001f)
            }
        }
    }

    @Test fun `normalization preserves original offsets through canonical mark reordering and astral characters`() {
        val text = "😀 a\u0301\u0323 किताब 안녕"
        val encoded = processor.encode(text, "hi")
        assertTrue(encoded.timingSafe)
        val below = encoded.codePoints.indexOf(0x0323)
        val acute = encoded.codePoints.indexOf(0x0301)
        assertTrue(below < acute)
        assertEquals(text.indexOf('\u0323'), encoded.sourceStarts[below])
        assertEquals(text.indexOf('\u0301'), encoded.sourceStarts[acute])
        val hindi = encoded.words.single { text.substring(it.start, it.end) == "किताब" }
        assertEquals(5, encoded.sourceStarts.indices.count { encoded.sourceStarts[it] >= hindi.start && encoded.sourceEnds[it] <= hindi.end && encoded.sourceStarts[it] >= 0 })
        val hangul = text.indexOf('안')
        assertEquals(3, encoded.sourceStarts.count { it == hangul })
        assertEquals(text.indexOf('a'), encoded.words.first().start)
    }

    @Test fun `ambiguous expansions and dropped lexical characters disable timings but preserve synthesis encoding`() {
        assertFalse(processor.encode("Write a@b.", "en").timingSafe)
        assertFalse(processor.encode("Use e.g., this.", "en").timingSafe)
        val sparse = LongArray(128) { it.toLong() }
        val unknown = SupertonicTextProcessor(sparse).encode("Hi Ж.", "en")
        assertFalse(unknown.timingSafe)
        assertEquals(1, unknown.droppedChars)
        assertTrue(unknown.ids.all { it >= 0 })
    }

    @Test fun `Arabic semicolon is replaced without shifting following UTF16 words`() {
        val text = "كتاب؛ جديد"
        val encoded = processor.encode(text, "ar")
        val semicolon = encoded.codePoints.indexOf(';'.code)
        assertTrue(encoded.timingSafe)
        assertEquals(text.indexOf('؛'), encoded.sourceStarts[semicolon])
        assertEquals(text.indexOf('ج'), encoded.words.last().start)
        assertTrue(SupertonicTextProcessor.preprocess(text, "ar").contains(';'))
    }

    @Test fun `chunks are exact source slices with repeated whitespace and surrogate-safe hard limits`() {
        val text = "  First   sentence.\n\n" + "किताब ".repeat(30) + "最後の言葉".repeat(20)
        val chunks = SupertonicTextProcessor.chunkWithOffsets(text, 45)
        assertTrue(chunks.size > 3)
        chunks.forEach {
            assertEquals(text.substring(it.start, it.end), it.text)
            assertTrue(it.text.length <= 45)
            assertFalse(it.text.first().isLowSurrogate())
            assertFalse(it.text.last().isHighSurrogate())
        }
        assertEquals(text.filterNot { it.isWhitespace() }, chunks.joinToString("") { it.text }.filterNot { it.isWhitespace() })
    }

    @Test fun `trimming shifts both boundaries and original text offsets together`() {
        val samples = FloatArray(2000) { if (it in 500 until 1200) .5f else 0f }
        val original = SupertonicAudio(samples, 1000, listOf(
            SupertonicWordTiming(0, 3, 500, 750, .8f),
            SupertonicWordTiming(4, 8, 900, 1200, .7f),
        ))
        val out = SupertonicEngine.trimWithTimings(original, 11)
        assertEquals(780, out.samples.size)
        assertEquals(SupertonicWordTiming(11, 14, 40, 290, .8f), out.wordTimings[0])
        assertEquals(SupertonicWordTiming(15, 19, 440, 740, .7f), out.wordTimings[1])
        // No speech may be invented by clamping an entirely trimmed word to an empty interval.
        assertTrue(SupertonicEngine.trimWithTimings(SupertonicAudio(samples, 1000,
            listOf(SupertonicWordTiming(0, 1, 100, 200, 1f))), 0).wordTimings.isEmpty())
    }

    @Test fun `malformed attention and silence fail safely without fabricated word marks`() {
        val encoded = processor.encode("A word", "en")
        val shape = 10 * encoded.ids.size
        val samples = FloatArray(1000) { .1f }
        assertTrue(SupertonicWordTimings.decode(FloatArray(shape - 1), 10, encoded, samples, 1000, 100).isEmpty())
        assertTrue(SupertonicWordTimings.decode(FloatArray(shape) { Float.NaN }, 10, encoded, samples, 1000, 100).isEmpty())
        assertTrue(SupertonicWordTimings.decode(FloatArray(shape), 10, encoded, samples, 1000, 100).isEmpty())
        val tagsOnly = FloatArray(shape) { if (it % encoded.ids.size == 0) 1f else 0f }
        assertTrue(SupertonicWordTimings.decode(tagsOnly, 10, encoded, samples, 1000, 100).isEmpty())
        assertTrue(SupertonicWordTimings.decode(FloatArray(shape) { 1f }, 10, encoded, FloatArray(1000), 1000, 100).isEmpty())
    }
}
