package com.linguareader

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.ScanSettings
import com.linguareader.data.scan.ScanSettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/** Primera apertura con toques reales: cada camino pregunta solo lo suyo y termina en la biblioteca. Sin descargas. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class OnboardingTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var preferences: UserPreferencesRepository
    @Inject lateinit var scanSettings: ScanSettingsRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            preferences.update { UserPreferences() }
            scanSettings.update { ScanSettings() }
        }
    }

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 10_000)

    @Test
    fun readingPathOffersTheSearchAndEndsInTheLibrary() {
        waitFor("¿Para qué vas a usar LinguaReader?")
        compose.onNodeWithText("Leer mis libros y documentos").performClick()
        waitFor("¿Buscamos libros en tu móvil?")
        compose.onNodeWithText("Ahora no").performClick()
        waitFor("Esto es lo que hemos preparado")
        compose.onNodeWithText("Empezar a leer").performClick()
        compose.waitUntilDoesNotExist(hasText("Esto es lo que hemos preparado"), 5_000)

        val p = runBlocking { preferences.preferences.first() }
        assertEquals(AppPurpose.READ, p.purpose)
        assertFalse("Solo leer no usa la voz", p.ttsMode)
        val s = runBlocking { scanSettings.settings.first() }
        assertTrue(s.offered)
        assertFalse("«Ahora no» tampoco busca solo al abrir la app", s.autoStart)
    }

    @Test
    fun learningPathCanSkipTheLanguageAndThenSkipsTheLevel() {
        waitFor("¿Para qué vas a usar LinguaReader?")
        compose.onNodeWithText("Aprender un idioma").performClick()
        waitFor("¿Qué idioma quieres aprender?")
        compose.onNodeWithText("Ahora no").performClick()
        waitFor("¿Cuál es tu idioma?")
        compose.onNodeWithText("Continuar").performClick()
        // Sin idioma que aprender no hay nivel que preguntar ni búsqueda (solo aprender): directo al resumen.
        waitFor("Esto es lo que hemos preparado")
        waitFor("Idioma para aprender")
        compose.onNodeWithText("Empezar a leer").performClick()
        compose.waitUntilDoesNotExist(hasText("Esto es lo que hemos preparado"), 5_000)

        val p = runBlocking { preferences.preferences.first() }
        assertEquals(AppPurpose.LEARN, p.purpose)
        assertTrue(p.ttsMode)
        assertTrue(p.learnerLevelChosen)
        assertTrue(p.onboardingSummaryShown)
    }
}
