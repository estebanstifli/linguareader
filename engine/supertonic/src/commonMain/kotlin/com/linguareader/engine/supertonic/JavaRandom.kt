package com.linguareader.engine.supertonic

/**
 * Generador con la misma secuencia que `java.util.Random` (congruencial lineal de 48 bits), en Kotlin común.
 *
 * Supertonic parte de ruido gaussiano sembrado con la clave de caché; con este generador el audio de una
 * frase es bit a bit el mismo en todas las plataformas y el mismo que producía el motor cuando usaba
 * `java.util.Random` (los clips ya cacheados y los nuevos coinciden).
 */
internal class JavaRandom(seed: Long) {
    private var seed: Long = (seed xor MULTIPLIER) and MASK

    private fun next(bits: Int): Int {
        seed = (seed * MULTIPLIER + ADDEND) and MASK
        return (seed ushr (48 - bits)).toInt()
    }

    /** Igual que `java.util.Random.nextDouble()`: uniforme en [0, 1) con 53 bits. */
    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val ADDEND = 0xBL
        const val MASK = (1L shl 48) - 1
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
