package com.linguareader.ui.catalog

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.data.db.ProgressDao
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
)

data class CatalogUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<CatalogItem> = emptyList(),
    val readingLanguage: String? = null,
    val translationLanguage: String = "es",
    val levels: Set<CefrLevel> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val books: BookRepository,
    private val preferences: UserPreferencesRepository,
    progressDao: ProgressDao,
) : ViewModel() {

    private val levels = MutableStateFlow<Set<CefrLevel>>(emptySet())
    private val _covers = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val covers: StateFlow<Map<String, ImageBitmap>> = _covers

    val state: StateFlow<CatalogUiState> =
        combine(preferences.preferences, levels, progressDao.observeAll()) { p, lv, progress -> Triple(p, lv, progress) }
            .flatMapLatest { (p, lv, progress) ->
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
                            )
                        },
                        readingLanguage = p.readingLanguage,
                        translationLanguage = p.translationLanguage,
                        levels = lv,
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

    fun toggleLevel(level: CefrLevel) = levels.update { if (level in it) it - level else it + level }

    private fun loadCover(summary: BookSummary) {
        val cover = summary.cover ?: return
        if (summary.id in _covers.value) return
        viewModelScope.launch {
            books.loadMedia(summary.id, cover).toImageBitmap()?.let { bmp -> _covers.update { it + (summary.id to bmp) } }
        }
    }
}
