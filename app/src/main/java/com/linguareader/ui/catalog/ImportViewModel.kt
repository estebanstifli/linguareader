package com.linguareader.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.core.model.CefrLevel
import com.linguareader.data.content.BookImportService
import com.linguareader.data.content.ImportDraft
import com.linguareader.data.content.ImportRequest
import com.linguareader.data.content.PendingImports
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.TranslationModelManager
import com.linguareader.core.model.LanguageTags
import com.linguareader.importer.ImportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Reading(val label: String) : ImportUiState
    data class Review(
        val draft: ImportDraft,
        val title: String,
        val author: String,
        val language: String,
        val level: CefrLevel,
        val languageOptions: List<String>,
        /** Idioma al que se traduce (preferencia del usuario). */
        val translationTarget: String,
        /** true = traducir el libro entero al importarlo; false = traducir mientras se lee. */
        val translateNow: Boolean = false,
        val saving: Boolean = false,
    ) : ImportUiState {
        /** Solo tiene sentido ofrecer traducción si el idioma del libro y el de traducción difieren. */
        val canTranslate: Boolean get() = !LanguageTags.sameLanguage(language, translationTarget)
    }
    data class Failed(val message: String) : ImportUiState
    data class Done(val bookId: String, val language: String) : ImportUiState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val service: BookImportService,
    private val preferences: UserPreferencesRepository,
    private val translations: BookTranslationService,
    private val models: TranslationModelManager,
    pending: PendingImports,
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state
    private var job: Job? = null

    init {
        // "Abrir con…" y "Compartir" desde otras apps.
        viewModelScope.launch {
            pending.request.filterNotNull().collect { pending.consume()?.let(::start) }
        }
    }

    fun start(request: ImportRequest) {
        job?.cancel()
        _state.value = ImportUiState.Reading(
            when (request) {
                is ImportRequest.Document -> "Leyendo el documento…"
                is ImportRequest.WebPage -> "Descargando ${request.url}…"
                is ImportRequest.Text -> "Preparando el texto…"
            },
        )
        job = viewModelScope.launch {
            _state.value = try {
                val draft = when (request) {
                    is ImportRequest.Document -> service.read(request.uri)
                    is ImportRequest.WebPage -> service.readUrl(request.url)
                    is ImportRequest.Text -> service.readText(request.text, request.title)
                }
                val prefs = preferences.preferences.first()
                val fallback = prefs.readingLanguage ?: "en"
                val language = draft.detectedLanguage ?: fallback
                ImportUiState.Review(
                    draft = draft,
                    title = draft.document.title.orEmpty(),
                    author = draft.document.authors.joinToString(", "),
                    language = language,
                    level = draft.document.level ?: CefrLevel.B1,
                    languageOptions = (listOf(language) + COMMON_LANGUAGES).distinctBy { it.lowercase() },
                    translationTarget = prefs.translationLanguage,
                )
            } catch (e: ImportException) {
                ImportUiState.Failed(e.message ?: "No se pudo importar")
            } catch (e: Exception) {
                ImportUiState.Failed("No se pudo leer el fichero: ${e.message}")
            }
        }
    }

    fun edit(transform: (ImportUiState.Review) -> ImportUiState.Review) =
        _state.update { if (it is ImportUiState.Review && !it.saving) transform(it) else it }

    fun confirm() {
        val review = _state.value as? ImportUiState.Review ?: return
        _state.value = review.copy(saving = true)
        job = viewModelScope.launch {
            _state.value = try {
                val id = service.commit(review.draft, review.title, review.author, review.language, review.level)
                if (review.translateNow && review.canTranslate && models.code(review.language) != null) {
                    translations.start(id, review.translationTarget)
                }
                ImportUiState.Done(id, review.language)
            } catch (e: Exception) {
                ImportUiState.Failed(e.message ?: "No se pudo guardar el libro")
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        _state.value = ImportUiState.Idle
    }

    companion object {
        /** Idiomas ofrecidos al importar (el TTS del sistema suele tener voz para todos). */
        val COMMON_LANGUAGES = listOf("en", "fr", "es", "de", "it", "pt", "nl", "ca")
    }
}
