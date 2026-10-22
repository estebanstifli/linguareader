package com.linguareader.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.db.BookmarkEntity
import com.linguareader.data.db.CollectionBookEntity
import com.linguareader.data.db.CollectionEntity
import com.linguareader.data.db.DeviceDocumentEntity
import com.linguareader.data.db.MachineTranslationEntity
import com.linguareader.data.db.ReadingProgressEntity
import com.linguareader.data.db.VocabularyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La base de datos compartida abierta con el SQLite empaquetado, sin Android: el `_Impl` generado para JVM. */
class AppDatabaseTest {

    private val db: AppDatabase = Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    @After
    fun close() = db.close()

    @Test
    fun `progress is upserted per book`() = runTest {
        val dao = db.progressDao()
        dao.upsert(ReadingProgressEntity("b1", "ch1-p1", 0, 10, 0, 1))
        dao.upsert(ReadingProgressEntity("b1", "ch1-p3", 2, 10, 42, 2))
        assertEquals(42, dao.get("b1")?.charOffset)
        assertEquals(1, dao.observeAll().first().size)
        assertNull(dao.get("other"))
    }

    @Test
    fun `duplicate bookmarks are ignored and listed in reading order`() = runTest {
        val dao = db.bookmarkDao()
        val first = dao.insert(BookmarkEntity(bookId = "b1", passageId = "p2", passageIndex = 1, charOffset = 5, excerpt = "x", createdAt = 1))
        val dup = dao.insert(BookmarkEntity(bookId = "b1", passageId = "p2", passageIndex = 1, charOffset = 5, excerpt = "y", createdAt = 2))
        dao.insert(BookmarkEntity(bookId = "b1", passageId = "p1", passageIndex = 0, charOffset = 9, excerpt = "z", createdAt = 3))
        assertTrue(first > 0)
        assertEquals(-1L, dup)
        assertEquals(listOf("p1", "p2"), dao.observeForBook("b1").first().map { it.passageId })
    }

    @Test
    fun `vocabulary exists per book, term and target language`() = runTest {
        val dao = db.vocabularyDao()
        dao.upsert(VocabularyEntity(bookId = "b1", bookTitle = "T", term = "fox", lemma = null, translation = "zorro", sourceLanguage = "en", targetLanguage = "es", context = "the fox", createdAt = 1))
        assertTrue(dao.exists("b1", "fox", "es"))
        assertFalse(dao.exists("b1", "fox", "fr"))
    }

    @Test
    fun `machine translations are keyed by engine, languages and text hash`() = runTest {
        val dao = db.machineTranslationDao()
        dao.put(MachineTranslationEntity("mlkit", "en", "es", "h1", "hola", 1))
        assertEquals("hola", dao.get("mlkit", "en", "es", "h1"))
        assertNull(dao.get("mlkit", "en", "fr", "h1"))
        dao.deleteLanguage("es")
        assertNull(dao.get("mlkit", "en", "es", "h1"))
    }

    @Test
    fun `system shelves come first and deleting a collection removes its books`() = runTest {
        val dao = db.libraryDao()
        val custom = dao.insertCollection(CollectionEntity(name = "Mine", kind = "custom", createdAt = 1))
        dao.insertCollection(CollectionEntity(name = "Favoritos", kind = "favorites", createdAt = 2))
        dao.addToCollection(CollectionBookEntity(custom, "b1", 3))
        assertEquals(listOf("favorites", "custom"), dao.observeCollections().first().map { it.kind })
        assertEquals(1, dao.observeMemberships().first().size)
        dao.deleteCollection(custom)
        assertTrue(dao.observeMemberships().first().isEmpty()) // ON DELETE CASCADE
    }

    @Test
    fun `device documents report stamps and clear`() = runTest {
        val dao = db.deviceDocumentDao()
        dao.upsert(listOf(DeviceDocumentEntity("/a.epub", "a.epub", "epub", 10, 20, "/", "A", null, null, 1)))
        assertEquals(listOf("/a.epub"), dao.stamps().map { it.location })
        dao.clear()
        assertTrue(dao.observeAll().first().isEmpty())
    }
}
