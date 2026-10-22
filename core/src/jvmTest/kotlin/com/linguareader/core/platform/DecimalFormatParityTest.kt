package com.linguareader.core.platform

import com.linguareader.core.text.speech.fillTemplate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * [formatFixed] sustituye a `String.format(Locale.ROOT, "%.2f", speed)` en la clave de caché de audio: si el
 * resultado cambiara, todos los clips ya generados dejarían de encontrarse. Se contrasta con el formateador de Java.
 */
class DecimalFormatParityTest {

    private fun java(value: Float, decimals: Int) = "%.${decimals}f".format(Locale.ROOT, value)

    @Test
    fun `matches java formatter for the speeds the app uses`() {
        val speeds = listOf(0.5f, 0.6f, 0.7f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.3f, 1.4f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        for (s in speeds) assertEquals("speed $s", java(s, 2), formatFixed(s.toDouble(), 2))
    }

    @Test
    fun `matches java formatter on rounding edge cases`() {
        val values = listOf(0.145f, 0.125f, 1.125f, 1.005f, 2.675f, 0.995f, 9.995f, 99.995f, 0.0f, -0.001f, -1.5f, 123456.789f, 1e-5f, 1e7f, 0.3f, 0.7f)
        for (v in values) {
            assertEquals("value $v", java(v, 2), formatFixed(v.toDouble(), 2))
            assertEquals("value $v", java(v, 0), formatFixed(v.toDouble(), 0))
        }
    }

    @Test
    fun `matches java formatter on random floats`() {
        val random = Random(42)
        repeat(20_000) {
            val v = (random.nextFloat() * 20f - 5f).let { if (random.nextInt(4) == 0) (it * 100).toInt() / 100f else it }
            assertEquals("value $v", java(v, 2), formatFixed(v.toDouble(), 2))
        }
    }

    @Test
    fun `hex and template helpers behave like their java counterparts`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.joinToString("") { "%02x".format(it) }, bytes.toHexLower())
        assertEquals("%1\$s %2\$s con %3\$s".format("dos", "euros", "cinco"), "%1\$s %2\$s con %3\$s".fillTemplate("dos", "euros", "cinco"))
        assertEquals("the %s".format("third"), "the %s".fillTemplate("third"))
    }
}
