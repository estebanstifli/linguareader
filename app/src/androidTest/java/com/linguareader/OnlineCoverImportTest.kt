package com.linguareader

import android.graphics.BitmapFactory
import com.linguareader.core.content.Hashing
import com.linguareader.core.content.MediaResult
import com.linguareader.core.model.CefrLevel
import com.linguareader.data.catalog.*
import com.linguareader.data.content.ImportedBookRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Real preview API, R2, EPUB extraction and local media storage. Requires published cover 1342. */
class OnlineCoverImportTest : KoinComponent {
    private val importer: OnlineBookImporter by inject()
    private val imported: ImportedBookRepository by inject()

    @Test fun reviewedR2CoverReplacesEpubCoverAndIsStoredLocally() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val response = CatalogHttp.bytes("https://lingoaudiobook-preview.estebandezafra.workers.dev/api/v1/books/1342", 1024 * 1024)
        val book = json.decodeFromJsonElement<OnlineBook>(json.parseToJsonElement(response.toString(Charsets.UTF_8)).jsonObject.getValue("data"))
        assertNotNull(book.r2_key)
        val remote = CatalogHttp.bytes(requireNotNull(book.cover_url), 5L * 1024 * 1024)
        val provider = object : OnlineCatalog {
            override val id = "cover-preview-check"
            override val name = "Cover verification"
            override suspend fun book(id: Int) = book
            override suspend fun books(filter: CatalogFilter, page: Int): CatalogPage = error("Not used")
            override suspend fun categories() = emptyList<CatalogCategory>()
            override suspend fun languages() = emptyList<CatalogLanguage>()
        }
        val id = importer.bookId(provider, book)
        try {
            assertEquals(id, importer.add(provider, book, CefrLevel.B1))
            val stored = imported.loadBook(id)
            val cover = requireNotNull(stored.cover)
            assertTrue(cover.path.endsWith(".webp"))
            assertFalse(cover.path.startsWith("http"))
            assertEquals(Hashing.sha256Hex(remote), cover.sha256)
            // loadMedia reads the local repository and verifies its SHA-256, without an HTTP request.
            val local = imported.loadMedia(id, cover) as MediaResult.Ok
            assertArrayEquals(remote, local.bytes)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(local.bytes, 0, local.bytes.size))
            assertEquals(480, bitmap.width)
            assertEquals(720, bitmap.height)
            bitmap.recycle()
        } finally {
            imported.delete(id)
        }
    }
}
