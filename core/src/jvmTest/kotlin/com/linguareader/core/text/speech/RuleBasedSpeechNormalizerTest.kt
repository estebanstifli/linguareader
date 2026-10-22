package com.linguareader.core.text.speech

import com.ibm.icu.text.MessageFormat
import com.ibm.icu.text.RuleBasedNumberFormat
import com.ibm.icu.util.ULocale
import com.linguareader.core.model.Passage
import com.linguareader.core.text.PassageBuilder
import com.linguareader.core.text.SentenceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** ICU4J con acceso directo a las reglas (lista de conjuntos conocida). */
val Icu4jNumberSpeller = SpellOutNumberSpeller { lang ->
    val f = RuleBasedNumberFormat(ULocale.forLanguageTag(lang), RuleBasedNumberFormat.SPELLOUT)
    object : SpellOut {
        override val ruleSetNames: Set<String> = f.ruleSetNames.toSet()
        override fun format(value: Long, ruleSet: String?): String = if (ruleSet == null) f.format(value) else f.format(value, ruleSet)
    }
}

/** Igual que la app (android.icu): solo `MessageFormat`, sin lista de reglas → se detectan probando. */
val Icu4jMessageFormatSpeller = SpellOutNumberSpeller { lang ->
    val locale = ULocale.forLanguageTag(lang)
    object : SpellOut {
        override val ruleSetNames: Set<String>? = null
        override fun format(value: Long, ruleSet: String?): String =
            MessageFormat(if (ruleSet == null) "{0,spellout}" else "{0,spellout,$ruleSet}", locale).format(arrayOf<Any>(value))
    }
}

