package com.linguareader.importer.extract

import okio.FileSystem
import okio.Path
import okio.use

/** Título, autores e idioma de un documento, leídos sin extraer su texto. */
data class DocumentMetadata(val title: String?, val authors: List<String>, val language: String?)

/**
 * Metadatos rápidos para indexar documentos del dispositivo (Ajustes › Examinar archivos): en un EPUB solo se lee el
 * OPF y en un FB2 la cabecera `<title-info>`, nunca el libro entero. Los demás formatos no tienen metadatos fiables
 * baratos de leer: se usa el nombre del fichero. Nunca lanza: un fichero dañado devuelve lo que se pueda.
 */
object QuickMetadata {

    /** Lo que se lee de un FB2 como mucho: la cabecera va al principio y los libros pueden pesar varios MB. */
    private const val FB2_HEAD_BYTES = 64 * 1024

    fun read(file: Path, fileSystem: FileSystem = FileSystem.SYSTEM): DocumentMetadata {
        val name = file.name.lowercase()
        val found = runCatching {
            when {
                name.endsWith(".epub") -> epub(fileSystem, file)
                name.endsWith(".fb2") -> fb2(headText(fileSystem, file))
                name.endsWith(".fb2.zip") || name.endsWith(".fbz") -> fb2Zip(fileSystem, file)
                else -> null
            }
        }.getOrNull()
        return DocumentMetadata(
            title = found?.title?.takeIf { it.isNotBlank() } ?: titleFromName(file.name),
            authors = found?.authors.orEmpty(),
            language = found?.language,
        )
    }

    /** «El_viaje-de Ana.v2.epub» → «El viaje de Ana.v2». */
    fun titleFromName(fileName: String): String {
        val base = fileName.removeSuffix(".zip").substringBeforeLast('.', fileName)
        return base.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { fileName }
    }

    private fun epub(fileSystem: FileSystem, file: Path): DocumentMetadata? = ZipArchive(fileSystem, file).use { zip ->
        val container = EpubExtractor.xml(zip.text("META-INF/container.xml") ?: return null)
        val opfPath = container.byLocalName("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() } ?: return null
        val metadata = EpubExtractor.xml(zip.text(opfPath) ?: return null).byLocalName("metadata").firstOrNull() ?: return null
        fun meta(tag: String) = metadata.byLocalName(tag).map { it.text().trim() }.filter { it.isNotEmpty() }
        DocumentMetadata(meta("title").firstOrNull(), meta("creator"), meta("language").firstOrNull())
    }

    private fun fb2Zip(fileSystem: FileSystem, file: Path): DocumentMetadata? = ZipArchive(fileSystem, file).use { zip ->
        val entry = zip.names.firstOrNull { it.lowercase().endsWith(".fb2") } ?: return null
        zip.head(entry, FB2_HEAD_BYTES)?.let { fb2(TextDecoding.decodeMarkup(it)) }
    }

    private fun headText(fileSystem: FileSystem, file: Path): String = fileSystem.read(file) {
        val head = ByteArray(FB2_HEAD_BYTES)
        var read = 0
        while (read < head.size) {
            val n = read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        TextDecoding.decodeMarkup(head.copyOf(read))
    }

    /** Cabecera FB2 con expresiones regulares: el fragmento leído suele cortar el XML y un parser lo rechazaría. */
    internal fun fb2(head: String): DocumentMetadata? {
        val info = Regex("<title-info>(.*?)</title-info>", RegexOption.DOT_MATCHES_ALL).find(head)?.groupValues?.get(1) ?: return null
        fun tag(name: String, inside: String) =
            Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(inside)?.groupValues?.get(1)?.let(::clean)
        val authors = Regex("<author>(.*?)</author>", RegexOption.DOT_MATCHES_ALL).findAll(info).mapNotNull { m ->
            val a = m.groupValues[1]
            listOfNotNull(tag("first-name", a), tag("middle-name", a), tag("last-name", a))
                .joinToString(" ").ifBlank { tag("nickname", a) }?.takeIf { it.isNotBlank() }
        }.toList()
        return DocumentMetadata(tag("book-title", info), authors, tag("lang", info))
    }

    private fun clean(xml: String): String =
        xml.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace(Regex("\\s+"), " ").trim()
}
