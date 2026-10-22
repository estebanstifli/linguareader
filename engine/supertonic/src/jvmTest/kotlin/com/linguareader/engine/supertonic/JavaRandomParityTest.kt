package com.linguareader.engine.supertonic

import org.junit.Assert.assertEquals
import org.junit.Test

/** El ruido de Supertonic debe ser el mismo que con `java.util.Random`: los clips cacheados y los nuevos coinciden. */
class JavaRandomParityTest {

    @Test
    fun `same sequence as java util Random for several seeds`() {
        for (seed in listOf(0L, 1L, -1L, 42L, 123_456_789L, Long.MAX_VALUE, Long.MIN_VALUE, "hola".hashCode() * 31L + 3)) {
            val expected = java.util.Random(seed)
            val actual = JavaRandom(seed)
            repeat(1_000) { i ->
                assertEquals("seed=$seed i=$i", expected.nextDouble(), actual.nextDouble(), 0.0)
            }
        }
    }
}
