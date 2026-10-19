package com.linguareader.engine.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryArchiveTest {

    @get:Rule val folder = TemporaryFolder()

    private val epub = "fake epub bytes".toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(sha256: String = sha(epub), file: String = "books/A1/tale.epub") = """
        {"formatVersion": 1, "language": "es", "version": 2, "books": [
          {"id": "lib-es-tale", "file": "$file", "title": "Cuento", "authors": ["Hermanos Grimm"],
           "level": "A1", "words": 3000, "sizeBytes": ${epub.size}, "sha256": "$sha256"}
        ]}
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return file
    }

    @Test
    fun unpacksAndVerifiesBooks() {
        val staging = File(folder.root, "staging")
        val m = LibraryArchive.unpack(zip("library.json" to manifest().toByteArray(), "books/A1/tale.epub" to epub), staging)
        assertEquals("es", m.language)
        assertEquals(2, m.version)
        assertEquals("lib-es-tale", m.books.single().id)
        assertTrue(File(staging, "books/A1/tale.epub").isFile)
    }

    @Test
    fun rejectsCorruptBook() {
        val bad = zip("library.json" to manifest(sha256 = "0".repeat(64)).toByteArray(), "books/A1/tale.epub" to epub)
        assertThrows(IOException::class.java) { LibraryArchive.unpack(bad, File(folder.root, "s1")) }
    }

    @Test
    fun rejectsZipSlipEntries() {
        val evil = zip("library.json" to manifest().toByteArray(), "../evil.txt" to epub)
        assertThrows(IOException::class.java) { LibraryArchive.unpack(evil, File(folder.root, "s2")) }
    }

    @Test
    fun rejectsManifestPathsOutsideStaging() {
        val evil = zip("library.json" to manifest(file = "../outside.epub").toByteArray(), "books/A1/tale.epub" to epub)
        assertThrows(IOException::class.java) { LibraryArchive.unpack(evil, File(folder.root, "s3")) }
    }

    @Test
    fun requiresManifest() {
        assertThrows(IOException::class.java) { LibraryArchive.unpack(zip("books/A1/tale.epub" to epub), File(folder.root, "s4")) }
    }
}
