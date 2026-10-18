package com.linguareader

import android.content.Context
import android.net.Uri
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.engine.TranslationRequest
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.TranslationUnit
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.content.BookImportService
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.engine.translation.TranslationModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * ML Kit real en el dispositivo: descarga de modelos (requiere red; si no hay, se omite),
 * traducción con caché y pretraducción completa de un libro importado.
 */
@HiltAndroidTest
class MlKitTranslationTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var models: TranslationModelManager
    @Inject lateinit var engine: TranslationEngine
    @Inject lateinit var importer: BookImportService
    @Inject lateinit var imported: ImportedBookRepository
    @Inject lateinit var bookTranslations: BookTranslationService
    @Inject lateinit var preferences: UserPreferencesRepository
    @Inject @ApplicationContext lateinit var context: Context

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            preferences.update { UserPreferences(translationSetupDone = true, translationWifiOnly = false, translationLanguage = "es") }
            val ready = withTimeout(180_000) { models.awaitDownload("fr") && models.awaitDownload("es") }
            assumeTrue("Sin red para descargar los modelos de ML Kit: ${models.downloads.value}", ready)
        }
    }

    private fun request(text: String) = TranslationRequest(
        text = text, contextBefore = "", contextAfter = "", sourceLanguage = "fr", targetLanguage = "es", unit = TranslationUnit.SENTENCE,
    )

    @Test
    fun translatesOnDeviceAndCachesTheResult() = runBlocking {
        val first = engine.translate(request("Le chat dort sur la chaise."))
        assertTrue("$first", first is TranslationResult.Translated)
        val text = (first as TranslationResult.Translated).text.lowercase()
        assertTrue(text, "gato" in text)
        assertEquals("mlkit", first.providerId)

        val started = System.nanoTime()
        val second = engine.translate(request("Le chat dort sur la chaise."))
        assertEquals(first.text, (second as TranslationResult.Translated).text)
        assertTrue("La segunda vez debe salir de la caché", (System.nanoTime() - started) / 1_000_000 < 200)
    }

    @Test
    fun unsupportedOrMissingModelsAreReportedNotInvented() = runBlocking {
        val result = engine.translate(request("x").copy(sourceLanguage = "tlh")) // klingon: no soportado
        assertEquals(TranslationResult.Reason.LANGUAGE_PAIR_UNSUPPORTED, (result as TranslationResult.Unavailable).reason)
    }

    @Test
    fun cancelledPretranslationKeepsItsProgressAndCanBeContinued() = runBlocking {
        // Frases únicas (sin aciertos de caché) para que dé tiempo a cancelar a mitad.
        val text = "Chapitre 1\n\n" + (1..250).joinToString(" ") { "Le chat numéro $it dort sur la chaise numéro $it." }
        val uri = Uri.fromFile(File(context.cacheDir, "chats.txt").apply { writeText(text) })
        val id = importer.commit(importer.read(uri), "Les chats", "", "fr", CefrLevel.A2)
        try {
            bookTranslations.start(id, "es")
            withTimeout(60_000) {
                bookTranslations.states.first { (it[id] as? BookTranslationState.Running)?.done?.let { d -> d >= 5 } == true }
            }
            bookTranslations.cancel(id)

            // Queda pausado con su progreso, sin reanudarse solo ni marcarse como terminado.
            val paused = withTimeout(10_000) { imported.translationStatus.first { it[id]?.paused != null } }.getValue(id)
            assertEquals("es", paused.paused!!.target)
            assertTrue("done=${paused.paused!!.done}", paused.paused!!.done in 5 until paused.paused!!.total)
            assertTrue(paused.pending == null && "es" !in paused.done)
            assertTrue(bookTranslations.states.value[id] == null)

            // Continuar: lo ya traducido sale de la caché y el libro termina.
            bookTranslations.start(id, "es")
            val finished = withTimeout(120_000) { imported.translationStatus.first { "es" in it[id]?.done.orEmpty() } }.getValue(id)
            assertTrue(finished.paused == null && finished.pending == null)
        } finally {
            bookTranslations.cancel(id)
            importer.delete(id)
        }
    }

    @Test
    fun wholeImportedBookIsPretranslated() = runBlocking {
        val text = "Chapitre 1\n\nLe matin, la mer était calme. Les bateaux sortaient du port.\n\n" +
            "Chapitre 2\n\nLe soir, le vent est revenu. Tout le monde est rentré à la maison."
        val uri = Uri.fromFile(File(context.cacheDir, "mer.txt").apply { writeText(text) })
        val draft = importer.read(uri)
        val id = importer.commit(draft, "La mer", "", "fr", CefrLevel.A2)
        try {
            bookTranslations.start(id, "es")
            withTimeout(120_000) {
                imported.translationStatus.first { "es" in it[id]?.done.orEmpty() }
            }
            assertTrue(bookTranslations.states.value[id] !is BookTranslationState.Failed)
            val book = imported.loadBook(id)
            val sentence = book.passages.first().sentences.first()
            val cached = engine.translate(request(sentence.text))
            assertTrue("$cached", cached is TranslationResult.Translated)
        } finally {
            importer.delete(id)
        }
    }
}
