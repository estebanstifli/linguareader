package com.linguareader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Integración extremo a extremo con DI real salvo el motor de voz (falso) y la BD (en memoria).
 * Demuestra que la UI funciona con otro motor sin cambios.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ReaderFlowTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var preferences: UserPreferencesRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { preferences.update { UserPreferences() } }
    }

    private fun openRedKite() {
        compose.waitUntilAtLeastOneExists(hasText("The Red Kite"), 10_000)
        compose.onNodeWithText("The Red Kite").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Tom is eight years old.", substring = true), 10_000)
    }

    @Test
    fun catalogListsBothDemoBooks() {
        compose.waitUntilAtLeastOneExists(hasText("The Red Kite"), 10_000)
        compose.onNodeWithText("Le marché du samedi").assertExists()
    }

    @Test
    fun wordEventsFromEngineEnableWordLevelSync() {
        openRedKite()
        compose.onNodeWithContentDescription("Reproducir").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sincronía por palabra", substring = true), 5_000)
    }

    @Test
    fun sentenceActionShowsBundledTranslation() {
        openRedKite()
        compose.onNodeWithText("Tom is eight years old.", substring = true)
            .performCustomAccessibilityActionWithLabel("Traducir frase")
        compose.waitUntilAtLeastOneExists(hasText("Tom tiene ocho años."), 5_000)
    }

    @Test
    fun tappingAWordWithoutGlossaryEntrySaysUnavailable() {
        openRedKite()
        // Primera palabra del pasaje: "Tom" (nombre propio, sin entrada en el glosario).
        compose.onNodeWithText("Tom is eight years old.", substring = true).performTouchInput {
            click(Offset(15f, 30f)) // dentro de la primera línea, sobre la primera palabra
        }
        compose.waitUntilAtLeastOneExists(hasText("no disponible en este libro", substring = true), 5_000)
    }
}
