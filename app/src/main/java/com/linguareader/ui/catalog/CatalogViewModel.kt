package com.linguareader.ui.catalog

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.db.ReadingProgressEntity
import com.linguareader.data.library.LibraryRepository
import com.linguareader.data.library.LibraryState
import com.linguareader.data.library.Shelf
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.ui.common.toImageBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CatalogItem(
    val summary: BookSummary,
    /** 0..1, null si nunca se abrió. */
    val progress: Float?,
    val progressLabel: String?,
    /** Importado por el usuario: se puede borrar. */
    val imported: Boolean = false,
    /** Pretraducción en curso o fallida (solo importados). */
    val translationJob: BookTranslationState? = null,
    /** Idiomas a los que el libro está traducido entero automáticamente. */
    val machineTranslations: Set<String> = emptySet(),
    /** Pretraducción cancelada por el usuario, pendiente de continuar. */
    val translationPause: ImportedBookRepository.TranslationPause? = null,
    /** En la papelera: no se abre; se restaura o (si es importado) se borra del todo. */
    val trashed: Boolean = false,
)

/** Apartado de la biblioteca elegido en el menú lateral. Cada uno es una vista filtrada de los mismos libros. */
sealed interface LibrarySection {
    /** Abiertos alguna vez, el último leído primero. */
    data object Reading : LibrarySection
    /** Todos los libros y documentos. */
    data object Books : LibrarySection
    /** Libros en [language] (inglés, francés), con subfiltro por nivel MCER. */
    data class Learn(val language: String) : LibrarySection
    /** Libros en cualquier otro idioma, con subfiltro por idioma y nivel. */
    data object LearnOther : LibrarySection
    /** Favoritos, Para leer o Leídos. */
    data class OnShelf(val id: Long) : LibrarySection
    /** Colecciones del usuario, con subfiltro por colección. */
    data object Collections : LibrarySection
    data object Trash : LibrarySection

    companion object {
        /** Idiomas con apartado propio en el menú. */
        val LEARN_LANGUAGES = listOf("en", "es", "fr")
    }
}

/** Procedencia de un libro (subfiltro de «Libros»). */
enum class BookOrigin { ALL, BUNDLED, IMPORTED }

/** Subfiltros del apartado actual; se reinician al cambiar de apartado. */
data class SubFilters(
    val levels: Set<CefrLevel> = emptySet(),
    val origin: BookOrigin = BookOrigin.ALL,
    /** Solo en «Aprender otros idiomas»: null = todos. */
    val language: String? = null,
    /** Solo en «Colecciones»: null = todas. */
    val collectionId: Long? = null,
)

