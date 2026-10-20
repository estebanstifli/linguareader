package com.linguareader.ui.catalog

import com.linguareader.core.model.BookSummary
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Estado de lectura de un libro, para filtrar en la búsqueda. */
enum class ReadStatus(val label: String) {
    ALL("Todos"),
    NOT_STARTED("Sin empezar"),
    READING("Leyendo"),
    FINISHED("Terminados"),
}

enum class SortOrder(val label: String) {
    /** El orden de cada apartado (importados recientes primero; en «Leyendo ahora», el último leído). */
    DEFAULT("Predeterminado"),
    TITLE("Título"),
    AUTHOR("Autor"),
    LEVEL("Nivel"),
    RECENT("Leídos hace poco"),
    SHORTEST("Más cortos"),
}

/** Búsqueda de la biblioteca (lupa de la cabecera). Vacía = sin filtrar. */
data class CatalogSearch(
    val text: String = "",
    val status: ReadStatus = ReadStatus.ALL,
    val sort: SortOrder = SortOrder.DEFAULT,
) {
    val isActive: Boolean get() = text.isNotBlank() || status != ReadStatus.ALL || sort != SortOrder.DEFAULT

    /**
     * Filtra y ordena [books] (ya en el orden del apartado).
     * @param progress fracción leída (0..1) y momento de la última lectura de cada libro abierto alguna vez.
     * @param finished libros marcados como «Leídos».
     */
    fun apply(books: List<BookSummary>, progress: Map<String, Progress>, finished: Set<String>): List<BookSummary> {
        val needle = fold(text.trim())
        fun statusOf(b: BookSummary): ReadStatus {
            val p = progress[b.id]
            return when {
                b.id in finished || (p != null && p.fraction >= 1f) -> ReadStatus.FINISHED
                p != null -> ReadStatus.READING
                else -> ReadStatus.NOT_STARTED
            }
        }
        val filtered = books.filter { b ->
            (needle.isEmpty() || fold(b.title).contains(needle) || b.authors.any { fold(it).contains(needle) }) &&
                (status == ReadStatus.ALL || statusOf(b) == status)
        }
        val collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }
        val byTitle = Comparator<BookSummary> { a, b -> collator.compare(a.title, b.title) }
        return when (sort) {
            SortOrder.DEFAULT -> filtered
            SortOrder.TITLE -> filtered.sortedWith(byTitle)
            // Sin autor, al final.
            SortOrder.AUTHOR -> filtered.sortedWith(
                compareBy<BookSummary> { it.authors.isEmpty() }
                    .then { a, b -> collator.compare(a.authors.firstOrNull().orEmpty(), b.authors.firstOrNull().orEmpty()) }
                    .then(byTitle),
            )
            SortOrder.LEVEL -> filtered.sortedWith(compareBy<BookSummary> { it.cefrLevel }.then(byTitle))
            SortOrder.RECENT -> filtered.sortedWith(compareByDescending<BookSummary> { progress[it.id]?.updatedAt ?: Long.MIN_VALUE }.then(byTitle))
            SortOrder.SHORTEST -> filtered.sortedWith(compareBy<BookSummary> { it.estimatedMinutes }.then(byTitle))
        }
    }

    data class Progress(val fraction: Float, val updatedAt: Long)

    companion object {
        /** Minúsculas y sin tildes: «Karénina» se encuentra escribiendo «karenina». */
        fun fold(s: String): String =
            Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }
}
