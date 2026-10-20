package com.linguareader.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentWalkerTest {

    @get:Rule val folder = TemporaryFolder()

    private fun file(path: String, bytes: Int = 2048): File =
        File(folder.root, path).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    private fun found(walker: DocumentWalker): List<String> {
        val out = mutableListOf<String>()
        walker.walk(folder.root) { out += it.file.relativeTo(folder.root).invariantSeparatorsPath + ":" + it.format }
        return out.sorted()
    }

    @Test
    fun `finds supported formats in nested folders`() {
        file("Download/novela.epub")
        file("Documents/notas/apuntes.PDF")
        file("Books/clasico.fb2.zip")
        file("Music/cancion.mp3")
        file("sin-extension")
        val walker = DocumentWalker(setOf("epub", "pdf", "fb2"), includeHidden = false, exclusions = emptyList())
        assertEquals(listOf("Books/clasico.fb2.zip:fb2", "Documents/notas/apuntes.PDF:pdf", "Download/novela.epub:epub"), found(walker))
    }

    @Test
    fun `hidden folders and files are skipped unless asked for`() {
        file(".cache/libro.epub")
        file("Books/.oculto.epub")
        file("Books/visible.epub")
        assertEquals(listOf("Books/visible.epub:epub"), found(DocumentWalker(setOf("epub"), false, emptyList())))
        assertEquals(3, found(DocumentWalker(setOf("epub"), true, emptyList())).size)
    }

    @Test
    fun `excluded folders and private app data are not walked`() {
        file("Android/data/com.otra.app/libro.epub")
        file("Android/obb/juego/libro.epub")
        file("WhatsApp/Media/libro.epub")
        file("Books/libro.epub")
        val excluded = File(folder.root, "WhatsApp").absolutePath
        assertEquals(listOf("Books/libro.epub:epub"), found(DocumentWalker(setOf("epub"), false, listOf(excluded))))
    }

    @Test
    fun `tiny text files are not documents`() {
        file("notas/lista.txt", bytes = 200)
        file("libros/cuento.txt", bytes = 5000)
        assertEquals(listOf("libros/cuento.txt:txt"), found(DocumentWalker(setOf("txt"), false, emptyList())))
    }

    @Test
    fun `stops when cancelled`() {
        repeat(5) { file("d$it/libro.epub") }
        var folders = 0
        DocumentWalker(setOf("epub"), false, emptyList()).walk(folder.root, cancelled = { folders >= 2 }, onFolder = { folders++ }) {}
        assertEquals(2, folders)
    }

    @Test
    fun `format comes from the extension`() {
        assertEquals("epub", DocumentWalker.formatOf("A.Book.EPUB"))
        assertEquals("fb2", DocumentWalker.formatOf("x.fb2.zip"))
        assertNull(DocumentWalker.formatOf(".nomedia"))
        assertNull(DocumentWalker.formatOf("README"))
    }
}
