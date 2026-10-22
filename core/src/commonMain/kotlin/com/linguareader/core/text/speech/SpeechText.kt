package com.linguareader.core.text.speech

import com.linguareader.core.model.Passage
import com.linguareader.core.model.Sentence

/**
 * Forma hablada de una frase: lo que se envía al TTS. El texto mostrado y sus tokens no cambian nunca;
 * [tokenSpoken] dice qué se pronuncia por cada token de la frase (en orden), para que el alineador
 * asigne los tiempos de «cuarenta y dos» al token «42». Una entrada vacía = el token no tiene voz propia
 * (p. ej. «UU» en «EE. UU.» → «Estados Unidos» cuando las palabras no cuadran): sus tiempos se reparten.
 *
 * [tokenSpoken] null = no se puede relacionar el habla con los tokens (forma del autor muy distinta del
 * texto): la frase se resalta entera, sin inventar tiempos.
 */
data class SpokenText(val spoken: String, val tokenSpoken: List<String>?)

/**
 * Puerto de normalización de texto antes del TTS (números, abreviaturas, símbolos → palabras).
 * Sustituible por DI: reglas propias, gramáticas WFST, etc. Debe ser rápido y puro (sin E/S).
 */
interface SpeechTextNormalizer {
    val id: String

    fun normalize(passage: Passage, sentence: Sentence, languageTag: String): SpokenText

    /** Texto suelto (audio de una palabra o expresión pulsada). */
    fun normalizeText(text: String, languageTag: String): String = text
}

/** Sin reglas: el TTS recibe el texto tal cual, salvo la forma hablada que haya dado el autor (es contenido). */
object IdentityNormalizer : SpeechTextNormalizer {
    override val id = "identity"
    override fun normalize(passage: Passage, sentence: Sentence, languageTag: String): SpokenText {
        val tokens = passage.tokensOf(sentence).map { it.text }
        return sentence.speech?.let { SpeechOverride.map(it, tokens, languageTag) } ?: SpokenText(sentence.text, tokens)
    }
}

/** Activa o desactiva [delegate] en caliente (ajuste del usuario); desactivado = [IdentityNormalizer]. */
class SwitchableNormalizer(private val enabled: () -> Boolean, private val delegate: SpeechTextNormalizer) : SpeechTextNormalizer {
    override val id: String get() = if (enabled()) delegate.id else IdentityNormalizer.id
    override fun normalize(passage: Passage, sentence: Sentence, languageTag: String) =
        (if (enabled()) delegate else IdentityNormalizer).normalize(passage, sentence, languageTag)
    override fun normalizeText(text: String, languageTag: String) =
        if (enabled()) delegate.normalizeText(text, languageTag) else text
}

enum class NumberStyle { CARDINAL, CARDINAL_FEMININE, ORDINAL_MASCULINE, ORDINAL_FEMININE, YEAR }

/** Deletreo de números en palabras (ICU RuleBasedNumberFormat en Android). */
interface NumberSpeller {
    /** null si el idioma no está soportado: el número se deja tal cual. */
    fun spell(value: Long, languageTag: String, style: NumberStyle = NumberStyle.CARDINAL): String?
}
