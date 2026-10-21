package com.linguareader.engine.alignment

import java.text.Normalizer

/**
 * Texto para vocabularios wav2vec2 de Hugging Face basados en caracteres: inglés en mayúsculas
 * (facebook/wav2vec2-base-100h: A–Z, `'`, blanco `<pad>`) o minúsculas con acentos
 * (bofenghuang/asr-wav2vec2-ctc-french: blanco `[PAD]`; facebook/wav2vec2-base-10k-voxpopuli-ft-fr/-es:
 * blanco `<pad>`, sin apóstrofo). `|` es solo separador de palabras.
 * Se conservan los diacríticos que el vocabulario tenga; los que no, se eliminan. Los guiones y
 * cualquier símbolo fuera del alfabeto no se alinean. Equivalente a [MmsText] para estos alfabetos.
 */
class Wav2Vec2Text(vocab: Map<String, Int>) {

    private val charIds: Map<Char, Int> = vocab.filterKeys { it.length == 1 && (it[0].isLetter() || it == "'") }.mapKeys { it.key[0] }
    private val upper = charIds.keys.any { it.isUpperCase() } && charIds.keys.none { it.isLowerCase() }
    val blank: Int = vocab["<pad>"] ?: vocab["[PAD]"] ?: 0

    private fun fold(c: Char) = if (upper) c.uppercaseChar() else c.lowercaseChar()

    fun normalize(word: String): String {
        val sb = StringBuilder()
        val nfc: String = Normalizer.normalize(word, Normalizer.Form.NFC).replace('’', '\'')
        for (c in nfc.toCharArray()) {
            val f = fold(c)
            if (f in charIds) { sb.append(f); continue }
            // Sin ese carácter en el vocabulario: probar sin diacrítico.
            val base = Normalizer.normalize(f.toString(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            base.forEach { b -> if (fold(b) in charIds) sb.append(fold(b)) }
        }
        return sb.toString()
    }

    fun prepare(words: List<String>): MmsText.Prepared {
        val targets = ArrayList<Int>()
        val toWord = ArrayList<Int>()
        val aligned = ArrayList<Int>()
        words.forEachIndexed { i, w ->
            val n = normalize(w).trim('\'')
            if (n.isEmpty()) return@forEachIndexed
            aligned += i
            n.forEach { c -> targets += charIds.getValue(c); toWord += i }
        }
        return MmsText.Prepared(targets.toIntArray(), toWord.toIntArray(), aligned)
    }
}
