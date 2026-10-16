package com.linguareader.core.alignment

import com.linguareader.core.audio.AudioCacheKey
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.TimedToken
import com.linguareader.core.testing.SampleBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentTest {

    private val passage = SampleBooks.english.passages.first()
    private fun timedAll(step: Long = 300) = passage.tokens.mapIndexed { i, t -> TimedToken(t.id, i * step, i * step + step - 50) }

    @Test
    fun `complete monotonic alignment is word level`() {
        assertEquals(AlignmentQuality.WordLevel, AlignmentValidator.validate(passage, timedAll()))
    }

    @Test
    fun `non monotonic alignment falls back to sentence`() {
        val t = timedAll().toMutableList().also { it.add(1, it.removeAt(3)) }
        val q = AlignmentValidator.validate(passage, t)
        assertTrue(q is AlignmentQuality.SentenceOnly)
    }

    @Test
    fun `low coverage falls back to sentence`() {
        val q = AlignmentValidator.validate(passage, timedAll().take(2))
        assertTrue((q as AlignmentQuality.SentenceOnly).reasons.any { it.startsWith("cobertura") })
    }

    @Test
    fun `low confidence falls back to sentence`() {
        val q = AlignmentValidator.validate(passage, timedAll().map { it.copy(confidence = 0.2f) })
        assertTrue(q is AlignmentQuality.SentenceOnly)
    }

    @Test
    fun `empty alignment is never word level`() {
        assertTrue(AlignmentValidator.validate(passage, emptyList()) is AlignmentQuality.SentenceOnly)
    }

    @Test
    fun `timeline lookup uses binary search semantics`() {
        val timed = timedAll()
        val timeline = AlignmentTimeline(timed)
        assertNull(timeline.tokenAt(-1))
        assertEquals(timed[0].tokenId, timeline.tokenAt(0)?.tokenId)
        assertEquals(timed[0].tokenId, timeline.tokenAt(260)?.tokenId) // hueco: se mantiene la anterior
        assertEquals(timed[2].tokenId, timeline.tokenAt(600)?.tokenId)
        assertEquals(timed.last().tokenId, timeline.tokenAt(timed.last().endMs)?.tokenId)
        assertNull(timeline.tokenAt(timed.last().endMs + 1))
    }

    @Test
    fun `audio cache key changes with every generation parameter`() {
        val base = SpeechRequest("Tom wakes up.", "en-US", "voice-a", 1.0f, mapOf("steps" to "5"))
        val k = AudioCacheKey.of(base, "supertonic", "1.0")
        assertEquals(k, AudioCacheKey.of(base.copy(text = "Tom   wakes up."), "supertonic", "1.0"))
        assertEquals(k, AudioCacheKey.of(base.copy(parameters = linkedMapOf("steps" to "5")), "supertonic", "1.0"))
        listOf(
            AudioCacheKey.of(base.copy(text = "Tom wakes up!"), "supertonic", "1.0"),
            AudioCacheKey.of(base.copy(languageTag = "en-GB"), "supertonic", "1.0"),
            AudioCacheKey.of(base.copy(voiceId = "voice-b"), "supertonic", "1.0"),
            AudioCacheKey.of(base.copy(speed = 1.25f), "supertonic", "1.0"),
            AudioCacheKey.of(base.copy(parameters = mapOf("steps" to "8")), "supertonic", "1.0"),
            AudioCacheKey.of(base, "supertonic", "1.1"),
            AudioCacheKey.of(base, "android", "1.0"),
        ).forEach { assertNotEquals(k, it) }
    }
}
