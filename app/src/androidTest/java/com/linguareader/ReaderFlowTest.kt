package com.linguareader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
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
        // Sin la pantalla inicial de traducción, que taparía la biblioteca.
        runBlocking { preferences.update { UserPreferences(translationSetupDone = true) } }
    }

    /** Los libros importados van primero: el incluido puede estar fuera de pantalla. */
    private fun scrollToBook(title: String) {
        compose.waitUntilAtLeastOneExists(hasText("pasajes", substring = true), 10_000) // catálogo cargado
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
    }

    private fun openRedKite() {
        scrollToBook("The Red Kite")
        compose.onNodeWithText("The Red Kite").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Tom is eight years old.", substring = true), 10_000)
    }

    @Test
    fun catalogListsBothDemoBooks() {
        scrollToBook("The Red Kite")
        scrollToBook("Le marché du samedi")
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
    fun tappingAWordWithoutGlossaryEntryFallsBackToMachineTranslation() {
        openRedKite()
        // Primera palabra del pasaje: "Tom" (nombre propio, sin entrada en el glosario). Sin dato
        // incluido, se usa el traductor del dispositivo: o traduce (y lo indica) o pide el modelo.
        compose.onNodeWithText("Tom is eight years old.", substring = true).performTouchInput {
            click(Offset(15f, 30f)) // dentro de la primera línea, sobre la primera palabra
        }
        compose.waitUntilAtLeastOneExists(
            hasText("Traducción automática", substring = true) or hasText("Falta descargar el traductor", substring = true),
            15_000,
        )
    }
}
