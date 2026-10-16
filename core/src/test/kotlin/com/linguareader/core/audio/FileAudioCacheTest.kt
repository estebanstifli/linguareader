package com.linguareader.core.audio

import com.linguareader.core.engine.AudioAsset
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileAudioCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun cache(max: Long = 1_000) = FileAudioCache(tmp.root, max, clock = { now })
    private fun key(n: Int) = "k".repeat(8) + n

    @Test
    fun `stores and returns entries`() {
        val c = cache()
        assertNull(c.get(key(1)))
        c.put(key(1)) { it.writeBytes(ByteArray(10)) }
        assertEquals(10L, c.get(key(1))!!.length())
    }

    @Test
    fun `evicts least recently used when over the limit`() {
        val c = cache(max = 250)
        c.put(key(1)) { it.writeBytes(ByteArray(100)) }; now += 10
        c.put(key(2)) { it.writeBytes(ByteArray(100)) }; now += 10
        c.get(key(1)); now += 10 // 1 pasa a ser el más reciente
        c.put(key(3)) { it.writeBytes(ByteArray(100)) }
        assertNotNull(c.get(key(1)))
        assertNull(c.get(key(2)))
        assertNotNull(c.get(key(3)))
        assertTrue(c.sizeBytes() <= 250)
    }

    @Test
    fun `failed writer leaves no partial entry`() {
        val c = cache()
        runCatching { c.put(key(1)) { it.writeBytes(ByteArray(5)); error("boom") } }
        assertNull(c.get(key(1)))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects keys that could escape the directory`() {
        cache().get("../../etc/passwd")
    }

    private class CountingSynth : FileSpeechSynthesizer {
        var calls = 0
        var fail = false
        override val info = TtsEngineInfo("st", "ST", false, false, true, true)
        override val modelVersion = "rev1"
        override suspend fun synthesizeTo(request: SpeechRequest, target: File): SynthesisOutcome {
            calls++
            if (fail) return SynthesisOutcome.Failed(SpeechResult.Failure(EngineUnavailableReason.SYNTHESIS_ERROR, "x"))
            target.writeText(request.text)
            return SynthesisOutcome.Written(500)
        }
        override suspend fun listVoices(languageTag: String) = emptyList<VoiceInfo>()
        override suspend fun availability(languageTag: String, voiceId: String?) = EngineReadiness.Ready
        override fun release() {}
    }

    @Test
    fun `caching synthesizer only synthesizes on miss and keys include every parameter`() = runTest {
        val engine = CountingSynth()
        val synth = CachingSpeechSynthesizer(engine, cache(max = 10_000))
        val req = SpeechRequest("Hello there.", "en", "F1", 1.0f, mapOf("steps" to "5"))
        val a = synth.synthesize(req) as SpeechResult.Success
        val b = synth.synthesize(req) as SpeechResult.Success
        assertEquals(1, engine.calls)
        assertEquals(a.audio.path, b.audio.path)
        synth.synthesize(req.copy(voiceId = "M1"))
        synth.synthesize(req.copy(speed = 1.2f))
        synth.synthesize(req.copy(parameters = mapOf("steps" to "8")))
        synth.synthesize(req.copy(languageTag = "fr"))
        assertEquals(5, engine.calls)
    }

    @Test
    fun `caching synthesizer does not cache failures`() = runTest {
        val engine = CountingSynth().apply { fail = true }
        val synth = CachingSpeechSynthesizer(engine, cache())
        val req = SpeechRequest("Hi.", "en", null, 1.0f)
        assertTrue(synth.synthesize(req) is SpeechResult.Failure)
        engine.fail = false
        assertTrue(synth.synthesize(req) is SpeechResult.Success)
        assertEquals(2, engine.calls)
        assertTrue(synth.synthesize(req) is SpeechResult.Success)
        assertEquals(2, engine.calls)
        assertTrue(AudioAsset::class.java.isInstance((synth.synthesize(req) as SpeechResult.Success).audio))
    }
}
