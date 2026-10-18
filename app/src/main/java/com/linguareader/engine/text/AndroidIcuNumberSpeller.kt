package com.linguareader.engine.text

import android.icu.text.MessageFormat
import android.icu.util.ULocale
import com.linguareader.core.text.speech.SpellOut
import com.linguareader.core.text.speech.SpellOutNumberSpeller

/**
 * Deletreo de números con el ICU del sistema (API 24+), sin añadir nada al APK. `RuleBasedNumberFormat`
 * no es API pública en Android: se usan las mismas reglas SPELLOUT de CLDR a través de `MessageFormat`
 * (`{0,spellout,%regla}`). Como no se pueden listar las reglas, [SpellOutNumberSpeller] las detecta probando.
 */
val AndroidIcuNumberSpeller = SpellOutNumberSpeller { lang ->
    val locale = ULocale.forLanguageTag(lang)
    val formats = HashMap<String, MessageFormat>()
    object : SpellOut {
        override val ruleSetNames: Set<String>? = null
        override fun format(value: Long, ruleSet: String?): String {
            val pattern = if (ruleSet == null) "{0,spellout}" else "{0,spellout,$ruleSet}"
            return formats.getOrPut(pattern) { MessageFormat(pattern, locale) }.format(arrayOf<Any>(value))
        }
    }
}
