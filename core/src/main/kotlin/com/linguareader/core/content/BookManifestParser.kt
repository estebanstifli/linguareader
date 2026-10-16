package com.linguareader.core.content

import com.linguareader.core.model.Book
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.Chapter
import com.linguareader.core.model.ContentLicense
import com.linguareader.core.model.GlossaryEntry
import com.linguareader.core.model.GlossaryExample
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.TermKind
import com.linguareader.core.text.PassageBuilder
import com.linguareader.core.text.SentenceInput
import com.linguareader.core.text.Tokenizer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ContentFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Convierte manifiestos JSON versionados en modelos de dominio, validando su estructura. */
object BookManifestParser {

    const val SUPPORTED_FORMAT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true // compatibilidad hacia delante con campos nuevos
    }

    private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val LANGUAGE_PATTERN = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    fun parseIndex(text: String): LibraryIndexDto = try {
        json.decodeFromString(LibraryIndexDto.serializer(), text)
    } catch (e: SerializationException) {
        throw ContentFormatException("Índice de biblioteca inválido: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw ContentFormatException("Índice de biblioteca inválido: ${e.message}", e)
    }

    fun parse(text: String): Book {
        val dto = try {
            json.decodeFromString(BookManifestDto.serializer(), text)
        } catch (e: SerializationException) {
            throw ContentFormatException("Manifiesto inválido: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ContentFormatException("Manifiesto inválido: ${e.message}", e)
        }
        return toDomain(dto)
    }

    fun toDomain(dto: BookManifestDto): Book {
        check(dto.formatVersion == SUPPORTED_FORMAT_VERSION) {
            "formatVersion ${dto.formatVersion} no soportado (se esperaba $SUPPORTED_FORMAT_VERSION)"
        }
        check(ID_PATTERN.matches(dto.id)) { "id de libro inválido: '${dto.id}'" }
        check(dto.title.isNotBlank()) { "El libro ${dto.id} no tiene título" }
        check(LANGUAGE_PATTERN.matches(dto.learningLanguage)) { "learningLanguage inválido: '${dto.learningLanguage}'" }
        val level = CefrLevel.parse(dto.cefrLevel) ?: fail("cefrLevel inválido: '${dto.cefrLevel}'")
        check(dto.license.id.isNotBlank()) { "license.id es obligatorio" }
        check(dto.sourceAttribution.isNotBlank()) { "sourceAttribution es obligatorio" }
        check(dto.chapters.isNotEmpty()) { "El libro ${dto.id} no tiene capítulos" }

        val seenIds = mutableSetOf<String>()
        fun unique(kind: String, id: String, scope: String = "") {
            check(ID_PATTERN.matches(id)) { "id de $kind inválido: '$id'" }
            check(seenIds.add("$kind:$scope/$id")) { "id de $kind duplicado: '$id'" }
        }

        var globalIndex = 0
        val chapters = dto.chapters.map { chapterDto ->
            unique("chapter", chapterDto.id)
            check(chapterDto.passages.isNotEmpty()) { "El capítulo ${chapterDto.id} no tiene pasajes" }
            val passages = chapterDto.passages.map { passageDto ->
                unique("passage", passageDto.id)
                passageDto.sentences.forEach { unique("sentence", it.id, scope = passageDto.id) }
                try {
                    PassageBuilder.build(
                        passageId = passageDto.id,
                        chapterId = chapterDto.id,
                        globalIndex = globalIndex++,
                        languageTag = dto.learningLanguage,
                        sentences = passageDto.sentences.map { SentenceInput(it.id, it.text, it.translations) },
                        image = passageDto.image?.let(::media),
                    )
                } catch (e: IllegalArgumentException) {
                    throw ContentFormatException(e.message ?: "Pasaje inválido", e)
                }
            }
            Chapter(chapterDto.id, chapterDto.title, chapterDto.titleTranslations, passages)
        }

        val glossary = dto.glossary.map { entry ->
            unique("glossary", entry.id)
            check(entry.forms.isNotEmpty()) { "La entrada ${entry.id} no tiene formas" }
            GlossaryEntry(
                id = entry.id,
                lemma = entry.lemma,
                kind = parseKind(entry.kind),
                partOfSpeech = entry.pos,
                forms = entry.forms.map(Tokenizer::matchKey).distinct(),
                translations = entry.translations,
                examples = entry.examples.map { GlossaryExample(it.text, it.translations) },
            )
        }

        return Book(
            id = dto.id,
            formatVersion = dto.formatVersion,
            title = dto.title,
            description = dto.description,
            learningLanguage = dto.learningLanguage,
            cefrLevel = level,
            genre = dto.genre,
            authors = dto.authors,
            adaptationCredit = dto.adaptationCredit,
            license = ContentLicense(dto.license.id, dto.license.name, dto.license.url),
            sourceAttribution = dto.sourceAttribution,
            cover = dto.cover?.let(::media),
            chapters = chapters,
            glossary = glossary,
        )
    }

    private fun media(dto: MediaDto): MediaRef {
        val path = dto.path
        check(path.isNotBlank() && !path.startsWith("/") && !path.contains("\\") &&
            path.split('/').none { it == ".." || it.isEmpty() }) {
            "Ruta de media no permitida (debe ser relativa a la carpeta del libro): '$path'"
        }
        dto.sha256?.let { check(SHA256_PATTERN.matches(it)) { "sha256 inválido para '$path'" } }
        return MediaRef(path, dto.alt, dto.credit, dto.sha256)
    }

    private fun parseKind(kind: String): TermKind = when (kind.lowercase()) {
        "word" -> TermKind.WORD
        "phrasal_verb" -> TermKind.PHRASAL_VERB
        "expression" -> TermKind.EXPRESSION
        "contraction" -> TermKind.CONTRACTION
        else -> fail("kind de glosario desconocido: '$kind'")
    }

    private inline fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw ContentFormatException(message())
    }

    private fun fail(message: String): Nothing = throw ContentFormatException(message)
}
