package com.linguareader.importer

import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ficheros mínimos construidos en el test: sin contenido de terceros en el repositorio. */
object Fixtures {

    /** PNG válido de más de 2 KB (el walker descarta adornos más pequeños). */
    val PNG: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(4096) { (it % 251).toByte() }

    /** Otra imagen (distinta de la portada) para ilustraciones interiores. */
    val PNG2: ByteArray = PNG + byteArrayOf(1, 2, 3)

    fun zip(folder: TemporaryFolder, name: String, entries: List<Pair<String, ByteArray>>, storedFirst: Boolean = true): File {
        val file = folder.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEachIndexed { index, (path, bytes) ->
                val entry = ZipEntry(path)
                if (index == 0 && storedFirst) {
                    // EPUB exige "mimetype" primero y sin comprimir: así aparece en la cabecera del ZIP.
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    fun epub(folder: TemporaryFolder, drm: Boolean = false, epub3: Boolean = true): File {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="${if (epub3) "3.0" else "2.0"}" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>The Lighthouse Keeper</dc:title>
                <dc:creator>Ana Test</dc:creator>
                <dc:language>en-GB</dc:language>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                ${if (epub3) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""}
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover-img" href="images/cover.png" media-type="image/png"/>
                <item id="css" href="style.css" media-type="text/css"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()
        val nav = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol>
              <li><a href="text/chapter%201.xhtml">One: The Storm</a></li>
              <li><a href="text/chapter2.xhtml#start">Two: Morning</a></li>
            </ol></nav></body></html>
        """.trimIndent()
        val ncx = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
              <navPoint id="n1"><navLabel><text>Chapter One</text></navLabel><content src="text/chapter%201.xhtml"/></navPoint>
              <navPoint id="n2"><navLabel><text>Chapter Two</text></navLabel><content src="text/chapter2.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        val chapter1 = """
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><style>p{}</style></head><body>
              <h1>The Storm</h1>
              <p>The wind was strong. Mr. Gray closed the door<sup><a href="#n1">1</a></sup>.</p>
              <div><img src="../images/lighthouse.png" alt="A lighthouse"/></div>
              <p>He lit the lamp, and the light went out over the sea.</p>
              <p>* * *</p>
            </body></html>
        """.trimIndent()
        val chapter2 = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <p id="start">In the morning the sea was calm. It was a new day.</p>
              <span epub:type="pagebreak">12</span>
            </body></html>
        """.trimIndent()
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val entries = mutableListOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/toc.ncx" to ncx.toByteArray(),
            "OEBPS/text/chapter 1.xhtml" to chapter1.toByteArray(),
            "OEBPS/text/chapter2.xhtml" to chapter2.toByteArray(),
            "OEBPS/images/cover.png" to PNG,
            "OEBPS/images/lighthouse.png" to PNG2,
            "OEBPS/style.css" to "p{}".toByteArray(),
        )
        if (epub3) entries += "OEBPS/nav.xhtml" to nav.toByteArray()
        if (drm) {
            entries += "META-INF/encryption.xml" to """
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                  <enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
                  <enc:CipherData><enc:CipherReference URI="OEBPS/text/chapter2.xhtml"/></enc:CipherData></enc:EncryptedData>
                </encryption>
            """.trimIndent().toByteArray()
        }
        return zip(folder, if (drm) "drm.epub" else "book.epub", entries)
    }

    fun fb2(encoding: String = "UTF-8"): String = """
        <?xml version="1.0" encoding="$encoding"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info>
            <author><first-name>Lucía</first-name><last-name>Pérez</last-name></author>
            <book-title>El faro</book-title>
            <lang>es</lang>
            <coverpage><image l:href="#cover.png"/></coverpage>
          </title-info></description>
          <body>
            <title><p>El faro</p></title>
            <section>
              <title><p>Capítulo 1</p><p>La tormenta</p></title>
              <p>El viento soplaba con fuerza.<a l:href="#n1" type="note">[1]</a> Nadie salió.</p>
              <empty-line/>
              <poem><stanza><v>Mar en calma,</v><v>cielo gris.</v></stanza></poem>
            </section>
            <section>
              <title><p>Capítulo 2</p></title>
              <p>Por la mañana, el mar estaba tranquilo.</p>
            </section>
          </body>
          <body name="notes"><section id="n1"><p>Una nota que no se lee.</p></section></body>
          <binary id="cover.png" content-type="image/png">${java.util.Base64.getEncoder().encodeToString(PNG)}</binary>
        </FictionBook>
    """.trimIndent()
}
