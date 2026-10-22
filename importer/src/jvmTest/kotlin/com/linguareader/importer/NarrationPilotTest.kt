package com.linguareader.importer

import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.narration.AssKaraoke
import com.linguareader.core.narration.NarrationAligner
import com.linguareader.importer.extract.EpubExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Piloto de narración pregrabada: el ASS de karaoke de `test_mp3/` (no versionado) frente al texto que tendrá la app
 * al importar el EPUB de la biblioteca. Se salta si faltan los ficheros.
 */
class NarrationPilotTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val ass = File(root, "test_mp3/The Three Little Pigs.ass")
    private val epub = File(root, "content/graded-books/en/A1/the-three-little-pigs.epub")

    @Test
    fun parsesKaraokeTimes() {
        val words = AssKaraoke.parse(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.00,0:00:04.88,Karaoke,,0,0,0,,{\kf28}Once{\kf28} upon{\kf14} a {\kf10}ti{\kf20}me,
            """.trimIndent(),
        )
        assertEquals(listOf("Once", "upon", "a", "time,"), words.map { it.text })
        assertEquals(2000L, words[0].startMs)
        assertEquals(2280L, words[0].endMs)
        assertEquals(2700L, words[3].startMs)
        assertEquals(3000L, words[3].endMs)
    }

    @Test
    fun libraryEpubDeclaresItsNarration() {
        assumeTrue(epub.isFile)
        val doc = EpubExtractor().extract(DocumentSource(epub, epub.name, "application/epub+zip"))
        val narration = doc.narration
        assumeTrue(narration != null) // EPUB generado antes de publicar la narración
        assertTrue(narration!!.audio.url.startsWith("https://") && narration.audio.url.endsWith(".mp3"))
        assertEquals(64, narration.audio.sha256?.length)
        assertTrue((narration.timings.sizeBytes ?: 0) > 0)
        val imported = ImportedBookBuilder().build(
            doc, ImportOptions("u-lib-en-the-three-little-pigs", doc.title ?: "", doc.authors, "en", CefrLevel.A1, epub.name),
        )
        assertEquals(narration, BookManifestParser.toDomain(imported.manifest).narration)
    }

    @Test
    fun alignsPilotNarrationWithLibraryText() {
        assumeTrue(ass.isFile && epub.isFile)
        val doc = EpubExtractor().extract(DocumentSource(epub, epub.name, "application/epub+zip"))
        val imported = ImportedBookBuilder().build(
            doc, ImportOptions("u-lib-en-the-three-little-pigs", doc.title ?: "", doc.authors, "en", CefrLevel.A1, epub.name),
        )
        val book = BookManifestParser.toDomain(imported.manifest)
        val words = AssKaraoke.parse(ass.readText())
        val tl = NarrationAligner.align(book, words)

        println("Narración: ${words.size} palabras; libro: ${tl.totalTokens} tokens; casados ${tl.matchedTokens} " +
            "(${"%.1f".format(tl.coverage * 100)} %); palabras de la narración sin casar: ${tl.unmatchedNarrationWords}")
        val estimated = tl.words.filter { it.estimated }
        val byId = book.passages.flatMap { it.tokens }.associateBy { it.id }
        println("Tokens sin tiempo propio (${estimated.size}): " + estimated.take(40).joinToString { byId[it.tokenId]?.text ?: it.tokenId })
        val untimed = book.passages.filter { tl.passageRange(it.index) == null }
        println("Pasajes sin tiempos: ${untimed.size} de ${book.passages.size}")

        assertTrue("cobertura ${tl.coverage}", tl.coverage > 0.97)
        assertTrue(tl.words.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
        assertTrue(untimed.isEmpty())
    }
}
