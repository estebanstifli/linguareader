package com.linguareader.core.text.speech

import com.linguareader.core.model.Passage
import com.linguareader.core.model.Sentence
import com.linguareader.core.text.Tokenizer

/**
 * Normalización por reglas, en este orden de prioridad:
 * 1. Forma hablada del autor ([Sentence.speech]): se usa tal cual.
 * 2. Léxico del libro ([Passage.pronunciations]) y abreviaturas del idioma ([LanguageRules.abbreviations]).
 * 3. Horas, ordinales, romanos en contexto, decimales, miles, %, moneda.
 * 4. Enteros sueltos ([NumberSpeller]).
 *
 * Trabaja sobre los tokens del [Tokenizer]: cada regla consume uno o varios tokens (y la puntuación
 * entre ellos) y su forma hablada se asigna a esos tokens, de modo que el mapa token → habla sale solo.
 * La puntuación que ninguna regla consume se copia tal cual (da la prosodia al TTS).
 */
class RuleBasedSpeechNormalizer(
    private val numbers: NumberSpeller,
    private val rulesFor: (String) -> LanguageRules = LanguageRules::forLanguage,
) : SpeechTextNormalizer {

    override val id = "rules"

    override fun normalize(passage: Passage, sentence: Sentence, languageTag: String): SpokenText {
        val tokens = passage.tokensOf(sentence)
        sentence.speech?.let { return SpeechOverride.map(it, tokens.map { t -> t.text }, languageTag) }
        val words = tokens.map { Word(it.text, it.rangeStart - sentence.rangeStart, it.rangeEnd - sentence.rangeStart) }
        return Run(sentence.text, words, languageTag, passage.pronunciations, capitalize = true).execute()
    }

    override fun normalizeText(text: String, languageTag: String): String {
        val normalized = Tokenizer.normalize(text)
        val words = Tokenizer.tokenize(normalized, languageTag).map { Word(normalized.substring(it.start, it.end), it.start, it.end) }
        return Run(normalized, words, languageTag, emptyMap(), capitalize = false).execute().spoken
    }

    private class Word(val text: String, val start: Int, val end: Int) {
        val isDigits = text.isNotEmpty() && text.all { it in '0'..'9' }
    }

    /** Resultado de una regla: consume [count] tokens desde el actual y los caracteres [start, end). */
    private class Match(val count: Int, val start: Int, val end: Int, val spoken: String, val perToken: List<String>? = null)

    /** Abreviatura o término del léxico ya troceado como tokens + puntuación. */
    private class Pattern(val source: Abbreviation, val words: List<String>, val inner: List<String>, val trailing: String)

    private inner class Run(
        private val text: String,
        private val words: List<Word>,
        private val languageTag: String,
        pronunciations: Map<String, String>,
        private val capitalize: Boolean,
    ) {
        private val rules = rulesFor(languageTag)
        private val patterns = (pronunciations.map { (k, v) -> Abbreviation(k, v, ignoreCase = true) } + rules.abbreviations)
            .mapNotNull(::compile)
            .sortedByDescending { it.words.size } // la más larga gana ("EE. UU." antes que "EE.")
        private var cursor = 0
        private var lastSpoken: String? = null

        fun execute(): SpokenText {
            val out = StringBuilder()
            val perToken = MutableList(words.size) { "" }
            var i = 0
            while (i < words.size) {
                val w = words[i]
                var m = runCatching { match(i) }.getOrNull()?.takeIf { it.start >= cursor && it.count >= 1 }
                    ?: Match(1, w.start, w.end, w.text)
                // A principio de frase se conserva la mayúscula: «Mr. Brown» → «Mister Brown», «21 años» → «Veintiún años».
                if (capitalize && i == 0 && m.spoken != w.text && (w.text.first().isUpperCase() || w.text.first().isDigit())) {
                    val cap = m.spoken.replaceFirstChar { it.uppercase() }
                    m = Match(m.count, m.start, m.end, cap, m.perToken?.let { listOf(it.first().replaceFirstChar { c -> c.uppercase() }) + it.drop(1) })
                }
                out.append(text, cursor, m.start).append(m.spoken)
                if (m.perToken != null && m.perToken.size == m.count) {
                    m.perToken.forEachIndexed { k, s -> perToken[i + k] = s }
                } else {
                    perToken[i] = m.spoken
                }
                lastSpoken = m.spoken
                cursor = maxOf(cursor, m.end)
                i += m.count
            }
            if (cursor < text.length) out.append(text, cursor, text.length)
            val spoken = out.toString().replace(SPACES, " ").trim()
            return SpokenText(spoken, perToken)
        }

        private fun match(i: Int): Match? =
            matchPattern(i) ?: matchTime(i) ?: matchOrdinal(i) ?: matchRoman(i) ?: matchNumber(i)

        // --- Abreviaturas y léxico --------------------------------------------------------------------

        private fun compile(a: Abbreviation): Pattern? {
            val written = Tokenizer.normalize(a.written)
            val spans = Tokenizer.tokenize(written, languageTag)
            if (spans.isEmpty() || written.substring(0, spans.first().start).isNotBlank()) return null
            val ws = spans.map { written.substring(it.start, it.end) }
            val inner = spans.zipWithNext { x, y -> written.substring(x.end, y.start).filterNot(Char::isWhitespace) }
            return Pattern(a, ws, inner, written.substring(spans.last().end).trim())
        }

        private fun matchPattern(i: Int): Match? = patterns.firstNotNullOfOrNull { p -> matchPattern(p, i) }

        private fun matchPattern(p: Pattern, i: Int): Match? {
            if (i + p.words.size > words.size) return null
            for (k in p.words.indices) {
                if (!words[i + k].text.equals(p.words[k], ignoreCase = p.source.ignoreCase)) return null
                if (k > 0 && gap(i + k - 1).filterNot(Char::isWhitespace) != p.inner[k - 1]) return null
            }
            val lastEnd = words[i + p.words.size - 1].end
            if (!text.startsWith(p.trailing, lastEnd)) return null
            var end = lastEnd + p.trailing.length
            val rest = text.substring(end)
            // Un término sin puntuación final no puede ser el comienzo de otra palabra (lo garantiza el tokenizador).
            p.source.next?.let { if (!it.containsMatchIn(rest.trimStart())) return null }
            // «etc.» a final de frase: el punto sigue siendo el final de la frase.
            if (p.trailing.endsWith('.') && rest.isBlank()) end -= 1
            val spoken = p.source.spoken
            val parts = spoken.split(' ').filter { it.isNotEmpty() }
            return Match(p.words.size, words[i].start, end, spoken, parts.takeIf { it.size == p.words.size })
        }

        // --- Horas ------------------------------------------------------------------------------------

        private fun matchTime(i: Int): Match? {
            val time = rules.time ?: return null
            val w = words[i]
            val compact = FRENCH_TIME.matchEntire(w.text)?.takeIf { rules.language == "fr" }
            val (hText, mText, count) = when {
                compact != null -> Triple(compact.groupValues[1], compact.groupValues[2].ifEmpty { "00" }, 1)
                w.isDigits && w.text.length <= 2 && gap(i) == ":" && words.getOrNull(i + 1)?.let { it.isDigits && it.text.length == 2 } == true ->
                    Triple(w.text, words[i + 1].text, 2)
                else -> return null
            }
            val h = hText.toLong()
            val m = mText.toLong()
            if (h > 24 || m > 59) return null
            val hWords = numbers.spell(h, languageTag, NumberStyle.CARDINAL_FEMININE) ?: return null
            val mWords = if (m == 0L) null else numbers.spell(m, languageTag) ?: return null
            return Match(count, w.start, words[i + count - 1].end, time(hWords, h, mWords, m))
        }

        // --- Ordinales y romanos ----------------------------------------------------------------------

        private fun matchOrdinal(i: Int): Match? {
            val w = words[i]
            // «1st», «2e», «1º»: cifras + sufijo en el mismo token.
            ORDINAL_TOKEN.matchEntire(w.text)?.let { m ->
                val style = suffixStyle(m.groupValues[2]) ?: return null
                val spoken = numbers.spell(m.groupValues[1].toLong(), languageTag, style) ?: return null
                return Match(1, w.start, w.end, if (nounFollows(i, w.end)) apocope(spoken) else spoken)
            }
            // «1.º», «2.ª»: cifras + punto + indicador en otro token.
            if (w.isDigits && w.text.length <= 6 && gap(i) == ".") {
                val next = words.getOrNull(i + 1) ?: return null
                val style = rules.ordinalIndicators[next.text] ?: return null
                val spoken = numbers.spell(w.text.toLong(), languageTag, style) ?: return null
                return Match(2, w.start, next.end, if (nounFollows(i + 1, next.end)) apocope(spoken) else spoken)
            }
            return null
        }

        private fun matchRoman(i: Int): Match? {
            val w = words[i]
            // «XIXe siècle», «François Ier»: romano (solo I, V, X: «Ce», «Le» no son ordinales) + sufijo.
            ROMAN_ORDINAL.matchEntire(w.text)?.let { m ->
                val style = suffixStyle(m.groupValues[2]) ?: return null
                val value = romanValue(m.groupValues[1]) ?: return null
                return numbers.spell(value, languageTag, style)?.let { Match(1, w.start, w.end, it) }
            }
            val previous = lastSpoken ?: return null
            if (i == 0 || text.substring(cursor, w.start).isNotBlank()) return null
            // «siglo XIX» → «siglo diecinueve», «Chapter IV» → «Chapter four».
            if (previous.lowercase() in rules.romanCardinalAfter) {
                val value = romanValue(w.text) ?: return null
                return numbers.spell(value, languageTag)?.let { Match(1, w.start, w.end, it) }
            }
            // «Henry VIII» → «Henry the eighth». Solo I/V/X, ≥ 2 letras y ≤ 40: «I» es un pronombre y «DC» no es un rey.
            val regnal = rules.regnalTemplate ?: return null
            if (!previous.first().isUpperCase() || w.text.length < 2 || w.text.any { it !in "IVX" }) return null
            val value = romanValue(w.text)?.takeIf { it <= 40 } ?: return null
            return numbers.spell(value, languageTag, NumberStyle.ORDINAL_MASCULINE)?.let { Match(1, w.start, w.end, regnal.fillTemplate(it)) }
        }

        private fun suffixStyle(suffix: String): NumberStyle? =
            rules.ordinalSuffixes[suffix] ?: rules.ordinalSuffixes[suffix.lowercase()]

        // --- Números: miles, decimales, %, moneda -----------------------------------------------------

        private fun matchNumber(i: Int): Match? {
            val w = words[i]
            if (!w.isDigits) return null
            var last = i
            var digits = w.text
            if (w.text.length <= 3) {
                while (true) {
                    val next = words.getOrNull(last + 1) ?: break
                    if (!next.isDigits || next.text.length != 3 || gap(last) !in rules.thousandsSeparators) break
                    digits += next.text
                    last++
                }
            }
            val grouped = last > i
            var fraction: String? = null
            rules.decimalSeparator?.let { sep ->
                val next = words.getOrNull(last + 1)
                if (next != null && next.isDigits && gap(last) == sep) { fraction = next.text; last++ }
            }
            if (digits.length > 15) return null
            val value = digits.toLong()
            var start = w.start
            var end = words[last].end

            // Sufijo: «5 %», «5 €»; prefijo: «€5», «$ 5».
            val after = skipSpace(end)
            var currency: CurrencyUnit? = null
            var percent = false
            if (after < text.length) {
                val c = text[after].toString()
                if (c == "%" && rules.percentWords != null) { percent = true; end = after + 1 }
                else rules.currencies[c]?.let { currency = it; end = after + 1 }
            }
            if (currency == null && !percent) {
                val before = skipSpaceBack(start)
                if (before > 0 && before - 1 >= cursor) rules.currencies[text[before - 1].toString()]?.let { currency = it; start = before - 1 }
            }

            val unit = currency
            // «March 3» → «March third» (el día tras el nombre del mes se lee como ordinal).
            val dayAfterMonth = unit == null && !percent && !grouped && fraction == null && value in 1..31 &&
                lastSpoken?.lowercase() in rules.ordinalDayAfter && text.substring(cursor, w.start).isBlank()
            val style = when {
                dayAfterMonth -> NumberStyle.ORDINAL_MASCULINE
                unit?.feminine == true -> NumberStyle.CARDINAL_FEMININE
                unit == null && !percent && !grouped && fraction == null && digits.length == 4 && value in 1100..2099 -> NumberStyle.YEAR
                else -> NumberStyle.CARDINAL
            }
            var intWords = numbers.spell(value, languageTag, style) ?: return null
            val spoken = when {
                unit != null -> {
                    if (!unit.feminine) intWords = apocope(intWords)
                    val name = if (value == 1L) unit.singular else unit.plural
                    val cents = fraction?.padEnd(2, '0')?.take(2)?.toLong()?.takeIf { it > 0 }
                    if (cents == null) "$intWords $name"
                    else rules.currencyWithCents.fillTemplate(intWords, name, numbers.spell(cents, languageTag) ?: return null)
                }
                else -> {
                    val number = if (fraction == null) intWords else "$intWords ${rules.decimalWord ?: return null} ${spellFraction(fraction!!) ?: return null}"
                    when {
                        percent -> "$number ${rules.percentWords}"
                        fraction == null && nounFollows(last, end) -> apocope(number)
                        else -> number
                    }
                }
            }
            return Match(last - i + 1, start, end, spoken)
        }

        /** «05» → «cero cinco»: los ceros a la izquierda se leen uno a uno (en inglés, todas las cifras). */
        private fun spellFraction(fraction: String): String? {
            if (rules.fractionDigitByDigit) {
                return fraction.map { numbers.spell(it.digitToInt().toLong(), languageTag) ?: return null }.joinToString(" ")
            }
            val zeros = fraction.takeWhile { it == '0' }.length
            val zeroWord = numbers.spell(0, languageTag) ?: return null
            val rest = fraction.drop(zeros)
            val parts = List(zeros) { zeroWord } + listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { numbers.spell(it.toLong(), languageTag) ?: return null })
            return parts.joinToString(" ")
        }

        private fun apocope(words: String) = rules.apocope?.invoke(words) ?: words

        /** El número va seguido (solo espacios) de una palabra que probablemente es lo que cuenta: «21 años». */
        private fun nounFollows(last: Int, end: Int): Boolean {
            val next = words.getOrNull(last + 1) ?: return false
            if (text.substring(end, next.start).isNotBlank() || !next.text.first().isLetter()) return false
            return next.text.lowercase() !in rules.nonNounsAfterNumber
        }

        // --- Utilidades -------------------------------------------------------------------------------

        /** Texto entre el token [k] y el siguiente (o hasta el final). */
        private fun gap(k: Int): String = text.substring(words[k].end, words.getOrNull(k + 1)?.start ?: text.length)

        private fun skipSpace(from: Int): Int {
            var p = from
            if (p < text.length && text[p].isSpaceLike()) p++
            return p
        }

        private fun skipSpaceBack(from: Int): Int {
            var p = from
            if (p > 0 && text[p - 1].isSpaceLike()) p--
            return p
        }

        private fun Char.isSpaceLike() = this == ' ' || this == '\u00A0' || this == '\u202F'
    }

    private companion object {
        val SPACES = Regex("[ \\t]+")
        val ORDINAL_TOKEN = Regex("(\\d{1,6})(\\p{L}+)")
        val ROMAN_ORDINAL = Regex("([IVX]+)(\\p{Ll}+)")
        val FRENCH_TIME = Regex("(\\d{1,2})h(\\d{2})?")
        val ROMAN = Regex("M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})")

        fun romanValue(s: String): Long? {
            if (s.isEmpty() || !ROMAN.matches(s)) return null
            val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
            var total = 0
            for (k in s.indices) {
                val cur = v.getValue(s[k])
                val next = s.getOrNull(k + 1)?.let(v::getValue) ?: 0
                total += if (cur < next) -cur else cur
            }
            return total.toLong()
        }
    }
}

