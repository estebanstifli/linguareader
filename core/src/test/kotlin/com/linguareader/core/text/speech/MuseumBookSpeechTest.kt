package com.linguareader.core.text.speech

import com.linguareader.core.content.BookManifestParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** El libro de prueba de normalización («A Day at the Museum», en content/test-books): forma hablada esperada de cada frase. */
class MuseumBookSpeechTest {

    private val book = BookManifestParser.parse(File("../content/test-books/en-a2-a-day-at-the-museum/manifest.json").readText())
    private val normalizer = RuleBasedSpeechNormalizer(Icu4jNumberSpeller)

    private val expected = listOf(
        "On Monday, March third, twenty twenty-five, mister and missus Green took their two children to the city museum.",
        "Their daughter, Amy, is nine years old, and their son, Ben, is twelve.",
        "The bus left at eight fifteen A M from number forty-two Park Road.",
        "The trip took forty-five minutes and cost twelve dollars and fifty per person.",
        "The museum opened in eighteen eighty-seven and has more than twenty-five thousand objects.",
        "Last year, one million two hundred fifty thousand people visited it.",
        "About seventy-five percent of the visitors are students.",
        "A ticket for adults costs eight pounds, but children under sixteen go in for free.",
        "In Room twelve, doctor Patel showed them a painting of King Henry the eighth.",
        "Next to it was a photo of Queen Elizabeth the second from nineteen fifty-three.",
        "Amy read Chapter four of the guide, that is the part about World War two.",
        "They saw swords, coins, old books, et cetera.",
        "Ben liked the third floor best, for example the room with maps from the seventeenth century.",
        "At twelve thirty P M they had lunch in the café on the second floor.",
        "It was Ben versus Amy in a quiz, and Ben won seven to five.",
        "Outside, it was cold: only four point five degrees.",
        "They walked home along saint James Street and arrived at four o'clock P M.",
        "The museum's phone number is five five five, oh one nine nine.",
        "Their next visit is on the first of June, for Part two of the trip.",
        "\"See you on June twenty-first!\" said missus Green.",
    )

    @Test
    fun `every sentence of the test book is spoken as expected`() {
        val actual = book.passages.flatMap { p -> p.sentences.map { normalizer.normalize(p, it, book.learningLanguage).spoken } }
        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    @Test
    fun `every token keeps a spoken entry for the aligner`() {
        for (p in book.passages) for (s in p.sentences) {
            val r = normalizer.normalize(p, s, book.learningLanguage)
            assertEquals("${p.id}/${s.id}", p.tokensOf(s).size, r.tokenSpoken!!.size)
        }
    }
}
