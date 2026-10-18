package com.linguareader.engine.alignment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class CtcAlignerTest {

    private val vocab = mapOf("<blank>" to 0, "a" to 1, "b" to 2, "'" to 3, "e" to 4, "l" to 5)

    /** Log-probs donde cada fotograma "grita" la etiqueta indicada. */
    private fun emissions(labels: IntArray, classes: Int): FloatArray {
        val d = FloatArray(labels.size * classes) { ln(0.01f) }
        labels.forEachIndexed { f, l -> d[f * classes + l] = ln(0.9f) }
        return d
    }

    @Test
    fun `viterbi follows clear emissions including repeated letters`() {
        // "ab" + pausa + "bb" (b repetida exige un blanco entre ellas)
        val labels = intArrayOf(0, 1, 1, 2, 0, 0, 2, 0, 2, 0)
        val r = CtcForcedAligner.align(emissions(labels, 6), labels.size, 6, intArrayOf(1, 2, 2, 2), 0)
        assertArrayEquals(labels, r.labelPath)
    }

    @Test
    fun `word spans are tight to the letters and exclude silences`() {
        val labels = intArrayOf(0, 1, 1, 2, 0, 0, 0, 0, 2, 2, 0)
        val r = CtcForcedAligner.align(emissions(labels, 6), labels.size, 6, intArrayOf(1, 2, 2), 0)
        val spans = CtcForcedAligner.wordSpans(r, intArrayOf(1, 2, 2), intArrayOf(0, 0, 1), 0)
        assertEquals(2, spans.size)
        assertEquals(1, spans[0].startFrame) // empieza en la voz, no en el silencio inicial
        assertEquals(3, spans[0].endFrame)   // termina en su última letra
        assertEquals(8, spans[1].startFrame) // el hueco 4..7 no pertenece a ninguna palabra
        assertEquals(9, spans[1].endFrame)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too short audio is rejected`() {
        CtcForcedAligner.align(emissions(intArrayOf(1, 2), 6), 2, 6, intArrayOf(1, 2, 2), 0)
    }

    @Test
    fun `text is romanized lowercased and punctuation free`() {
        val t = MmsText(vocab)
        assertEquals("ale", t.normalize("Âlé!"))
        assertEquals("l'", t.normalize("l’"))
        val p = t.prepare(listOf("Él", "123", "abe"))
        assertEquals(listOf(0, 2), p.alignedWords) // "123" no tiene letras: sin tiempo
        assertArrayEquals(intArrayOf(4, 5, 1, 2, 4), p.targets)
        assertArrayEquals(intArrayOf(0, 0, 2, 2, 2), p.targetToWord)
    }

    @Test
    fun `a spoken form of several words aligns as one token`() {
        // El normalizador manda «cuarenta y dos» para el token «42»: todas sus letras cuentan para ese token.
        val p = MmsText(vocab).prepare(listOf("el", "ab ab", "be"))
        assertEquals(listOf(0, 1, 2), p.alignedWords)
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 1, 1, 2, 2), p.targetToWord)
        val w = Wav2Vec2Text(mapOf("<pad>" to 0, "|" to 4, "A" to 7, "B" to 5))
        assertEquals("ABAB", w.normalize("ab ab"))
    }

    @Test
    fun `resampler keeps duration and tone`() {
        val sr = 44_100
        val x = FloatArray(sr) { kotlin.math.sin(2 * Math.PI * 440 * it / sr).toFloat() }
        val y = Resampler.resample(x, sr, 16_000)
        assertEquals(16_000, y.size)
        // cruces por cero ≈ 2 × 440
        val crossings = (1 until y.size).count { (y[it - 1] < 0) != (y[it] < 0) }
        assertTrue("cruces=$crossings", crossings in 870..890)
    }

    @Test
    fun `wav2vec2 english vocabulary is uppercase and strips accents`() {
        val t = Wav2Vec2Text(mapOf("<pad>" to 0, "|" to 4, "A" to 7, "E" to 5, "L" to 15, "'" to 27))
        assertEquals(0, t.blank)
        assertEquals("ELA'", t.normalize("Élà’"))
    }

    @Test
    fun `wav2vec2 french vocabulary keeps accents, drops hyphens and uses PAD as blank`() {
        val t = Wav2Vec2Text(mapOf("|" to 0, "[PAD]" to 47, "-" to 2, "a" to 3, "m" to 15, "r" to 20, "e" to 7, "è" to 33, "ï" to 38, "g" to 9, "d" to 6, "n" to 16))
        assertEquals(47, t.blank)
        assertEquals("mère", t.normalize("Mère"))
        assertEquals("grandmère", t.normalize("grand-mère"))
        assertEquals("a", t.normalize("à")) // à no está en este vocabulario → a
        val p = t.prepare(listOf("--", "mère"))
        assertEquals(listOf(1), p.alignedWords)
    }
}