/**
 * Relaciona la forma hablada del autor con los tokens de la frase: las palabras iguales hacen de ancla
 * (subsecuencia común más larga) y lo que queda entre anclas se reparte (1:1 si cuadra, si no al primer
 * token del tramo). Sin ninguna ancla y con distinto número de palabras → sin mapa (resaltado por frase).
 */
internal object SpeechOverride {

    fun map(speech: String, tokens: List<String>, languageTag: String): SpokenText {
        val spokenWords = Tokenizer.tokenize(speech, languageTag).map { speech.substring(it.start, it.end) }
        if (tokens.isEmpty()) return SpokenText(speech, emptyList())
        val a = tokens.map(Tokenizer::matchKey)
        val b = spokenWords.map(Tokenizer::matchKey)
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val anchors = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { anchors += i to j; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        if (anchors.isEmpty() && n != m && n > 1) return SpokenText(speech, null)

        val out = List(n) { mutableListOf<String>() }
        var ti = 0
        var sj = 0
        for ((ai, aj) in anchors + (n to m)) {
            val tCount = ai - ti
            val sCount = aj - sj
            when {
                sCount == 0 -> Unit // tokens sin voz propia
                tCount == 0 -> out[if (ti > 0) ti - 1 else ai].addAll(spokenWords.subList(sj, aj))
                tCount == sCount -> for (k in 0 until tCount) out[ti + k] += spokenWords[sj + k]
                else -> out[ti].addAll(spokenWords.subList(sj, aj))
            }
            if (ai < n) out[ai] += spokenWords[aj]
            ti = ai + 1
            sj = aj + 1
        }
        return SpokenText(speech, out.map { it.joinToString(" ") })
    }
}

/**
 * Sustituye `%1$s`, `%2$s`… y `%s` (en orden) por [args], como `String.format` con argumentos de texto.
 * Las plantillas de [LanguageRules] solo usan esos marcadores.
 */
internal fun String.fillTemplate(vararg args: String): String {
    var out = this
    args.forEachIndexed { i, a -> out = out.replace("%${i + 1}\$s", a) }
    var next = 0
    while (next < args.size && out.contains("%s")) { out = out.replaceFirst("%s", args[next]); next++ }
    return out
}
