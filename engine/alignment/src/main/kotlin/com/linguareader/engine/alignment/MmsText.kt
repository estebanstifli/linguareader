package com.linguareader.engine.alignment

import java.text.Normalizer

/**
 * Prepara el texto para el alfabeto CTC de MMS (letras latinas a–z y apóstrofo, minúsculas, sin
 * diacríticos). Equivale a la normalización `latin` de audio.cpp y a una romanización mínima
 * válida para inglés, francés y español. Las palabras sin ninguna letra (números, símbolos)
 * quedan sin tiempo y se resaltan con la frase.
 */
class MmsText(vocab: Map<String, Int>) {

    private val charIds: Map<Char, Int> = vocab.filterKeys { it.length == 1 }.mapKeys { it.key[0] }
    val blank: Int = vocab["<blank>"] ?: 0

    data class Prepared(
        val targets: IntArray,
        /** Índice (en la lista de palabras de entrada) de cada objetivo. */
        val targetToWord: IntArray,
        /** Palabras de entrada que tienen al menos una letra alineable. */
        val alignedWords: List<Int>,
    )

    fun normalize(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()
            .replace('’', '\'')
            .filter { it in charIds }

    fun prepare(words: List<String>): Prepared {
        val targets = ArrayList<Int>()
        val toWord = ArrayList<Int>()
        val aligned = ArrayList<Int>()
        words.forEachIndexed { i, w ->
            val n = normalize(w).trim('\'')
            if (n.isEmpty()) return@forEachIndexed
            aligned += i
            n.forEach { c -> targets += charIds.getValue(c); toWord += i }
        }
        return Prepared(targets.toIntArray(), toWord.toIntArray(), aligned)
    }
}
