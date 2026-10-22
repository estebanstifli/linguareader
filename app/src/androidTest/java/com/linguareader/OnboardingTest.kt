package com.linguareader

import org.koin.core.component.inject
import org.koin.core.component.KoinComponent
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Primera apertura con toques reales: cada camino pregunta solo lo suyo y termina en la biblioteca. Sin descargas. */
@OptIn(ExperimentalTestApi::class)
class OnboardingTest : KoinComponent {
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val preferences: UserPreferencesRepository by inject()
    private val scanSettings: ScanSettingsRepository by inject()

    @Before
    fun setUp() {
        runBlocking {
            preferences.update { UserPreferences() }
            scanSettings.update { ScanSettings() }
        }
    }

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 10_000)

    @Test
    fun readingPathOffersTheSearchAndEndsInTheLibrary() {
        waitFor(str(R.string.onboarding_purpose_title))
        compose.onNodeWithText(str(R.string.onboarding_purpose_read)).performClick()
        waitFor(str(R.string.scan_offer_title))
        compose.onNodeWithText(str(R.string.common_not_now)).performClick()
        waitFor(str(R.string.onboarding_summary_title))
        compose.onNodeWithText(str(R.string.onboarding_start_reading)).performClick()
        compose.waitUntilDoesNotExist(hasText(str(R.string.onboarding_summary_title)), 5_000)

        val p = runBlocking { preferences.preferences.first() }
        assertEquals(AppPurpose.READ, p.purpose)
        assertFalse("Solo leer no usa la voz", p.ttsMode)
        val s = runBlocking { scanSettings.settings.first() }
        assertTrue(s.offered)
        assertFalse("«Ahora no» tampoco busca solo al abrir la app", s.autoStart)
    }

    @Test
    fun learningPathCanSkipTheLanguageAndThenSkipsTheLevel() {
        waitFor(str(R.string.onboarding_purpose_title))
        compose.onNodeWithText(str(R.string.onboarding_purpose_learn)).performClick()
        waitFor(str(R.string.onboarding_learn_title))
        compose.onNodeWithText(str(R.string.common_not_now)).performClick()
        waitFor(str(R.string.onboarding_native_title))
        compose.onNodeWithText(str(R.string.common_continue)).performClick()
        // Sin idioma que aprender no hay nivel que preguntar ni búsqueda (solo aprender): directo al resumen.
        waitFor(str(R.string.onboarding_summary_title))
        waitFor(str(R.string.onboarding_language_to_learn))
        compose.onNodeWithText(str(R.string.onboarding_start_reading)).performClick()
        compose.waitUntilDoesNotExist(hasText(str(R.string.onboarding_summary_title)), 5_000)

        val p = runBlocking { preferences.preferences.first() }
        assertEquals(AppPurpose.LEARN, p.purpose)
        assertTrue(p.ttsMode)
        assertTrue(p.learnerLevelChosen)
        assertTrue(p.onboardingSummaryShown)
    }
}
