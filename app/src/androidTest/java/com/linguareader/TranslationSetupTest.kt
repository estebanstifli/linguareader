package com.linguareader

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/** Primera apertura: se propone el idioma de traducción y se puede omitir. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class TranslationSetupTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var preferences: UserPreferencesRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { preferences.update { UserPreferences(translationSetupDone = false, ttsModeChosen = true, learnLanguageChosen = true) } }
    }

    @Test
    fun firstLaunchInLearningModeAsksForALanguageAndCanBeSkipped() {
        runBlocking { preferences.update { it.copy(learnLanguageChosen = false) } }
        compose.waitUntilAtLeastOneExists(hasText("¿Qué idioma quieres aprender?"), 10_000)
        compose.onNodeWithText("Ahora no").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Traducción en tu móvil"), 5_000)
        assertTrue(runBlocking { preferences.preferences.first().learnLanguageChosen })
    }

    @Test
    fun firstLaunchOffersTranslationSetupAndCanBeSkipped() {
        compose.waitUntilAtLeastOneExists(hasText("Traducción en tu móvil"), 10_000)
        compose.onNodeWithText("Ahora no").performClick()
        compose.waitUntilDoesNotExist(hasText("Traducción en tu móvil"), 5_000)
        compose.waitUntilAtLeastOneExists(hasText("Biblioteca"), 5_000)
        assertTrue(runBlocking { preferences.preferences.first().translationSetupDone })
    }
}
