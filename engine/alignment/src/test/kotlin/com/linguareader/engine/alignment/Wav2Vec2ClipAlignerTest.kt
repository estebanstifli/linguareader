package com.linguareader.engine.alignment

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class Wav2Vec2ClipAlignerTest {

    private val models = File(System.getProperty("align.models", "../../.models"))

    @Test
    fun `no model for the language means unavailable and no timings`() = runBlocking {
        val a = Wav2Vec2ClipAligner(modelFor = { null })
        assertFalse(a.isAvailable("en-US"))
        assertNull(runCatching { a.align("/nada.wav", listOf("hi"), "en") }.getOrNull())
    }

    @Test
    fun `aligns a real clip through the port and releases the model`() = runBlocking {
        val dir = File(models, "aligners/w2v2-base-100h")
        val corpus = File(models, "align-corpus")
        assumeTrue(File(dir, "onnx/model_int8.onnx").exists() && File(corpus, "corpus.json").exists())
        val item = Json.parseToJsonElement(File(corpus, "corpus.json").readText()).jsonArray.map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == "ref_en_0" }
        val words = item["words"]!!.jsonArray.map { it.jsonPrimitive.content }
        val aligner = Wav2Vec2ClipAligner(modelFor = { lang -> if (lang == "en") AlignerModelFiles(File(dir, "onnx/model_int8.onnx"), File(dir, "vocab.json")) else null })
        assertTrue(aligner.isAvailable("en-GB"))
        val spans = aligner.align(File(corpus, item["wav"]!!.jsonPrimitive.content).path, words, "en-GB")!!
        assertEquals(words.size, spans.size)
        val truth = item["truth"]!!.jsonArray
        val meanErr = spans.map { abs(it.startMs / 1000.0 - (truth[it.wordIndex].jsonObject["start"]!!.jsonPrimitive.double + 0.04)) }.average()
        assertTrue("error medio $meanErr s", meanErr < 0.1)
        assertTrue(spans.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
        assertTrue(spans.all { it.confidence in 0f..1f })
        aligner.release()
    }
}
