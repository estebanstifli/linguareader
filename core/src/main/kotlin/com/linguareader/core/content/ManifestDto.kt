package com.linguareader.core.content

import kotlinx.serialization.Serializable

/** Representación JSON del manifiesto de un libro (ver docs/CONTENT_FORMAT.md). */
@Serializable
data class BookManifestDto(
    val formatVersion: Int,
    val id: String,
    val title: String,
    val description: String? = null,
    val learningLanguage: String,
    val cefrLevel: String,
    val genre: String? = null,
    val authors: List<String> = emptyList(),
    val adaptationCredit: String? = null,
    val license: LicenseDto,
    val sourceAttribution: String,
    val cover: MediaDto? = null,
    val chapters: List<ChapterDto>,
    val glossary: List<GlossaryEntryDto> = emptyList(),
)

@Serializable
data class LicenseDto(
    val id: String,
    val name: String? = null,
    val url: String? = null,
)

@Serializable
data class MediaDto(
    val path: String,
    val alt: String? = null,
    val credit: String? = null,
    val sha256: String? = null,
)

@Serializable
data class ChapterDto(
    val id: String,
    val title: String,
    val titleTranslations: Map<String, String> = emptyMap(),
    val passages: List<PassageDto>,
)

@Serializable
data class PassageDto(
    val id: String,
    val image: MediaDto? = null,
    val sentences: List<SentenceDto>,
)

@Serializable
data class SentenceDto(
    val id: String,
    val text: String,
    val translations: Map<String, String> = emptyMap(),
)

@Serializable
data class GlossaryEntryDto(
    val id: String,
    val lemma: String,
    val kind: String = "word",
    val pos: String? = null,
    val forms: List<String>,
    val translations: Map<String, String> = emptyMap(),
    val examples: List<ExampleDto> = emptyList(),
)

@Serializable
data class ExampleDto(
    val text: String,
    val translations: Map<String, String> = emptyMap(),
)

/** Índice de la biblioteca empaquetada: lista de carpetas de libros. */
@Serializable
data class LibraryIndexDto(
    val formatVersion: Int,
    val books: List<String>,
)
