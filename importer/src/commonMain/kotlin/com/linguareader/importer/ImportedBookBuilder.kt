package com.linguareader.importer

import com.linguareader.core.content.BookManifestDto
import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.content.ChapterDto
import com.linguareader.core.content.Hashing
import com.linguareader.core.content.LicenseDto
import com.linguareader.core.content.MediaDto
import com.linguareader.core.content.NarrationDto
import com.linguareader.core.content.PassageDto
import com.linguareader.core.content.RemoteFileDto
import com.linguareader.core.content.SentenceDto
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.RemoteFile
import com.linguareader.core.text.SentenceSegmenter
import com.linguareader.core.text.Tokenizer
import com.linguareader.importer.extract.ImageTypes
import kotlinx.serialization.json.Json

/** Lo que el usuario confirma (o corrige) en el diálogo de importación. */
data class ImportOptions(
    val bookId: String,
    val title: String,
    val authors: List<String>,
    /** BCP-47 del idioma de lectura. */
    val language: String,
    val level: CefrLevel,
    /** Nombre del fichero original, para la atribución. */
    val sourceName: String,
)

/** Libro listo para guardar: manifiesto en el formato de `docs/CONTENT_FORMAT.md` + ficheros de media. */
class ImportedBook(
    val manifest: BookManifestDto,
    /** Ruta relativa a la carpeta del libro → bytes. */
    val media: Map<String, ByteArray>,
) {
    fun manifestJson(): String = JSON.encodeToString(BookManifestDto.serializer(), manifest)

    private companion object {
        val JSON = Json { prettyPrint = false; encodeDefaults = false }
    }
}

/**
 * Reparte el texto extraído en capítulos → pasajes → frases. Un pasaje agrupa párrafos hasta
 * rondar [targetWords] palabras (sin partir párrafos salvo que superen [maxWords]), así el TTS
 * genera fragmentos de tamaño estable y el lector pasa de página con naturalidad.
 *
 * Una imagen del documento se convierte en la ilustración del pasaje siguiente.
 */
class ImportedBookBuilder(
    private val targetWords: Int = 120,
    private val maxWords: Int = 220,
) {

    fun build(document: ExtractedDocument, options: ImportOptions): ImportedBook {
        val media = linkedMapOf<String, ByteArray>()
        fun store(image: ExtractedImage, name: String): MediaDto? {
            val extension = ImageTypes.extension(image.bytes) ?: return null
            val path = "media/$name.$extension"
            media[path] = image.bytes
            return MediaDto(path = path, sha256 = Hashing.sha256Hex(image.bytes))
        }

        var passageCounter = 0
        var imageCounter = 0
        var pendingImage: MediaDto? = null
        val chapters = mutableListOf<ChapterDto>()

        document.chapters.forEach { chapter ->
            val passages = mutableListOf<PassageDto>()
            var sentences = mutableListOf<String>()
            var image: MediaDto? = null

            fun flush() {
                if (sentences.isEmpty()) return
                passageCounter++
                passages += PassageDto(
                    id = "p$passageCounter",
                    image = image,
                    sentences = sentences.mapIndexed { i, text -> SentenceDto(id = "s${i + 1}", text = text) },
                )
                sentences = mutableListOf()
                image = null
            }

            chapter.blocks.forEach { block ->
                when (block) {
                    is ExtractedBlock.Image -> {
                        flush()
                        val stored = store(block.image, "img-${++imageCounter}")?.copy(alt = block.alt)
                        if (stored != null) image = stored
                    }
                    is ExtractedBlock.Paragraph -> {
                        if (image == null && pendingImage != null) image = pendingImage.also { pendingImage = null }
                        val paragraph = SentenceSegmenter.split(block.text, options.language).filter(::hasWords)
                        val paragraphWords = paragraph.sumOf(::words)
                        if (sentences.isNotEmpty() && wordCount(sentences) + paragraphWords > maxWords) flush()
                        for (sentence in paragraph) {
                            if (sentences.isNotEmpty() && wordCount(sentences) + words(sentence) > maxWords) flush()
                            sentences += sentence
                        }
                        if (wordCount(sentences) >= targetWords) flush()
                    }
                }
            }
            flush()
            // Imagen al final de un capítulo: ilustra el primer pasaje del siguiente.
            if (image != null) pendingImage = image
            if (passages.isNotEmpty()) {
                val number = chapters.size + 1
                chapters += ChapterDto(
                    id = "c$number",
                    title = chapter.title?.takeIf { it.isNotBlank() }?.take(MAX_TITLE) ?: "Capítulo $number",
                    passages = passages,
                )
            }
        }

        if (chapters.isEmpty()) throw ImportException(ImportException.Reason.NO_TEXT, "El documento no contiene texto legible")

        val manifest = BookManifestDto(
            formatVersion = BookManifestParser.SUPPORTED_FORMAT_VERSION,
            id = options.bookId,
            title = options.title.trim().ifEmpty { options.sourceName }.take(MAX_TITLE),
            learningLanguage = options.language,
            cefrLevel = options.level.name,
            authors = options.authors.filter { it.isNotBlank() },
            license = LicenseDto(id = LICENSE_ID, name = "Importado por el usuario (uso personal)"),
            sourceAttribution = options.sourceName,
            cover = document.cover?.let { store(it, "cover") },
            chapters = chapters,
            narration = document.narration?.let { n ->
                fun file(f: RemoteFile) = RemoteFileDto(f.url, f.sizeBytes, f.sha256)
                NarrationDto(file(n.audio), file(n.timings))
            },
        )
        // Misma validación que el contenido empaquetado: si el lector no lo aceptaría, se falla ya.
        BookManifestParser.toDomain(manifest)
        return ImportedBook(manifest, media)
    }

    private fun hasWords(sentence: String) = Tokenizer.tokenize(sentence, "und").isNotEmpty()

    private fun words(sentence: String) = sentence.count { it == ' ' } + 1

    private fun wordCount(sentences: List<String>) = sentences.sumOf(::words)

    companion object {
        const val LICENSE_ID = "user-import"
        private const val MAX_TITLE = 160
    }
}