data class CatalogUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val section: LibrarySection = LibrarySection.Books,
    val filters: SubFilters = SubFilters(),
    val items: List<CatalogItem> = emptyList(),
    val translationLanguage: String = "es",
    val grid: Boolean = false,
    /** Estanterías fijas (Favoritos, Para leer, Leídos) y colecciones propias. */
    val shelves: List<Shelf> = emptyList(),
    /** Idiomas de los libros de «otros idiomas» (para sus chips). */
    val otherLanguages: List<String> = emptyList(),
    /** Número de libros de cada apartado, para el menú lateral. */
    val counts: Map<LibrarySection, Int> = emptyMap(),
) {
    val systemShelves: List<Shelf> get() = shelves.filter { !it.isCustom }
    val collections: List<Shelf> get() = shelves.filter { it.isCustom }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val books: BookRepository,
    private val preferences: UserPreferencesRepository,
    private val imported: ImportedBookRepository,
    private val bookTranslations: BookTranslationService,
    private val library: LibraryRepository,
    progressDao: ProgressDao,
) : ViewModel() {

    private val selection = MutableStateFlow<Pair<LibrarySection, SubFilters>>(LibrarySection.Books to SubFilters())
    private val _covers = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val covers: StateFlow<Map<String, ImageBitmap>> = _covers

    val state: StateFlow<CatalogUiState> =
        combine(
            combine(preferences.preferences, progressDao.observeAll(), library.state, selection) { p, progress, lib, sel ->
                Inputs(p.translationLanguage, p.libraryGrid, progress, lib, sel.first, sel.second)
            },
            bookTranslations.states,
            imported.translationStatus,
        ) { inputs, jobs, statuses -> Triple(inputs, jobs, statuses) }
            .flatMapLatest { (inputs, jobs, statuses) ->
                books.observeCatalog(CatalogFilter()).map { all ->
                    all.forEach(::loadCover)
                    build(inputs, all) { s ->
                        val pr = inputs.progress[s.id]
                        CatalogItem(
                            summary = s,
                            progress = pr?.let { (it.passageIndex + 1f) / it.totalPassages.coerceAtLeast(1) },
                            progressLabel = pr?.let { "Pasaje ${it.passageIndex + 1} de ${it.totalPassages}" },
                            imported = imported.owns(s.id),
                            translationJob = jobs[s.id],
                            machineTranslations = statuses[s.id]?.done.orEmpty(),
                            translationPause = statuses[s.id]?.paused,
                            trashed = s.id in inputs.library.trashedIds,
                        )
                    }
                }
            }
            .catch { emit(CatalogUiState(loading = false, error = it.message ?: "Error al cargar la biblioteca")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    private class Inputs(
        val translationLanguage: String,
        val grid: Boolean,
        progressList: List<ReadingProgressEntity>,
        val library: LibraryState,
        val section: LibrarySection,
        val filters: SubFilters,
    ) {
        val progress = progressList.associateBy { it.bookId }
    }

    private fun build(inputs: Inputs, all: List<BookSummary>, item: (BookSummary) -> CatalogItem): CatalogUiState {
        val lib = inputs.library
        val live = all.filter { it.id !in lib.trashedIds }
        val section = inputs.section.takeIf { s -> s !is LibrarySection.OnShelf || lib.shelves.any { it.id == s.id } }
            ?: LibrarySection.Books
        val f = inputs.filters
        fun learnsOther(b: BookSummary) = LibrarySection.LEARN_LANGUAGES.none { LanguageTags.sameLanguage(it, b.learningLanguage) }
        fun inSection(s: LibrarySection, b: BookSummary): Boolean = when (s) {
            LibrarySection.Reading -> b.id in inputs.progress
            LibrarySection.Books -> true
            is LibrarySection.Learn -> LanguageTags.sameLanguage(s.language, b.learningLanguage)
            LibrarySection.LearnOther -> learnsOther(b)
            is LibrarySection.OnShelf -> lib.shelves.firstOrNull { it.id == s.id }?.bookIds?.contains(b.id) == true
            LibrarySection.Collections -> lib.shelves.any { it.isCustom && b.id in it.bookIds }
            LibrarySection.Trash -> false
        }
        val visible = when (section) {
            LibrarySection.Trash -> all.filter { it.id in lib.trashedIds }
            else -> live.filter { b ->
                inSection(section, b) &&
                    (f.levels.isEmpty() || b.cefrLevel in f.levels) &&
                    when (f.origin) {
                        BookOrigin.ALL -> true
                        BookOrigin.BUNDLED -> !imported.owns(b.id)
                        BookOrigin.IMPORTED -> imported.owns(b.id)
                    } &&
                    (f.language == null || LanguageTags.sameLanguage(f.language, b.learningLanguage)) &&
                    (f.collectionId == null || lib.shelves.firstOrNull { it.id == f.collectionId }?.bookIds?.contains(b.id) == true)
            }
        }.let { list ->
            if (section == LibrarySection.Reading) list.sortedByDescending { inputs.progress[it.id]?.updatedAt ?: 0L } else list
        }
        val sections = buildList {
            add(LibrarySection.Reading); add(LibrarySection.Books)
            LibrarySection.LEARN_LANGUAGES.forEach { add(LibrarySection.Learn(it)) }
            add(LibrarySection.LearnOther); add(LibrarySection.Collections)
            lib.shelves.filter { !it.isCustom }.forEach { add(LibrarySection.OnShelf(it.id)) }
        }
        return CatalogUiState(
            loading = false,
            section = section,
            filters = f,
            items = visible.map(item),
            translationLanguage = inputs.translationLanguage,
            grid = inputs.grid,
            shelves = lib.shelves,
            otherLanguages = live.filter(::learnsOther).map { LanguageTags.primary(it.learningLanguage) }.distinct().sorted(),
            counts = sections.associateWith { s -> live.count { inSection(s, it) } } + (LibrarySection.Trash to lib.trash.size),
        )
    }

    fun showSection(section: LibrarySection) { selection.value = section to SubFilters() }

    fun toggleLevel(level: CefrLevel) = selection.update { (s, f) ->
        s to f.copy(levels = if (level in f.levels) f.levels - level else f.levels + level)
    }

    fun clearLevels() = selection.update { (s, f) -> s to f.copy(levels = emptySet()) }

    fun setOrigin(origin: BookOrigin) = selection.update { (s, f) -> s to f.copy(origin = origin) }

    fun setOtherLanguage(tag: String?) = selection.update { (s, f) -> s to f.copy(language = tag) }

    fun setCollection(id: Long?) = selection.update { (s, f) -> s to f.copy(collectionId = id) }

    fun setGrid(grid: Boolean) = viewModelScope.launch { preferences.update { it.copy(libraryGrid = grid) } }

    fun setTranslationLanguage(tag: String) = viewModelScope.launch {
        preferences.update { it.copy(translationLanguage = tag) }
    }

    fun cancelTranslation(bookId: String) = bookTranslations.cancel(bookId)

    fun retryTranslation(bookId: String, target: String) = bookTranslations.start(bookId, target)

    fun dismissTranslationError(bookId: String) = bookTranslations.dismissError(bookId)

    fun moveToTrash(item: CatalogItem) = viewModelScope.launch { library.trash(item.summary.id, item.summary.title) }

    fun restore(bookId: String) = viewModelScope.launch { library.restore(bookId) }

    fun setInShelf(shelfId: Long, bookId: String, inShelf: Boolean) = viewModelScope.launch { library.setInShelf(shelfId, bookId, inShelf) }

    fun canDeleteForever(bookId: String) = library.canDeleteForever(bookId)

    /** Borra del dispositivo un libro importado que está en la papelera. */
    fun deleteForever(bookId: String) = viewModelScope.launch {
        bookTranslations.cancel(bookId)
        library.deleteForever(bookId)
        _covers.update { it - bookId }
    }

    fun deleteCollection(id: Long) = viewModelScope.launch {
        library.deleteCollection(id)
        selection.update { (s, f) -> s to f.copy(collectionId = null) }
    }

    private fun loadCover(summary: BookSummary) {
        val cover = summary.cover ?: return
        if (summary.id in _covers.value) return
        viewModelScope.launch {
            books.loadMedia(summary.id, cover).toImageBitmap()?.let { bmp -> _covers.update { it + (summary.id to bmp) } }
        }
    }
}
