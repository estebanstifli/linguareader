package com.linguareader.engine.alignment

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredAlignmentModelsTest {
    private fun FakeFileSystem.put(path: String, text: String = "keep") {
        val p = path.toPath()
        createDirectories(p.parent!!)
        write(p) { writeUtf8(text) }
    }

    @Test
    fun `upgrade removes all obsolete downloads and partial files but preserves speech and books`() {
        FakeFileSystem().use { fs ->
            fs.put("/app/files/models/alignment/w2v2-en/rev/model.onnx", "old")
            fs.put("/app/files/models/alignment/w2v2-es/rev/model.onnx.part", "partial")
            val keep = listOf("/app/files/models/supertonic-3/rev/model.onnx", "/app/files/audio-cache/clip.wav.marks.json", "/app/files/audio-cache/clip.wav.alignment.json", "/app/files/books/book.epub")
            keep.forEach { fs.put(it) }
            val cleanup = RetiredAlignmentModels(fs, "/app/files".toPath())
            cleanup.remove(); cleanup.remove()
            assertFalse(fs.exists("/app/files/models/alignment".toPath()))
            keep.forEach { assertEquals("keep", fs.read(it.toPath()) { readUtf8() }) }
        }
    }

    @Test
    fun `cleanup refuses redirected root and does not follow nested links`() {
        FakeFileSystem().apply { allowSymlinks = true }.use { fs ->
            fs.put("/outside/protected", "private")
            fs.createDirectories("/app/files/models".toPath())
            fs.createSymlink("/app/files/models/alignment".toPath(), "/outside".toPath())
            val cleanup = RetiredAlignmentModels(fs, "/app/files".toPath())
            cleanup.remove()
            assertTrue(fs.exists("/outside/protected".toPath()))
            fs.delete("/app/files/models/alignment".toPath())
            fs.createDirectories("/app/files/models/alignment".toPath())
            fs.createSymlink("/app/files/models/alignment/foreign".toPath(), "/outside".toPath())
            cleanup.remove()
            assertFalse(fs.exists("/app/files/models/alignment".toPath()))
            assertEquals("private", fs.read("/outside/protected".toPath()) { readUtf8() })
        }
    }
}
