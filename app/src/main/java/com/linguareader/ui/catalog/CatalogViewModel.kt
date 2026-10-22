package com.linguareader.ui.catalog

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.data.content.BookExporter
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.db.ReadingProgressEntity
import com.linguareader.data.library.LibraryRepository
import com.linguareader.data.library.LibraryState
import com.linguareader.data.library.Shelf
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.content.CoverThumbnails
import com.linguareader.data.content.OriginalDocument
import androidx.compose.ui.graphics.asImageBitmap
import com.linguareader.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.model.OriginalOpenMode
import com.linguareader.data.db.DeviceDocumentDao
import com.linguareader.data.scan.ScanSettingsRepository
import kotlinx.coroutines.flow.first
import com.linguareader.engine.packs.LibraryPackManager
import javax.inject.Inject

data class CatalogItem(
    val summary: BookSummary,
    /** 0..1, null si nunca se abrió. */
    val progress: Float?,
    /** Importado por el usuario: se puede borrar. */
    val imported: Boolean = false,
    /** De una biblioteca descargable (también se guarda como importado, pero no lo trajo el usuario). */
    val fromLibrary: Boolean = false,
    /** Pretraducción en curso o fallida (solo importados). */
    val translationJob: BookTranslationState? = null,
    /** Idiomas a los que el libro está traducido entero automáticamente. */
    val machineTranslations: Set<String> = emptySet(),
    /** Pretraducción cancelada por el usuario, pendiente de continuar. */
    val translationPause: ImportedBookRepository.TranslationPause? = null,
    /** Fichero original (solo documentos con visor de su formato, como PDF): permite «Ver original». */
    val original: OriginalDocument? = null,
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

/** Subfiltros del apartado actual; se reinician al cambiar de apartado. */
data class SubFilters(
    val levels: Set<CefrLevel> = emptySet(),
    /** Idioma del libro (chips de «Aprender otros idiomas» o búsqueda): null = todos. */
    val language: String? = null,
    /** Solo en «Colecciones»: null = todas. */
    val collectionId: Long? = null,
    /** Lupa de la cabecera: texto, estado de lectura y orden. */
    val search: CatalogSearch = CatalogSearch(),
)

data class CatalogUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val section: LibrarySection = LibrarySection.Books,
    val filters: SubFilters = SubFilters(),
    val items: List<CatalogItem> = emptyList(),
    val translationLanguage: String = "es",
    val grid: Boolean = false,
    /** Qué hacer al pulsar un documento con visor de su formato (PDF). */
    val originalOpenMode: OriginalOpenMode = OriginalOpenMode.ASK,
    /** Estanterías fijas (Favoritos, Para leer, Leídos) y colecciones propias. */
    val shelves: List<Shelf> = emptyList(),
    /** Idiomas de los libros de «otros idiomas» (para sus chips). */
    val otherLanguages: List<String> = emptyList(),
    /** Idiomas de los libros del apartado actual (filtro de idioma de la búsqueda si hay más de uno). */
    val sectionLanguages: List<String> = emptyList(),
    /** Número de libros de cada apartado, para el menú lateral. */
    val counts: Map<LibrarySection, Int> = emptyMap(),
    /** «¿Por dónde empiezo?»: en «Aprender…» sin nada empezado, el libro más corto de lo que se muestra. */
    val suggestion: CatalogItem? = null,
) {
    val systemShelves: List<Shelf> get() = shelves.filter { !it.isCustom }
    val collections: List<Shelf> get() = shelves.filter { it.isCustom }
}

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val books: BookRepository,
    private val preferences: UserPreferencesRepository,
    private val imported: ImportedBookRepository,
    private val bookTranslations: BookTranslationService,
    private val library: LibraryRepository,
    private val exporter: BookExporter,
    private val scanSettings: ScanSettingsRepository,
    private val thumbnails: CoverThumbnails,
    progressDao: ProgressDao,
    documents: DeviceDocumentDao,
) : ViewModel() {

    /** Documentos encontrados en el móvil para el aviso de «Libros»; 0 si no hay o el usuario cerró el aviso. */
    val foundDocuments: StateFlow<Int> =
        combine(documents.observeAll(), scanSettings.settings) { docs, s -> if (s.foundBannerDismissed) 0 else docs.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun dismissFoundDocuments() = viewModelScope.launch { scanSettings.update { it.copy(foundBannerDismissed = true) } }

    init {
        // Al abrir (o al acabar la primera apertura), quien aprende empieza en «Aprender <idioma>» con su nivel.
        viewModelScope.launch {
            val p = preferences.preferences.first { it.purpose != null && it.onboardingSummaryShown }
            homeOf(p)?.let { selection.value = it }
        }
    }

    private fun homeOf(p: UserPreferences): Pair<LibrarySection, SubFilters>? {
        if (p.purpose == AppPurpose.READ) return null
        val lang = p.readingLanguage?.let(LanguageTags::primary) ?: return null
        val levels = setOfNotNull(p.learnerLevel)
        return if (lang in LibrarySection.LEARN_LANGUAGES) LibrarySection.Learn(lang) to SubFilters(levels = levels)
        else LibrarySection.LearnOther to SubFilters(levels = levels, language = lang)
    }

    private val selection = MutableStateFlow<Pair<LibrarySection, SubFilters>>(LibrarySection.Books to SubFilters())
    private val _covers = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val covers: StateFlow<Map<String, ImageBitmap>> = _covers

    val state: StateFlow<CatalogUiState> =
        // Un único combine plano: cada fuente se suscribe una vez. (Con flatMapLatest, cada cambio de preferencias,
        // progreso o estanterías volvía a suscribirse al catálogo y repetía sus consultas.)
        combine(
            combine(preferences.preferences, progressDao.observeAll(), library.state, selection) { p, progress, lib, sel ->
                Inputs(p.translationLanguage, p.libraryGrid, p.originalOpenMode, progress, lib, sel.first, sel.second)
            },
            bookTranslations.states,
            imported.translationStatus,
            imported.originals,
            books.observeCatalog(CatalogFilter()).onEach { all -> all.forEach(::loadCover) },
        ) { inputs, jobs, statuses, originals, all ->
            build(inputs, all) { s ->
                val pr = inputs.progress[s.id]
                CatalogItem(
                    summary = s,
                    progress = pr?.let { (it.passageIndex + 1f) / it.totalPassages.coerceAtLeast(1) },

                    imported = imported.owns(s.id),
                    fromLibrary = LibraryPackManager.isLibraryBook(s.id),
                    translationJob = jobs[s.id],
                    machineTranslations = statuses[s.id]?.done.orEmpty(),
                    translationPause = statuses[s.id]?.paused,
                    original = originals[s.id],
                    trashed = s.id in inputs.library.trashedIds,
                )
            }
        }
            .catch { emit(CatalogUiState(loading = false, error = UiText.res(R.string.catalog_load_error))) }
            // Filtrar y ordenar decenas de libros no debe competir con la primera composición en el hilo principal.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    private class Inputs(
        val translationLanguage: String,
        val grid: Boolean,
        val originalOpenMode: OriginalOpenMode,
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
        val inThisSection = when (section) {
            LibrarySection.Trash -> all.filter { it.id in lib.trashedIds }
            else -> live.filter { inSection(section, it) }
        }
        val visible = inThisSection.filter { b ->
            (f.levels.isEmpty() || b.cefrLevel in f.levels) &&
                (f.language == null || LanguageTags.sameLanguage(f.language, b.learningLanguage)) &&
                (f.collectionId == null || lib.shelves.firstOrNull { it.id == f.collectionId }?.bookIds?.contains(b.id) == true)
        }.let { list ->
            when (section) {
                LibrarySection.Reading -> list.sortedByDescending { inputs.progress[it.id]?.updatedAt ?: 0L }
                // Para aprender, de lo más fácil a lo más difícil (sortedBy es estable: dentro del nivel, el orden de antes).
                is LibrarySection.Learn, LibrarySection.LearnOther -> list.sortedBy { it.cefrLevel }
                else -> list
            }
        }.let { list ->
            val progress = inputs.progress.mapValues { (_, p) ->
                CatalogSearch.Progress((p.passageIndex + 1f) / p.totalPassages.coerceAtLeast(1), p.updatedAt)
            }
            val finished = lib.shelves.firstOrNull { it.kind == LibraryRepository.READ }?.bookIds.orEmpty()
            f.search.apply(list, progress, finished)
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
            originalOpenMode = inputs.originalOpenMode,
            shelves = lib.shelves,
            otherLanguages = live.filter(::learnsOther).map { LanguageTags.primary(it.learningLanguage) }.distinct().sorted(),
            sectionLanguages = inThisSection.map { LanguageTags.primary(it.learningLanguage) }.distinct().sorted(),
            counts = sections.associateWith { s -> live.count { inSection(s, it) } } + (LibrarySection.Trash to lib.trash.size),
            suggestion = visible.takeIf {
                (section is LibrarySection.Learn || section == LibrarySection.LearnOther) && !f.search.isActive &&
                    inThisSection.none { b -> b.id in inputs.progress }
            }?.minByOrNull { it.estimatedMinutes }?.let(item),
        )
    }

    fun showSection(section: LibrarySection) { selection.value = section to SubFilters() }

    fun toggleLevel(level: CefrLevel) = selection.update { (s, f) ->
        s to f.copy(levels = if (level in f.levels) f.levels - level else f.levels + level)
    }

    fun clearLevels() = selection.update { (s, f) -> s to f.copy(levels = emptySet()) }

    fun setSearch(search: CatalogSearch) = selection.update { (s, f) -> s to f.copy(search = search) }

    fun clearSearch() = selection.update { (s, f) -> s to f.copy(search = CatalogSearch(), language = if (s == LibrarySection.LearnOther) f.language else null) }

    /** Filtro de idioma: chips de «Aprender otros idiomas» y búsqueda de los demás apartados. */
    fun setOtherLanguage(tag: String?) = selection.update { (s, f) -> s to f.copy(language = tag) }

    fun setCollection(id: Long?) = selection.update { (s, f) -> s to f.copy(collectionId = id) }

    fun setOriginalOpenMode(mode: OriginalOpenMode) = viewModelScope.launch { preferences.update { it.copy(originalOpenMode = mode) } }

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

    /** Crea una colección con el nombre dado y mete el libro en ella. */
    fun createCollection(name: String, bookId: String) = viewModelScope.launch {
        if (name.isNotBlank()) library.createCollection(name, bookId)
    }

    /** TXT del libro listo para compartir, o null si falla. */
    suspend fun exportForSharing(bookId: String): android.net.Uri? =
        runCatching { exporter.exportText(books.loadBook(bookId)) }.getOrNull()

    fun canDeleteForever(bookId: String) = library.canDeleteForever(bookId)

    /** Borra del dispositivo un libro importado que está en la papelera. */
    fun deleteForever(bookId: String) = viewModelScope.launch {
        bookTranslations.cancel(bookId)
        library.deleteForever(bookId)
        requestedCovers.remove(bookId)
        _covers.update { it - bookId }
        thumbnails.forget(bookId)
    }

    fun deleteCollection(id: Long) = viewModelScope.launch {
        library.deleteCollection(id)
        selection.update { (s, f) -> s to f.copy(collectionId = null) }
    }

    /**
     * Versión de portada ya pedida por libro (cargada o en curso): [loadCover] se llama en cada emisión del
     * catálogo y solo vuelve a cargar si cambió la portada o, en las páginas de texto, el título.
     */
    private val requestedCovers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Miniatura de la portada en segundo plano, desde la caché en disco de [CoverThumbnails] (nunca a tamaño
     * completo ni en el hilo principal). Los documentos del usuario sin portada reciben una «primera página»
     * dibujada; los libros de las bibliotecas graduadas, no.
     */
    private fun loadCover(summary: BookSummary) {
        val textPage = summary.cover == null && imported.owns(summary.id) && !LibraryPackManager.isLibraryBook(summary.id)
        if (summary.cover == null && !textPage) return
        val version = "${summary.cover?.sha256 ?: summary.cover?.path}|${if (textPage) summary.title else ""}"
        if (requestedCovers.put(summary.id, version) == version) return
        viewModelScope.launch {
            thumbnails.load(summary, COVER_THUMBNAIL_PX, textPage)?.asImageBitmap()
                ?.let { bmp -> _covers.update { it + (summary.id to bmp) } }
        }
    }

    private companion object {
        /** Ancho de miniatura suficiente para la portada más ancha de la cuadrícula en un móvil (≈ 160 dp a 3x). */
        const val COVER_THUMBNAIL_PX = 480
    }
}
