package com.linguareader

import org.koin.core.component.inject
import org.koin.core.component.KoinComponent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.ScanSettings
import com.linguareader.data.scan.ScanSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private fun language(tag: String): String =
        java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.getDefault()).replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }

    @Test
    fun freshInstallChoosesLanguagesThenLevelAndEndsInTheLibrary() {
        waitFor(str(R.string.onboarding_languages_title))
        // Francés: nunca coincide con el idioma del emulador (inglés), así que «Continuar» queda activo.
        compose.onNodeWithText(language("fr")).performScrollTo().performClick()
        // La lista de idiomas es larga: el botón queda fuera de pantalla hasta desplazarse.
        compose.onNodeWithText(str(R.string.common_continue)).performScrollTo().performClick()
        waitFor(str(R.string.onboarding_level_body))
        compose.onNodeWithText(str(R.string.onboarding_level_unsure)).performClick()
        compose.onNodeWithText(str(R.string.onboarding_continue_download)).performClick()
        waitFor(str(R.string.onboarding_summary_title))
        compose.onNodeWithText(str(R.string.onboarding_start_reading)).performClick()
        compose.waitUntilDoesNotExist(hasText(str(R.string.onboarding_summary_title)), 5_000)

        val p = runBlocking { preferences.preferences.first() }
        assertEquals(AppPurpose.LEARN, p.purpose)
        assertEquals(listOf("fr"), p.learningLanguages)
        assertEquals("fr", p.readingLanguage)
        assertTrue(p.ttsMode)
        assertTrue(p.learnerLevelChosen)
        assertTrue(p.onboardingSummaryShown)
    }

    @Test
    fun continueIsDisabledUntilALanguageIsChosen() {
        waitFor(str(R.string.onboarding_languages_title))
        compose.onNodeWithText(str(R.string.common_continue)).assertIsNotEnabled()
        compose.onNodeWithText(language("fr")).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.common_continue)).assertIsEnabled()
    }
}
