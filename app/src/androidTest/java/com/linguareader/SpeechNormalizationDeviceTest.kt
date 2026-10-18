package com.linguareader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linguareader.core.text.speech.NumberStyle
import com.linguareader.core.text.speech.RuleBasedSpeechNormalizer
import com.linguareader.engine.text.AndroidIcuNumberSpeller
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** El ICU del sistema (solo `MessageFormat` es público) da las mismas reglas que ICU4J en las pruebas de JVM. */
@RunWith(AndroidJUnit4::class)
class SpeechNormalizationDeviceTest {

    private val speller = AndroidIcuNumberSpeller
    private val normalizer = RuleBasedSpeechNormalizer(speller)

    @Test
    fun systemIcuSpellsCardinalsOrdinalsAndYears() {
        assertEquals("veintiuno", speller.spell(21, "es"))
        assertEquals("veintiuna", speller.spell(21, "es", NumberStyle.CARDINAL_FEMININE))
        assertEquals("segunda", speller.spell(2, "es", NumberStyle.ORDINAL_FEMININE))
        assertEquals("first", speller.spell(1, "en", NumberStyle.ORDINAL_MASCULINE))
        assertEquals("nineteen ninety-nine", speller.spell(1999, "en", NumberStyle.YEAR))
        assertEquals("dix-neuvième", speller.spell(19, "fr", NumberStyle.ORDINAL_MASCULINE))
    }

    @Test
    fun systemIcuNormalizesSentences() {
        assertEquals("Tengo veintiún años.", normalizer.normalizeText("Tengo 21 años.", "es"))
        assertEquals("It costs five dollars.", normalizer.normalizeText("It costs \$5.", "en"))
        assertEquals("Il est dix heures trente.", normalizer.normalizeText("Il est 10h30.", "fr"))
    }
}
