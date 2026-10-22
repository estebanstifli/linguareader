package com.linguareader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassageBuilderTest {

    private val passage = PassageBuilder.build(
        passageId = "ch1-p1",
        chapterId = "ch1",
        globalIndex = 0,
        languageTag = "en",
        sentences = listOf(
            SentenceInput("s1", "Tom is  eight.", mapOf("es" to "Tom tiene ocho.")),
            SentenceInput("s2", "\"It's windy!\" he says.", emptyMap()),
        ),
        image = null,
    )

    @Test
    fun `text is normalized and sentences are joined by one space`() {
        assertEquals("Tom is eight. \"It's windy!\" he says.", passage.text)
        assertEquals(0, passage.sentences[0].rangeStart)
        assertEquals("Tom is eight.".length, passage.sentences[0].rangeEnd)
        assertEquals(passage.sentences[0].rangeEnd + 1, passage.sentences[1].rangeStart)
    }

    @Test
    fun `token ranges always match the passage text`() {
        passage.tokens.forEach { token ->
            assertEquals(token.text, passage.text.substring(token.rangeStart, token.rangeEnd))
        }
        assertEquals(listOf("Tom", "is", "eight", "It's", "windy", "he", "says"), passage.tokens.map { it.text })
    }

    @Test
    fun `token ids are stable and sequential`() {
        assertEquals("ch1-p1:t0", passage.tokens.first().id)
        assertEquals("ch1-p1:t6", passage.tokens.last().id)
        val again = PassageBuilder.build("ch1-p1", "ch1", 0, "en",
            listOf(SentenceInput("s1", "Tom is  eight.", emptyMap()), SentenceInput("s2", "\"It's windy!\" he says.", emptyMap())), null)
        assertEquals(passage.tokens, again.tokens)
    }

    @Test
    fun `tokens know their sentence`() {
        assertEquals(listOf("s1", "s1", "s1", "s2", "s2", "s2", "s2"), passage.tokens.map { it.sentenceId })
    }

    @Test
    fun `offset index finds tokens and ignores punctuation`() {
        val idx = passage.text.indexOf("windy")
        assertEquals("windy", OffsetIndex.tokenAt(passage.tokens, idx + 2)?.text)
        assertNull(OffsetIndex.tokenAt(passage.tokens, passage.text.indexOf('!')))
        assertEquals("It's", OffsetIndex.firstTokenIn(passage.tokens, passage.text.indexOf('"'), passage.text.indexOf('"') + 6)?.text)
        assertEquals("s2", OffsetIndex.sentenceAt(passage.sentences, idx)?.id)
    }

    @Test
    fun `translation block requires every sentence translated`() {
        assertNull(passage.translationBlock("es"))
    }
}
