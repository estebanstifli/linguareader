package com.linguareader.core.model

/** Niveles del Marco Común Europeo de Referencia. */
enum class CefrLevel { A1, A2, B1, B2, C1, C2;

    companion object {
        fun parse(value: String): CefrLevel? = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

/** Referencia a un recurso multimedia relativo a la carpeta del libro, con hash opcional de integridad. */
data class MediaRef(
    val path: String,
    val alt: String?,
    val credit: String?,
    val sha256: String?,
)

/** Fichero publicado en remoto, con tamaño y sha256 para verificar la descarga. */
data class RemoteFile(val url: String, val sizeBytes: Long?, val sha256: String?)

/** Narración pregrabada de un libro: pista de audio continua + subtítulos ASS de karaoke con el tiempo de cada palabra. */
data class NarrationRef(val audio: RemoteFile, val timings: RemoteFile)

data class ContentLicense(
    val id: String,
    val name: String?,
    val url: String?,
)

data class Book(
    val id: String,
    val formatVersion: Int,
    val title: String,
    val description: String?,
    /** BCP-47, p. ej. "en" o "fr-FR". */
    val learningLanguage: String,
    val cefrLevel: CefrLevel,
    val genre: String?,
    val authors: List<String>,
    val adaptationCredit: String?,
    val license: ContentLicense,
    val sourceAttribution: String,
    val cover: MediaRef?,
    val chapters: List<Chapter>,
    val glossary: List<GlossaryEntry>,
    /** Narración pregrabada que se puede descargar; null si el libro no tiene. */
    val narration: NarrationRef? = null,
) {
    /** Todos los pasajes en orden de lectura. */
    val passages: List<Passage> by lazy { chapters.flatMap { it.passages } }

    val wordCount: Int by lazy { passages.sumOf { it.tokens.size } }

    /** Idiomas para los que todas las frases tienen traducción incluida. */
    val fullyTranslatedLanguages: Set<String> by lazy {
        val all = passages.flatMap { it.sentences }
        if (all.isEmpty()) emptySet()
        else all.map { it.translations.keys }.reduce { acc, keys -> acc intersect keys }
    }

    fun chapterOf(passage: Passage): Chapter? = chapters.firstOrNull { it.id == passage.chapterId }

    fun summary(): BookSummary = BookSummary(
        id = id,
        title = title,
        description = description,
        learningLanguage = learningLanguage,
        cefrLevel = cefrLevel,
        genre = genre,
        cover = cover,
        wordCount = wordCount,
        estimatedMinutes = estimateMinutes(wordCount, cefrLevel),
        passageCount = passages.size,
        translationLanguages = fullyTranslatedLanguages,
        authors = authors,
    )

    companion object {
        /** Estimación aproximada de lectura en voz alta para aprendices, más lenta en niveles bajos. */
        fun estimateMinutes(words: Int, level: CefrLevel): Int {
            val wordsPerMinute = when (level) {
                CefrLevel.A1 -> 80; CefrLevel.A2 -> 95; CefrLevel.B1 -> 110
                CefrLevel.B2 -> 125; CefrLevel.C1, CefrLevel.C2 -> 140
            }
            return maxOf(1, (words + wordsPerMinute - 1) / wordsPerMinute)
        }
    }
}

data class Chapter(
    val id: String,
    val title: String,
    val titleTranslations: Map<String, String>,
    val passages: List<Passage>,
)

/**
 * Un pasaje con su texto normalizado e inmutable. Los rangos de [sentences] y [tokens] son
 * offsets UTF-16 sobre [text], que es la concatenación de las frases separadas por un espacio.
 */
data class Passage(
    val id: String,
    val chapterId: String,
    /** Posición global del pasaje dentro del libro (0-based). */
    val index: Int,
    val text: String,
    val sentences: List<Sentence>,
    val tokens: List<Token>,
    val image: MediaRef?,
    /** Léxico de pronunciación del libro (término escrito → forma hablada), para el TTS. */
    val pronunciations: Map<String, String> = emptyMap(),
) {
    fun sentenceById(id: String): Sentence? = sentences.firstOrNull { it.id == id }
    fun tokenById(id: String): Token? = tokens.firstOrNull { it.id == id }
    fun tokensOf(sentence: Sentence): List<Token> = tokens.filter { it.sentenceId == sentence.id }

    /** Traducción del pasaje completo (bloque), solo si todas sus frases están traducidas. */
    fun translationBlock(languageTag: String): String? {
        val parts = sentences.map { it.translations[languageTag] ?: return null }
        return parts.joinToString(" ")
    }
}

data class Sentence(
    val id: String,
    val index: Int,
    val text: String,
    val rangeStart: Int,
    val rangeEnd: Int,
    val translations: Map<String, String>,
    /** Forma hablada dada por el autor (se envía al TTS en lugar de [text]); null = normalización automática. */
    val speech: String? = null,
)

/** Unidad pulsable: palabra (con apóstrofos/guiones internos). La puntuación no genera tokens. */
data class Token(
    val id: String,
    val index: Int,
    val text: String,
    val rangeStart: Int,
    val rangeEnd: Int,
    val sentenceId: String,
) {
    operator fun contains(offset: Int): Boolean = offset in rangeStart until rangeEnd
}

data class BookSummary(
    val id: String,
    val title: String,
    val description: String?,
    val learningLanguage: String,
    val cefrLevel: CefrLevel,
    val genre: String?,
    val cover: MediaRef?,
    val wordCount: Int,
    val estimatedMinutes: Int,
    val passageCount: Int,
    val translationLanguages: Set<String>,
    val authors: List<String> = emptyList(),
)

data class CatalogFilter(
    /** null = todos los idiomas. Se compara por subetiqueta primaria ("fr" casa con "fr-FR"). */
    val learningLanguage: String? = null,
    val levels: Set<CefrLevel> = emptySet(),
    val genre: String? = null,
) {
    fun matches(book: BookSummary): Boolean =
        (learningLanguage == null || LanguageTags.sameLanguage(learningLanguage, book.learningLanguage)) &&
            (levels.isEmpty() || book.cefrLevel in levels) &&
            (genre == null || book.genre == genre)
}
