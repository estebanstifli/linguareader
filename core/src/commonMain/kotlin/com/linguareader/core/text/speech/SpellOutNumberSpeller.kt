package com.linguareader.core.text.speech

import com.linguareader.core.model.LanguageTags
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Formateador de deletreo de un idioma (reglas SPELLOUT de ICU/CLDR). */
interface SpellOut {
    /**
     * Conjuntos de reglas disponibles, o null si la plataforma no los expone (Android solo ofrece las reglas
     * a través de `MessageFormat`, que con un nombre desconocido usa en silencio las de por defecto).
     */
    val ruleSetNames: Set<String>?

    /** [ruleSet] null = reglas por defecto del idioma (`%spellout-numbering`). */
    fun format(value: Long, ruleSet: String?): String
}

/**
 * [NumberSpeller] sobre reglas SPELLOUT de ICU. Core no depende de ICU: la app usa `android.icu`
 * (sin coste en el APK) y los tests de JVM `com.ibm.icu`, vía [factory].
 */
class SpellOutNumberSpeller(private val factory: (language: String) -> SpellOut?) : NumberSpeller {

    private val lock = SynchronizedObject()
    private val formats = HashMap<String, Any>()
    private val ruleSets = HashMap<String, String>()

    override fun spell(value: Long, languageTag: String, style: NumberStyle): String? {
        val lang = LanguageTags.primary(languageTag)
        val words = synchronized(lock) {
            val f = formats.getOrPut(lang) { runCatching { factory(lang) }.getOrNull() ?: NONE } as? SpellOut ?: return null
            val ruleSet = ruleSets.getOrPut("$lang|$style") { resolve(f, style) ?: DEFAULT }.takeIf { it != DEFAULT }
            runCatching { f.format(value, ruleSet) }.getOrNull()
        } ?: return null
        // Sin reglas para el idioma, ICU cae en la raíz y devuelve cifras: mejor dejar el número como está.
        return words.takeIf { it.isNotBlank() && it.none(Char::isDigit) }?.replace(SOFT_HYPHEN, ' ')?.replace(SPACES, " ")?.trim()
    }

    /** Primer conjunto candidato que exista; null = el de por defecto. */
    private fun resolve(f: SpellOut, style: NumberStyle): String? {
        val names = f.ruleSetNames
        if (names != null) return CANDIDATES.getValue(style).firstOrNull { it in names }
        if (style == NumberStyle.CARDINAL) return null
        // Sin lista: existe si cambia el resultado respecto a las reglas por defecto en algún número de prueba.
        return CANDIDATES.getValue(style).firstOrNull { c -> PROBES.any { f.format(it, c) != f.format(it, null) } }
    }

    private companion object {
        val NONE = Any()
        const val DEFAULT = ""
        val SOFT_HYPHEN = 0xAD.toChar()
        val SPACES = Regex("\\s+")
        val PROBES = listOf(1L, 2L, 21L, 1999L)
        // «numbering» es la forma suelta («veintiuno»); en español «cardinal-masculine» ya viene apocopada («veintiún»).
        val CARDINAL = listOf("%spellout-numbering", "%spellout-cardinal", "%spellout-cardinal-masculine")
        val CANDIDATES = mapOf(
            NumberStyle.CARDINAL to CARDINAL,
            NumberStyle.CARDINAL_FEMININE to listOf("%spellout-cardinal-feminine") + CARDINAL,
            NumberStyle.ORDINAL_MASCULINE to listOf("%spellout-ordinal-masculine", "%spellout-ordinal"),
            NumberStyle.ORDINAL_FEMININE to listOf("%spellout-ordinal-feminine", "%spellout-ordinal"),
            NumberStyle.YEAR to listOf("%spellout-numbering-year") + CARDINAL,
        )
    }
}
