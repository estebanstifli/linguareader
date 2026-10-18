package com.linguareader.importer

import com.linguareader.importer.extract.MobiExtractor
import com.linguareader.importer.extract.PalmDoc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class MobiExtractorTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `PalmDOC descomprime literales, espacio más carácter y referencias hacia atrás`() {
        // "abcabcab": a b c (literales) + referencia distancia 3, longitud 5.
        val backRef = (0x8000 or (3 shl 3) or (5 - 3))
        val input = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), (backRef shr 8).toByte(), backRef.toByte())
        assertEquals("abcabcab", String(PalmDoc.decompress(input), Charsets.US_ASCII))
        // 0xC0 | 'x' ^ 0x80 → " x"; 0x02 → dos literales (pueden ser bytes altos).
        val special = byteArrayOf('H'.code.toByte(), ('x'.code xor 0x80).toByte(), 0x02, 0xC3.toByte(), 0xA9.toByte())
        assertEquals("H xé", String(PalmDoc.decompress(special), Charsets.UTF_8))
    }

    @Test
    fun `mobi sin comprimir con EXTH, imágenes por recindex y portada`() {
        val html = """<html><body><h2>Chapter One</h2><p>It was a quiet town.</p><img recindex="00001"/>""" +
            """<mbp:pagebreak/><h2>Chapter Two</h2><p>The rain stopped. Everyone smiled.</p></body></html>"""
        val file = mobi(html.toByteArray(), compressed = false)
        val doc = ExtractorRegistry.default().extract(DocumentSource(file, "town.azw3"))
        assertEquals("mobi", doc.format)
        assertEquals("The Quiet Town", doc.title)
        assertEquals(listOf("Ana Writer"), doc.authors)
        assertEquals("en-GB", doc.language)
        assertEquals(listOf("Chapter One", "Chapter Two"), doc.chapters.map { it.title })
        assertEquals(1, doc.chapters[0].blocks.count { it is ExtractedBlock.Image })
        assertArrayEquals(Fixtures.PNG, assertNotNull(doc.cover).let { doc.cover!!.bytes })
    }

    @Test
    fun `mobi con compresión PalmDOC y UTF-8`() {
        val html = "<p>Él caminó hacia el café. ¡Qué día!</p>"
        val doc = ExtractorRegistry.default().extract(DocumentSource(mobi(html.toByteArray(), compressed = true), "cafe.mobi"))
        assertEquals(
            listOf("Él caminó hacia el café. ¡Qué día!"),
            doc.chapters.flatMap { c -> c.blocks.filterIsInstance<ExtractedBlock.Paragraph>().map { it.text } },
        )
    }

    @Test
    fun `mobi con DRM se rechaza`() {
        try {
            ExtractorRegistry.default().extract(DocumentSource(mobi("<p>x</p>".toByteArray(), compressed = false, encryption = 2), "drm.azw"))
            fail()
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.DRM_PROTECTED, e.reason)
        }
    }

    /** Compresor PalmDOC trivial: solo tramos de literales (válido, aunque no comprime). */
    private fun literalPalmDoc(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        bytes.toList().chunked(8).forEach { chunk ->
            out.write(chunk.size)
            out.write(chunk.toByteArray())
        }
        return out.toByteArray()
    }

    /** PDB "BOOKMOBI" mínimo: registro 0 (cabeceras PalmDOC + MOBI + EXTH), texto e imagen. */
    private fun mobi(text: ByteArray, compressed: Boolean, encryption: Int = 0): java.io.File {
        val textRecord = if (compressed) literalPalmDoc(text) else text
        val title = "Fallback Name".toByteArray()

        val exth = ByteArrayOutputStream().also { e ->
            val d = DataOutputStream(e)
            val records = listOf(
                100 to "Ana Writer".toByteArray(),
                503 to "The Quiet Town".toByteArray(),
                524 to "en-GB".toByteArray(),
                201 to byteArrayOf(0, 0, 0, 0), // portada = primera imagen
            )
            d.writeBytes("EXTH")
            d.writeInt(12 + records.sumOf { 8 + it.second.size })
            d.writeInt(records.size)
            records.forEach { (type, value) -> d.writeInt(type); d.writeInt(8 + value.size); d.write(value) }
        }.toByteArray()

        val mobiHeaderLength = 0xE8
        val record0 = ByteArray(16 + mobiHeaderLength + exth.size + title.size)
        java.nio.ByteBuffer.wrap(record0).apply {
            putShort(0, if (compressed) 2 else 1)
            putInt(4, text.size)
            putShort(8, 1) // un registro de texto
            putShort(10, 4096)
            putShort(12, encryption.toShort())
            put(16, "MOBI".toByteArray())
            putInt(0x14, mobiHeaderLength)
            putInt(0x18, 2)
            putInt(0x1C, 65001)
            putInt(0x54, 16 + mobiHeaderLength + exth.size)
            putInt(0x58, title.size)
            putInt(0x6C, 2) // primera imagen = registro 2
            putInt(0x80, 0x40)
            putShort(0xF2, 0)
            put(16 + mobiHeaderLength, exth)
            put(16 + mobiHeaderLength + exth.size, title)
        }

        val records = listOf(record0, textRecord, Fixtures.PNG)
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write("Quiet Town".toByteArray().copyOf(32))
        d.write(ByteArray(28))
        d.writeBytes("BOOKMOBI")
        d.write(ByteArray(8))
        d.writeShort(records.size)
        var offset = 78 + records.size * 8 + 2
        records.forEachIndexed { i, r -> d.writeInt(offset); d.writeInt(i); offset += r.size }
        d.writeShort(0)
        records.forEach(d::write)
        return folder.newFile().apply { writeBytes(out.toByteArray()) }
    }
}
