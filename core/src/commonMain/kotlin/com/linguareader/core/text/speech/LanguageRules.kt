package com.linguareader.core.text.speech

import com.linguareader.core.model.LanguageTags

/**
 * Forma escrita → forma hablada. [written] se compara token a token (y la puntuación entre ellos),
 * p. ej. "Sr.", "EE. UU.". [next], si existe, debe casar con el texto que sigue (tras espacios).
 */
data class Abbreviation(
    val written: String,
    val spoken: String,
    val ignoreCase: Boolean = false,
    val next: Regex? = null,
)

data class CurrencyUnit(val singular: String, val plural: String, val feminine: Boolean = false)

/**
 * Datos de normalización de un idioma. Solo datos: la lógica está en [RuleBasedSpeechNormalizer].
 * Un idioma sin reglas propias usa [generic] (solo números enteros sueltos).
 */
data class LanguageRules(
    val language: String,
    val abbreviations: List<Abbreviation> = emptyList(),
    /** Separadores de miles aceptados entre grupos de 3 cifras. */
    val thousandsSeparators: Set<String> = emptySet(),
    val decimalSeparator: String? = null,
    val decimalWord: String? = null,
    /** Decimales cifra a cifra («3.14» → «three point one four») en lugar de como número («tres coma catorce»). */
    val fractionDigitByDigit: Boolean = false,
    val percentWords: String? = null,
    val currencies: Map<String, CurrencyUnit> = emptyMap(),
    /** Plantilla «entero unidad + céntimos» (%1$s entero, %2$s unidad, %3$s fracción). */
    val currencyWithCents: String = "%1\$s %2\$s %3\$s",
    /** Hora «h:mm» → palabras; recibe hora y minutos ya deletreados (o null si son 0). */
    val time: ((hour: String, hourValue: Long, minutes: String?, minuteValue: Long) -> String)? = null,
    /** Palabras tras las que un número romano se lee como cardinal («siglo XIX» → «siglo diecinueve»). */
    val romanCardinalAfter: Set<String> = emptySet(),
    /** Romano tras un nombre propio, como ordinal en esta plantilla («Henry VIII» → «Henry the eighth»); null = no. */
    val regnalTemplate: String? = null,
    /** Palabras (meses) tras las que un número 1–31 es un día y se lee como ordinal («March 3» → «March third»). */
    val ordinalDayAfter: Set<String> = emptySet(),
    /** Sufijo de ordinal pegado a cifras o romanos («1st», «2e», «XIXe»), con su género. */
    val ordinalSuffixes: Map<String, NumberStyle> = emptyMap(),
    /** Indicadores de ordinal como token aparte o tras punto («1.º», «2.ª»). */
    val ordinalIndicators: Map<String, NumberStyle> = emptyMap(),
    /** Apócope ante sustantivo: «veintiuno años» → «veintiún años», «primero piso» → «primer piso» (español). */
    val apocope: ((String) -> String)? = null,
    /** Palabras tras un número que NO son el sustantivo que cuenta («el 21 de mayo»): sin apócope. */
    val nonNounsAfterNumber: Set<String> = emptySet(),
) {
    companion object {
        fun forLanguage(languageTag: String): LanguageRules = when (val lang = LanguageTags.primary(languageTag)) {
            "es" -> SPANISH
            "en" -> ENGLISH
            "fr" -> FRENCH
            else -> generic(lang)
        }

        fun generic(language: String) = LanguageRules(language)

        private val NBSP = setOf("\u00A0", "\u202F")
        private val CAPITALIZED = Regex("^\\p{Lu}")
        private val DIGIT = Regex("^\\d")
        private val ROMAN = Regex("^[IVXLCDM]+\\b")

        val SPANISH = LanguageRules(
            language = "es",
            abbreviations = listOf(
                Abbreviation("Sr.", "señor"), Abbreviation("Sra.", "señora"), Abbreviation("Srta.", "señorita"),
                Abbreviation("Sres.", "señores"), Abbreviation("Dr.", "doctor"), Abbreviation("Dra.", "doctora"),
                Abbreviation("Ud.", "usted"), Abbreviation("Uds.", "ustedes"), Abbreviation("Vd.", "usted"),
                Abbreviation("D.", "don", next = CAPITALIZED), Abbreviation("Dña.", "doña"),
                Abbreviation("etc.", "etcétera"), Abbreviation("pág.", "página"), Abbreviation("págs.", "páginas"),
                Abbreviation("núm.", "número"), Abbreviation("aprox.", "aproximadamente"),
                Abbreviation("Avda.", "avenida"), Abbreviation("Av.", "avenida"), Abbreviation("c/", "calle", next = CAPITALIZED),
                Abbreviation("EE. UU.", "Estados Unidos"), Abbreviation("EE.UU.", "Estados Unidos"),
                Abbreviation("p. ej.", "por ejemplo"), Abbreviation("s.", "siglo", next = ROMAN),
            ),
            thousandsSeparators = setOf(".") + NBSP,
            decimalSeparator = ",",
            decimalWord = "coma",
            percentWords = "por ciento",
            currencies = mapOf(
                "€" to CurrencyUnit("euro", "euros"), "$" to CurrencyUnit("dólar", "dólares"),
                "£" to CurrencyUnit("libra", "libras", feminine = true),
            ),
            currencyWithCents = "%1\$s %2\$s con %3\$s",
            time = { h, _, m, mv ->
                when {
                    m == null -> "$h en punto"
                    mv == 15L -> "$h y cuarto"
                    mv == 30L -> "$h y media"
                    else -> "$h y $m"
                }
            },
            romanCardinalAfter = setOf("siglo", "siglos"),
            ordinalIndicators = mapOf("º" to NumberStyle.ORDINAL_MASCULINE, "ª" to NumberStyle.ORDINAL_FEMININE),
            ordinalSuffixes = mapOf("º" to NumberStyle.ORDINAL_MASCULINE, "ª" to NumberStyle.ORDINAL_FEMININE),
            apocope = { words ->
                when {
                    // Ordinales: «primero piso» → «primer piso», «tercero» → «tercer».
                    words.endsWith("primero") || words.endsWith("tercero") -> words.dropLast(1)
                    words.endsWith("veintiuno") -> words.removeSuffix("veintiuno") + "veintiún"
                    words == "uno" || words.endsWith(" uno") -> words.removeSuffix("uno") + "un"
                    else -> words
                }
            },
            nonNounsAfterNumber = setOf(
                "de", "del", "y", "o", "u", "e", "a", "al", "en", "por", "para", "con", "sin", "que", "es", "son",
                "fue", "era", "más", "menos", "veces", "por ciento", "entre", "hasta", "desde",
            ),
        )

        val ENGLISH = LanguageRules(
            language = "en",
            abbreviations = listOf(
                Abbreviation("Mr.", "mister"), Abbreviation("Mrs.", "missus"), Abbreviation("Ms.", "miz"),
                Abbreviation("Dr.", "doctor"), Abbreviation("Prof.", "professor"), Abbreviation("St.", "saint", next = CAPITALIZED),
                Abbreviation("Mt.", "mount"), Abbreviation("etc.", "et cetera"), Abbreviation("vs.", "versus"),
                Abbreviation("e.g.", "for example"), Abbreviation("i.e.", "that is"),
                Abbreviation("No.", "number", next = DIGIT), Abbreviation("Jr.", "junior"), Abbreviation("Sr.", "senior"),
                Abbreviation("U.S.", "U S"), Abbreviation("a.m.", "A M"), Abbreviation("p.m.", "P M"),
            ),
            thousandsSeparators = setOf(","),
            decimalSeparator = ".",
            decimalWord = "point",
            fractionDigitByDigit = true,
            percentWords = "percent",
            currencies = mapOf(
                "$" to CurrencyUnit("dollar", "dollars"), "€" to CurrencyUnit("euro", "euros"),
                "£" to CurrencyUnit("pound", "pounds"),
            ),
            currencyWithCents = "%1\$s %2\$s and %3\$s",
            time = { h, _, m, mv ->
                when {
                    m == null -> "$h o'clock"
                    mv < 10 -> "$h oh $m"
                    else -> "$h $m"
                }
            },
            ordinalSuffixes = mapOf(
                "st" to NumberStyle.ORDINAL_MASCULINE, "nd" to NumberStyle.ORDINAL_MASCULINE,
                "rd" to NumberStyle.ORDINAL_MASCULINE, "th" to NumberStyle.ORDINAL_MASCULINE,
            ),
            romanCardinalAfter = setOf(
                "chapter", "chapters", "part", "book", "volume", "act", "scene", "war", "phase", "stage", "level", "unit",
                "room", "gallery", "hall", "floor", "case", "page", "section",
            ),
            regnalTemplate = "the %s",
            ordinalDayAfter = setOf(
                "january", "february", "march", "april", "may", "june", "july", "august",
                "september", "october", "november", "december",
            ),
        )

        val FRENCH = LanguageRules(
            language = "fr",
            abbreviations = listOf(
                Abbreviation("M.", "monsieur", next = CAPITALIZED), Abbreviation("MM.", "messieurs"),
                Abbreviation("Mme", "madame"), Abbreviation("Mmes", "mesdames"), Abbreviation("Mlle", "mademoiselle"),
                Abbreviation("Mlles", "mesdemoiselles"), Abbreviation("Dr", "docteur"), Abbreviation("Dr.", "docteur"),
                Abbreviation("Pr", "professeur"), Abbreviation("St", "saint"), Abbreviation("Ste", "sainte"),
                Abbreviation("etc.", "et cetera"), Abbreviation("p. ex.", "par exemple"),
                Abbreviation("c.-à-d.", "c'est-à-dire"), Abbreviation("av.", "avenue"), Abbreviation("bd", "boulevard"),
            ),
            thousandsSeparators = setOf(" ", ".") + NBSP,
            decimalSeparator = ",",
            decimalWord = "virgule",
            percentWords = "pour cent",
            currencies = mapOf(
                "€" to CurrencyUnit("euro", "euros"), "$" to CurrencyUnit("dollar", "dollars"),
                "£" to CurrencyUnit("livre", "livres", feminine = true),
            ),
            currencyWithCents = "%1\$s %2\$s %3\$s",
            time = { h, hv, m, _ ->
                val heures = if (hv == 1L) "heure" else "heures"
                if (m == null) "$h $heures" else "$h $heures $m"
            },
            ordinalSuffixes = mapOf(
                "er" to NumberStyle.ORDINAL_MASCULINE, "re" to NumberStyle.ORDINAL_FEMININE,
                "ère" to NumberStyle.ORDINAL_FEMININE, "e" to NumberStyle.ORDINAL_MASCULINE,
                "ème" to NumberStyle.ORDINAL_MASCULINE, "ᵉ" to NumberStyle.ORDINAL_MASCULINE,
            ),
        )
    }
}
