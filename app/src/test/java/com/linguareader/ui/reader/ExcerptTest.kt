package com.linguareader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptTest {
    @Test
    fun shortTextIsKeptWhole() {
        assertEquals("Tom tiene ocho años.", excerptAround("Tom tiene ocho años.", 0.9f))
    }

    @Test
    fun longTextIsCutAroundThePositionWithEllipsesOnBothEnds() {
        val text = "Al principio de la mañana el viejo pescador salió del puerto con su barca azul, " +
            "y aunque el mar estaba tranquilo, sabía que la tormenta llegaría antes de la noche."
        val excerpt = excerptAround(text, 0.5f, max = 40)
        assertTrue(excerpt, excerpt.startsWith("…") && excerpt.endsWith("…"))
        assertTrue(excerpt, excerpt.length <= 42)
        // Corta por palabras: lo que queda entre los «…» está en el texto original.
        assertTrue(excerpt, text.contains(excerpt.removePrefix("…").removeSuffix("…")))
    }

    @Test
    fun startOfSentenceOnlyGetsTrailingEllipsis() {
        val text = "Una frase bastante larga que no cabe entera en la minitarjeta de traducción de la palabra."
        val excerpt = excerptAround(text, 0f, max = 30)
        assertTrue(excerpt, !excerpt.startsWith("…") && excerpt.endsWith("…"))
    }
}
