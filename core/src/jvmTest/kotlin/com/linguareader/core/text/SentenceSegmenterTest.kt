package com.linguareader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSegmenterTest {

    @Test
    fun `divide por puntuación final`() {
        assertEquals(
            listOf("It was late.", "Was anyone there?", "Nobody answered!"),
            SentenceSegmenter.split("It was late. Was anyone there? Nobody answered!", "en"),
        )
    }

    @Test
    fun `no corta tras abreviaturas ni iniciales`() {
        assertEquals(
            listOf("Mr. Smith met Dr. Jones at St. Paul's.", "They talked."),
            SentenceSegmenter.split("Mr. Smith met Dr. Jones at St. Paul's. They talked.", "en"),
        )
        assertEquals(1, SentenceSegmenter.split("J. R. R. Tolkien wrote it.", "en").size)
        assertEquals(1, SentenceSegmenter.split("La Sra. García llegó, p. ej. ayer, con el Dr. Ruiz.", "es").size)
    }

    @Test
    fun `un número suelto con punto no cierra frase`() {
        assertEquals(listOf("156. Charing Cross Road."), SentenceSegmenter.split("156. Charing Cross Road.", "en"))
        // Pero un número al final de una frase normal sí la cierra.
        assertEquals(2, SentenceSegmenter.split("He was born in 1775. She came later.", "en").size)
    }

    @Test
    fun `respeta diálogos e interrogaciones del español`() {
        val sentences = SentenceSegmenter.split("¿Vienes? —No lo sé. ¡Qué frío hace!", "es")
        assertEquals(listOf("¿Vienes?", "—No lo sé.", "¡Qué frío hace!"), sentences)
    }

    @Test
    fun `conserva diacríticos y elisiones francesas`() {
        val sentences = SentenceSegmenter.split("L'école est fermée. Aujourd'hui, c'est férié.", "fr")
        assertEquals(listOf("L'école est fermée.", "Aujourd'hui, c'est férié."), sentences)
    }

    @Test
    fun `parte frases desmesuradas por puntuación interna`() {
        val long = (1..120).joinToString(", ") { "palabra$it" } + "."
        val parts = SentenceSegmenter.split(long, "es")
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= SentenceSegmenter.MAX_SENTENCE_CHARS })
        assertEquals(Tokenizer.normalize(long), parts.joinToString(" "))
    }

    @Test
    fun `texto vacío no produce frases`() {
        assertEquals(emptyList<String>(), SentenceSegmenter.split("  \n ", "en"))
    }
}