@RunWith(Parameterized::class)
class RuleBasedSpeechNormalizerTest(private val label: String, speller: NumberSpeller) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun spellers() = listOf(arrayOf("rbnf", Icu4jNumberSpeller), arrayOf("messageformat", Icu4jMessageFormatSpeller))
    }

    private val normalizer = RuleBasedSpeechNormalizer(speller)

    private fun passage(text: String, lang: String, speech: String? = null, lexicon: Map<String, String> = emptyMap()): Passage =
        PassageBuilder.build("p", "c", 0, lang, listOf(SentenceInput("s1", text, emptyMap(), speech)), null, lexicon)

    private fun spoken(text: String, lang: String, lexicon: Map<String, String> = emptyMap()): String {
        val p = passage(text, lang, lexicon = lexicon)
        return normalizer.normalize(p, p.sentences[0], lang).spoken
    }

    private fun perToken(text: String, lang: String, speech: String? = null): List<String>? {
        val p = passage(text, lang, speech)
        return normalizer.normalize(p, p.sentences[0], lang).tokenSpoken
    }

    @Test
    fun `text without anything to normalize is unchanged`() {
        val text = "L'école est près d'ici, n'est-ce pas ? Oui !"
        val p = passage(text, "fr")
        val r = normalizer.normalize(p, p.sentences[0], "fr")
        assertEquals(p.sentences[0].text, r.spoken)
        assertEquals(p.tokens.map { it.text }, r.tokenSpoken)
    }

    @Test
    fun `spanish numbers apocope before nouns`() {
        assertEquals("Tengo veintiún años.", spoken("Tengo 21 años.", "es"))
        assertEquals("Hay un perro.", spoken("Hay 1 perro.", "es"))
        assertEquals("El veintiuno de mayo.", spoken("El 21 de mayo.", "es"))
        // Sin saber el género del sustantivo se usa el masculino.
        assertEquals("Son dos mil quinientos libros.", spoken("Son 2.500 libros.", "es"))
    }

    @Test
    fun `spanish decimals percent currency and time`() {
        assertEquals("Sube un tres coma cinco por ciento.", spoken("Sube un 3,5 %.", "es"))
        assertEquals("Cuesta veintiún euros.", spoken("Cuesta 21 €.", "es"))
        assertEquals("Cuesta tres euros con cincuenta.", spoken("Cuesta 3,50 €.", "es"))
        assertEquals("Llega a las diez y media.", spoken("Llega a las 10:30.", "es"))
        assertEquals("Sale a la una y diez.", spoken("Sale a la 1:10.", "es"))
    }

    @Test
    fun `spanish abbreviations ordinals and centuries`() {
        assertEquals("El doctor Pérez vive en el primer piso.", spoken("El Dr. Pérez vive en el 1.º piso.", "es"))
        assertEquals("Es del siglo diecinueve.", spoken("Es del siglo XIX.", "es"))
        assertEquals("Es del siglo diecinueve.", spoken("Es del s. XIX.", "es"))
        assertEquals("Viajó a Estados Unidos.", spoken("Viajó a EE. UU.", "es"))
        assertEquals("Compró pan, leche, etcétera.", spoken("Compró pan, leche, etc.", "es"))
        assertEquals("Es la segunda vez.", spoken("Es la 2.ª vez.", "es"))
    }

    @Test
    fun `english years ordinals money and abbreviations`() {
        assertEquals("It was nineteen ninety-nine.", spoken("It was 1999.", "en"))
        assertEquals("She came twenty-first.", spoken("She came 21st.", "en"))
        assertEquals("It costs five dollars.", spoken("It costs \$5.", "en"))
        assertEquals("One thousand two hundred people.", spoken("1,200 people.", "en"))
        assertEquals("Mister Brown arrived at ten oh five.", spoken("Mr. Brown arrived at 10:05.", "en"))
        assertEquals("Pi is three point one four.", spoken("Pi is 3.14.", "en"))
    }

    @Test
    fun `english dates and roman numerals`() {
        assertEquals("It opened on March third, twenty twenty-five.", spoken("It opened on March 3, 2025.", "en"))
        assertEquals("We left on the fourth of July.", spoken("We left on the 4th of July.", "en"))
        assertEquals("Read Chapter four after World War two.", spoken("Read Chapter IV after World War II.", "en"))
        assertEquals("King Henry the eighth met Elizabeth the second.", spoken("King Henry VIII met Elizabeth II.", "en"))
        assertEquals("I think I can.", spoken("I think I can.", "en"))
        assertEquals("She lives in Washington DC.", spoken("She lives in Washington DC.", "en"))
        assertEquals("May I help you?", spoken("May I help you?", "en"))
    }

    @Test
    fun `french elisions ordinals and spaces as thousands separator`() {
        assertEquals("En l'an deux mille.", spoken("En l'an 2000.", "fr"))
        assertEquals("Le dix-neuvième siècle.", spoken("Le XIXe siècle.", "fr"))
        assertEquals("Le premier jour.", spoken("Le 1er jour.", "fr"))
        assertEquals("Ce livre coûte trois mille euros.", spoken("Ce livre coûte 3\u00A0000 €.", "fr"))
        assertEquals("Il est dix heures trente.", spoken("Il est 10h30.", "fr"))
        assertEquals("Madame Dubois arrive.", spoken("Mme Dubois arrive.", "fr"))
    }

    @Test
    fun `every token gets its spoken form in order`() {
        val p = passage("Tengo 42 gatos y 3,5 kilos.", "es")
        val r = normalizer.normalize(p, p.sentences[0], "es")
        assertEquals(p.tokens.size, r.tokenSpoken!!.size)
        assertEquals(listOf("Tengo", "cuarenta y dos", "gatos", "y", "tres coma cinco", "", "kilos"), r.tokenSpoken)
    }

    @Test
    fun `multiword abbreviation is distributed one to one when word counts match`() {
        assertEquals(listOf("Viajó", "a", "Estados", "Unidos"), perToken("Viajó a EE. UU.", "es"))
    }

    @Test
    fun `book lexicon wins over rules`() {
        assertEquals("Vive en la Calle Mayor.", spoken("Vive en la C. Mayor.", "es", mapOf("C." to "Calle")))
    }

    @Test
    fun `unsupported language keeps numbers it cannot spell`() {
        val speller = SpellOutNumberSpeller { null }
        val n = RuleBasedSpeechNormalizer(speller)
        val p = passage("Ich habe 3 Katzen.", "de")
        assertEquals("Ich habe 3 Katzen.", n.normalize(p, p.sentences[0], "de").spoken)
    }

    @Test
    fun `author speech is used verbatim and anchored to tokens`() {
        assertEquals(listOf("Tengo", "cuarenta y dos", "gatos"), perToken("Tengo 42 gatos.", "es", speech = "Tengo cuarenta y dos gatos."))
        val p = passage("Tengo 42 gatos.", "es", speech = "Tengo cuarenta y dos gatos.")
        assertEquals("Tengo cuarenta y dos gatos.", normalizer.normalize(p, p.sentences[0], "es").spoken)
    }

    @Test
    fun `author speech unrelated to the text gives no word mapping`() {
        assertNull(perToken("Hola, mundo.", "es", speech = "Buenos días a todos."))
    }

    @Test
    fun `snippet normalization`() {
        assertEquals("cuarenta y dos", normalizer.normalizeText("42", "es"))
    }
}
