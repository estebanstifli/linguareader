package com.linguareader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.content.BookImportService
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.ScanSettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
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
    @Inject lateinit var scanSettings: ScanSettingsRepository
    @Inject lateinit var importer: BookImportService

    private lateinit var bookId: String

    @Before
    fun setUp() {
        hilt.inject()
        grantNotifications()
        // Sin las pantallas iniciales (modo y traducción), que taparían la biblioteca.
        runBlocking { skipOnboarding(preferences, scanSettings, UserPreferences(translationSetupDone = true, ttsModeChosen = true, translationLanguage = "es")) }
        // La app ya no incluye libros: cada prueba importa el suyo.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.fromFile(File(target.cacheDir, "reader-flow.txt").apply { writeText(BOOK_TEXT) })
        bookId = runBlocking { importer.commit(importer.read(uri), BOOK_TITLE, "", "en", CefrLevel.A2) }
    }

    @After
    fun tearDown() {
        runBlocking { importer.delete(bookId) }
    }

    /** Los libros importados van primero: el incluido puede estar fuera de pantalla. */
    private fun scrollToBook(title: String) {
        compose.waitUntilAtLeastOneExists(hasScrollToNodeAction(), 10_000) // catálogo cargado
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
    }

    private fun openBook() {
        scrollToBook(BOOK_TITLE)
        openFromCatalog(compose, BOOK_TITLE)
        compose.waitUntilAtLeastOneExists(hasText(FIRST_SENTENCE, substring = true), 10_000)
    }

    @Test
    fun catalogListsTheImportedBook() {
        scrollToBook(BOOK_TITLE)
        compose.onNodeWithText(BOOK_TITLE).assertExists()
    }

    @Test
    fun wordEventsFromEngineEnableWordLevelSync() {
        openBook()
        compose.onNodeWithContentDescription(str(R.string.player_play)).performClick()
        compose.waitUntilAtLeastOneExists(hasText(str(R.string.player_sync_word), substring = true), 5_000)
    }

    @Test
    fun sentenceActionTranslatesWithTheDeviceTranslator() {
        openBook()
        compose.onNodeWithText(FIRST_SENTENCE, substring = true)
            .performCustomAccessibilityActionWithLabel(str(R.string.reader_translate_sentence))
        // Los libros no traen traducción: o traduce el traductor del dispositivo o pide su modelo.
        compose.waitUntilAtLeastOneExists(
            hasText(str(R.string.translation_machine), substring = true) or hasText(str(R.string.translation_unavailable_model_missing), substring = true),
            15_000,
        )
    }

    @Test
    fun tappingAWordWithoutGlossaryEntryFallsBackToMachineTranslation() {
        openBook()
        // Primera palabra del pasaje: "Tom" (sin glosario). Se usa el traductor del dispositivo:
        // o traduce (y lo indica) o pide el modelo.
        compose.onNodeWithText(FIRST_SENTENCE, substring = true).performTouchInput {
            click(Offset(15f, 30f)) // toque sobre la primera palabra: controles + minitarjeta
        }
        compose.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.word_more_info)), 15_000)
        compose.onNodeWithContentDescription(str(R.string.word_more_info)).performClick()
        compose.waitUntilAtLeastOneExists(
            hasText(str(R.string.translation_machine), substring = true) or hasText(str(R.string.translation_unavailable_model_missing), substring = true),
            15_000,
        )
    }

    private companion object {
        const val BOOK_TITLE = "Reader Flow Test Book"
        const val FIRST_SENTENCE = "Tom is eight years old."
        val BOOK_TEXT = listOf(
            "Tom is eight years old. He lives in a small house near the sea with his mother and his dog.",
            "Every morning he walks to school along the beach. He likes the sound of the waves.",
        ).joinToString("\n\n")
    }
}
