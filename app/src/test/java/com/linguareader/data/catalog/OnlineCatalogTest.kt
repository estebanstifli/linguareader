package com.linguareader.data.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.IOException

class OnlineCatalogTest {
    @Test fun ownedCoverIsBoundedAndPreservedForOfflineImport() = runBlocking {
        val book = OnlineBook(1342, "Pride", cover_url = "https://library.example/cover.webp", r2_key = "ai/1342/cover.webp")
        val bytes = "RIFFxxxxWEBPtest".toByteArray()
        val result = reviewedCatalogCover(book) { url, limit ->
            assertEquals(book.cover_url, url)
            assertEquals(5L * 1024 * 1024, limit)
            bytes
        }
        assertArrayEquals(bytes, result!!.bytes)
        assertNull(reviewedCatalogCover(book.copy(r2_key = null)) { _, _ -> error("Should not download source covers") })
    }
    @Test fun missingOrInvalidCoverFallsBackButCancellationPropagates() = runBlocking {
        val book = OnlineBook(1342, "Pride", cover_url = "https://library.example/cover.webp", r2_key = "ai/1342/cover.webp")
        assertNull(reviewedCatalogCover(book) { _, _ -> throw IOException("Offline") })
        assertNull(reviewedCatalogCover(book) { _, _ -> "<html>error</html>".toByteArray() })
        try {
            reviewedCatalogCover(book) { _, _ -> throw CancellationException("Cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun searchEncodesSeparatorsAndCombinesFilters() {
        val query = LingoOnlineCatalog.query(CatalogFilter("Austen & Brontë?", "en", "romance", "B1"), 2)
        val params = query.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("Austen & Brontë?", params["q"])
        assertEquals("en", params["language"])
        assertEquals("romance", params["category"])
        assertEquals("B1", params["level"])
        assertEquals("2", params["page"])
        assertEquals("popular", params["sort"])
    }
    @Test fun emptyFiltersAreOmittedAndPageStartsAtOne() {
        assertEquals("page=1&limit=24&sort=popular", LingoOnlineCatalog.query(CatalogFilter(), 0))
    }
    @Test fun readsNullableMetadataAndIgnoresNewApiFields() {
        val json = Json { ignoreUnknownKeys = true }
        val page = json.decodeFromString<CatalogPage>("""{"data":[{"id":1342,"title":"Pride and Prejudice","author_names":null,"cover_url":null,"cefr_estimated":null,"description":null,"languages":"en, fr","download_count":100}],"pagination":{"page":1,"pages":1,"total":1},"api_version":"v1"}""")
        assertEquals(listOf("en", "fr"), page.data.single().languageTags)
        assertNull(page.data.single().download)
        assertNull(page.data.single().cefr_estimated)
    }
    @Test fun prefersEpubAndFallsBackToTextWithoutDownloadingHtml() {
        val text = CatalogFormat("txt", "https://example.org/book.txt")
        val epub = CatalogFormat("epub", "https://example.org/book.epub")
        val html = CatalogFormat("html", "https://example.org/book.html")
        val book = OnlineBook(1, "Book", formats = listOf(html, text, epub))
        assertEquals(epub, book.download)
        assertEquals(text, book.copy(formats = listOf(html, text)).download)
        assertNull(book.copy(formats = listOf(html)).download)
    }
    @Test fun rejectsUnencryptedAndCredentialBearingUrls() {
        listOf("http://example.org/book", "file:///data/book", "https://user:password@example.org/book", "javascript:alert(1)").forEach {
            assertTrue(runCatching { CatalogHttp.checkedUrl(it) }.isFailure)
        }
        assertEquals("www.gutenberg.org", CatalogHttp.checkedUrl("https://www.gutenberg.org/ebooks/1342.epub3.images").host)
    }
}
