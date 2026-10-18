package com.linguareader.ui.catalog

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.data.content.BookImportService
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.db.ProgressDao
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.ui.common.Languages
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
)

data class CatalogUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<CatalogItem> = emptyList(),
    val readingLanguage: String? = null,
    val translationLanguage: String = "es",
    val levels: Set<CefrLevel> = emptySet(),
    /** Idiomas de lectura para filtrar: los incluidos más los de los libros importados. */
    val readingOptions: List<String?> = Languages.readingOptions,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val books: BookRepository,
    private val preferences: UserPreferencesRepository,
    private val imported: ImportedBookRepository,
    private val importer: BookImportService,
    private val bookTranslations: BookTranslationService,
    progressDao: ProgressDao,
) : ViewModel() {

    private val importedLanguages = imported.observeCatalog(CatalogFilter()).map { list ->
        list.map { LanguageTags.primary(it.learningLanguage) }.distinct()
    }

    private val levels = MutableStateFlow<Set<CefrLevel>>(emptySet())
    private val _covers = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val covers: StateFlow<Map<String, ImageBitmap>> = _covers

    val state: StateFlow<CatalogUiState> =
        combine(
            combine(preferences.preferences, levels, progressDao.observeAll(), importedLanguages) { p, lv, progress, extra ->
                Triple(p, lv, progress) to extra
            },
            bookTranslations.states,
            imported.translationStatus,
        ) { base, jobs, statuses -> Triple(base, jobs, statuses) }
            .flatMapLatest { (base, jobs, statuses) ->
                val (triple, extraLanguages) = base
                val (p, lv, progress) = triple
                val byBook = progress.associateBy { it.bookId }
                books.observeCatalog(CatalogFilter(learningLanguage = p.readingLanguage, levels = lv)).map { list ->
                    list.forEach(::loadCover)
                    CatalogUiState(
                        loading = false,
                        items = list.map { s ->
                            val pr = byBook[s.id]
                            CatalogItem(
                                summary = s,
                                progress = pr?.let { (it.passageIndex + 1f) / it.totalPassages.coerceAtLeast(1) },
                                progressLabel = pr?.let { "Pasaje ${it.passageIndex + 1} de ${it.totalPassages}" },
                                imported = imported.owns(s.id),
                                translationJob = jobs[s.id],
                                machineTranslations = statuses[s.id]?.done.orEmpty(),
                                translationPause = statuses[s.id]?.paused,
                            )
                        },
                        readingLanguage = p.readingLanguage,
                        translationLanguage = p.translationLanguage,
                        levels = lv,
                        readingOptions = (Languages.readingOptions + extraLanguages + p.readingLanguage).distinct(),
                    )
                }
            }
            .catch { emit(CatalogUiState(loading = false, error = it.message ?: "Error al cargar la biblioteca")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    fun setReadingLanguage(tag: String?) = viewModelScope.launch {
        preferences.update { it.copy(readingLanguage = tag) }
    }

    fun setTranslationLanguage(tag: String) = viewModelScope.launch {
        preferences.update { it.copy(translationLanguage = tag) }
    }

    fun cancelTranslation(bookId: String) = bookTranslations.cancel(bookId)

    fun retryTranslation(bookId: String, target: String) = bookTranslations.start(bookId, target)

    fun dismissTranslationError(bookId: String) = bookTranslations.dismissError(bookId)

    fun deleteImported(bookId: String) = viewModelScope.launch {
        bookTranslations.cancel(bookId)
        importer.delete(bookId)
        _covers.update { it - bookId }
    }

    fun toggleLevel(level: CefrLevel) = levels.update { if (level in it) it - level else it + level }

    private fun loadCover(summary: BookSummary) {
        val cover = summary.cover ?: return
        if (summary.id in _covers.value) return
        viewModelScope.launch {
            books.loadMedia(summary.id, cover).toImageBitmap()?.let { bmp -> _covers.update { it + (summary.id to bmp) } }
        }
    }
}
