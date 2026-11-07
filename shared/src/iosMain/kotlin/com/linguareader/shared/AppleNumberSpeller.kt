package com.linguareader.shared

import com.linguareader.core.model.LanguageTags
import com.linguareader.core.text.speech.NumberSpeller
import com.linguareader.core.text.speech.NumberStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterSpellOutStyle
import kotlin.concurrent.AtomicReference

/**
 * Deletreo de números con `NSNumberFormatter` (`.spellOut`, reglas CLDR del sistema), el equivalente iOS de
 * `AndroidIcuNumberSpeller`. Foundation solo expone el cardinal: los ordinales se dejan como están (null) en vez de
 * leerlos mal, y los años en inglés se leen por pares («nineteen ninety-nine»), como `%spellout-numbering-year`.
 */
object AppleNumberSpeller : NumberSpeller {
    private val formatters = AtomicReference<Map<String, NSNumberFormatter>>(emptyMap())

    override fun spell(value: Long, languageTag: String, style: NumberStyle): String? {
        val lang = LanguageTags.primary(languageTag)
        return when (style) {
            NumberStyle.ORDINAL_MASCULINE, NumberStyle.ORDINAL_FEMININE -> null
            NumberStyle.YEAR -> if (lang == "en") englishYear(value) else cardinal(value, lang)
            else -> cardinal(value, lang)
        }
    }

    private fun englishYear(value: Long): String? {
        if (value !in 1100..1999 && value !in 2010..2099) return cardinal(value, "en")
        val high = value / 100
        val low = value % 100
        if (low == 0L) return cardinal(high, "en")?.let { "$it hundred" }
        val tail = if (low < 10) cardinal(low, "en")?.let { "oh $it" } else cardinal(low, "en")
        return listOfNotNull(cardinal(high, "en"), tail).takeIf { it.size == 2 }?.joinToString(" ")
    }

    private fun cardinal(value: Long, lang: String): String? {
        val words = formatter(lang).stringFromNumber(NSNumber(longLong = value)) ?: return null
        // Sin reglas para el idioma, Foundation devuelve cifras: mejor dejar el número como está.
        return words.takeIf { it.isNotBlank() && it.none(Char::isDigit) }?.replace('\u00AD', ' ')?.replace(Regex("\\s+"), " ")?.trim()
    }

    private fun formatter(lang: String): NSNumberFormatter {
        formatters.value[lang]?.let { return it }
        val f = NSNumberFormatter().apply {
            numberStyle = NSNumberFormatterSpellOutStyle
            locale = NSLocale(localeIdentifier = lang)
        }
        while (true) {
            val current = formatters.value
            if (formatters.compareAndSet(current, current + (lang to f))) return f
        }
    }
}
