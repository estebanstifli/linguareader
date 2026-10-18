package com.linguareader

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.engine.importing.PdfExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractorRegistry
import com.linguareader.importer.ImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** PDF real generado en el dispositivo con PdfDocument (texto vectorial, como un PDF exportado). */
@RunWith(AndroidJUnit4::class)
class PdfImportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = ExtractorRegistry.default(extra = listOf(PdfExtractor(context)))

    private fun pdf(name: String, pages: List<List<String>>): File {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 12f }
        pages.forEachIndexed { index, lines ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
            lines.forEachIndexed { i, line -> if (line.isNotEmpty()) page.canvas.drawText(line, 40f, 40f + i * 18f, paint) }
            document.finishPage(page)
        }
        return File(context.cacheDir, name).also { file -> file.outputStream().use(document::writeTo); document.close() }
    }

    @Test
    fun extractsTextWithoutRunningHeadersOrPageNumbers() {
        val bodies = listOf(
            listOf("The storm arrived at dusk.", "Waves broke over the rocks.", "Nobody slept in the village."),
            listOf("The keeper climbed the stairs.", "He lit the great lamp.", "Its light swept the dark sea."),
            listOf("A small boat appeared.", "It turned towards the harbour.", "The sailors were safe."),
            listOf("Morning came slowly.", "The sea was calm again.", "Everyone thanked the keeper."),
        )
        val pages = bodies.mapIndexed { i, body -> listOf("The Lighthouse", "") + body + listOf("", "", "${i + 1}") }
        val doc = registry.extract(DocumentSource(pdf("story.pdf", pages), "story.pdf"))
        assertEquals("pdf", doc.format)
        val text = doc.chapters.flatMap { c -> c.blocks.filterIsInstance<ExtractedBlock.Paragraph>().map { it.text } }.joinToString(" ")
        assertTrue(text, text.contains("The storm arrived at dusk."))
        assertTrue(text, text.contains("Everyone thanked the keeper."))
        assertTrue("Cabecera repetida: $text", !text.contains("The Lighthouse"))
        assertTrue("Número de página: $text", !Regex("\\b[1-4]\\.?\\s*$").containsMatchIn(text.trim()))
    }

    @Test
    fun scannedPdfWithoutTextIsRejected() {
        val file = pdf("scan.pdf", listOf(listOf(""), listOf("")))
        try {
            registry.extract(DocumentSource(file, "scan.pdf"))
            fail("Debía rechazar un PDF sin texto")
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.NO_TEXT, e.reason)
        }
    }
}
