package com.linguareader

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.content.BookImportService
import com.linguareader.data.content.PendingImports
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.importer.ImportException
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/** Importación real en el dispositivo: ContentResolver → extracción → guardado → catálogo → lector. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ImportFlowTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var importer: BookImportService
    @Inject lateinit var books: BookRepository
    @Inject lateinit var preferences: UserPreferencesRepository
    @Inject lateinit var pendingImports: PendingImports
    @Inject @ApplicationContext lateinit var context: Context

    @Before
    fun setUp() {
        hilt.inject()
        // Sin la pantalla inicial de traducción, que taparía la biblioteca.
        runBlocking { preferences.update { UserPreferences(translationSetupDone = true) } }
    }

    private fun file(name: String, text: String): Uri =
        Uri.fromFile(File(context.cacheDir, name).apply { writeText(text) })

    @Test
    fun importsHtmlShowsItInCatalogAndOpensItInTheReader() {
        val html = """
            <!DOCTYPE html><html lang="es"><head><meta charset="utf-8"><title>El viejo faro</title></head>
            <body><h1>La tormenta</h1><p>El viento soplaba con fuerza. La Sra. Gómez cerró la puerta.</p>
            <h1>La mañana</h1><p>Por la mañana, el mar estaba tranquilo.</p></body></html>
        """.trimIndent()
        val id = runBlocking {
            val draft = importer.read(file("faro.html", html))
            assertEquals("es", draft.detectedLanguage)
            assertEquals("El viejo faro", draft.document.title)
            importer.commit(draft, draft.document.title!!, "Autora", "es", CefrLevel.A2)
        }
        runBlocking {
            val book = books.loadBook(id)
            assertEquals(listOf("La tormenta", "La mañana"), book.chapters.map { it.title })
            assertEquals("La Sra. Gómez cerró la puerta.", book.passages.first().sentences[1].text)
            val catalog = books.observeCatalog(CatalogFilter(learningLanguage = "es")).first()
            assertTrue(catalog.any { it.id == id && it.cefrLevel == CefrLevel.A2 })
            preferences.update { it.copy(readingLanguage = "es") }
        }

        compose.waitUntilAtLeastOneExists(hasText("El viejo faro"), 10_000)
        compose.onNodeWithText("El viejo faro").performClick()
        compose.waitUntilAtLeastOneExists(hasText("El viento soplaba con fuerza.", substring = true), 10_000)

        runBlocking {
            importer.delete(id)
            assertFalse(books.observeCatalog(CatalogFilter()).first().any { it.id == id })
        }
    }

    @Test
    fun sharedTextFromAnotherAppGoesThroughTheDialogIntoTheReader() {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Shared story")
            putExtra(android.content.Intent.EXTRA_TEXT, "The cat sat on the mat. ".repeat(30) + "\n\nThen it slept.")
        }
        // Mismo camino que MainActivity.onCreate/onNewIntent.
        assertTrue(pendingImports.offer(intent))

        compose.waitUntilAtLeastOneExists(hasText("Importar libro"), 10_000)
        compose.onNodeWithText("Importar").performClick()
        compose.waitUntilAtLeastOneExists(hasText("The cat sat on the mat.", substring = true), 10_000)

        runBlocking {
            val book = books.observeCatalog(CatalogFilter()).first().first { it.title == "Shared story" }
            importer.delete(book.id)
        }
    }

    @Test
    fun reimportingTheSameFileKeepsTheSameId() = runBlocking {
        val uri = file("notas.txt", "Chapter 1\n\nIt was late. Nobody came.\n\nChapter 2\n\nMorning arrived.")
        val first = importer.read(uri)
        val id = importer.commit(first, "Notes", "", "en", CefrLevel.B1)
        val second = importer.read(uri)
        assertEquals(id, second.bookId)
        assertTrue(second.alreadyImported)
        importer.delete(id)
    }

    @Test
    fun unsupportedFilesFailWithAClearReason() = runBlocking {
        // Cabecera OLE2 de un .doc de Word 97–2003.
        val doc = File(context.cacheDir, "viejo.doc").apply {
            writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(512))
        }
        try {
            importer.read(Uri.fromFile(doc))
            fail("Debía rechazar el formato")
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.UNSUPPORTED_FORMAT, e.reason)
            assertTrue(e.message!!.contains("DOCX"))
        }
    }
}
