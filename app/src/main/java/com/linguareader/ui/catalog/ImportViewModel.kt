package com.linguareader.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.R
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
import com.linguareader.ui.common.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Reading(val label: UiText) : ImportUiState
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
    /**
     * [message]: el motivo, traducido. [detail]: el mensaje técnico del importador, si aporta algo.
     * [viewUri]: el fichero no se puede importar pero sí ver con su formato (PDF escaneado, sin texto).
     */
    data class Failed(val message: UiText, val detail: String? = null, val viewUri: android.net.Uri? = null) : ImportUiState
    data class Done(val bookId: String, val language: String) : ImportUiState
}

class ImportViewModel(
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
                is ImportRequest.Document -> UiText.res(R.string.import_reading_document)
                is ImportRequest.WebPage -> UiText.res(R.string.import_downloading, request.url)
                is ImportRequest.Text -> UiText.res(R.string.import_preparing_text)
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
                val viewable = request is ImportRequest.Document && e.reason == ImportException.Reason.NO_TEXT && service.isPdf(request.uri)
                ImportUiState.Failed(
                    message = UiText.res(
                        when (e.reason) {
                            ImportException.Reason.UNSUPPORTED_FORMAT -> R.string.import_error_unsupported
                            ImportException.Reason.DRM_PROTECTED -> R.string.import_error_drm
                            ImportException.Reason.NO_TEXT -> R.string.import_error_no_text
                            ImportException.Reason.CORRUPT -> R.string.import_error_corrupt
                        },
                    ),
                    detail = e.message.takeIf { e.reason != ImportException.Reason.NO_TEXT },
                    viewUri = (request as? ImportRequest.Document)?.uri?.takeIf { viewable },
                )
            } catch (e: Exception) {
                ImportUiState.Failed(UiText.res(R.string.import_error_read_file), detail = e.message)
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
                ImportUiState.Failed(UiText.res(R.string.import_error_save), detail = e.message)
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
