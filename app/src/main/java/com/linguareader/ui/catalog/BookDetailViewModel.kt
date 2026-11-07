package com.linguareader.ui.catalog

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.OriginalOpenMode
import com.linguareader.data.content.CompositeBookRepository
import com.linguareader.data.content.CoverThumbnails
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.content.OriginalDocument
import com.linguareader.data.narration.NarrationLibrary
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.audio.BookAudioService
import com.linguareader.engine.audio.BookAudioState
import com.linguareader.engine.audio.BookAudioStatus
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.ui.navigation.BookDetailRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Por qué no se ofrece generar el audio del libro (null = se ofrece). */
enum class AudioUnavailable {
    /** El motor activo es la voz del sistema: genera al vuelo, no hace falta preparar nada. */
    NOT_SUPERTONIC,
    MODEL_NOT_INSTALLED,
    LANGUAGE_NOT_SUPPORTED,
}

/**
 * Narración pregrabada que declara el libro (audio de estudio + tiempos por palabra, descargada del servidor): con ella
 * no hace falta generar nada en el móvil.
 */
data class NarrationSection(
    /** Descargada y casada con el texto: el lector la usa. */
    val ready: Boolean,
    val downloading: Boolean,
    /** Tamaño de la descarga (audio + tiempos). */
    val bytes: Long,
)

/** Apartado «Audio del libro» de la ficha: generación completa con Supertonic para escuchar sin esperas. */
data class AudioSection(
    val unavailable: AudioUnavailable?,
    val voice: String,
    val job: BookAudioState?,
    /** Lo que hay en disco (null mientras se cuenta). */
    val status: BookAudioStatus?,
    /** Si el libro trae narración pregrabada, sustituye a la generación local. */
    val narration: NarrationSection? = null,
)

data class BookDetailUiState(
    val loading: Boolean = true,
    val summary: BookSummary? = null,
    val cover: ImageBitmap? = null,
    /** 0..1, null si nunca se abrió. */
    val progress: Float? = null,
    val imported: Boolean = false,
    val fromLibrary: Boolean = false,
    val translationTarget: String = "es",
    val translationJob: BookTranslationState? = null,
    val translationPause: ImportedBookRepository.TranslationPause? = null,
    /** Idiomas a los que el libro está traducido entero automáticamente. */
    val machineTranslations: Set<String> = emptySet(),
    val audio: AudioSection? = null,
    /** Otro libro cuyo audio se está generando ahora (id y título): solo se genera uno a la vez. */
    val otherGenerating: Pair<String, String>? = null,
    /** Seguir generando con la pantalla apagada (servicio en primer plano) y si ya se decidió («No volver a preguntar»). */
    val generateScreenOff: Boolean = true,
    val generateScreenOffChosen: Boolean = false,
    val original: OriginalDocument? = null,
    val originalOpenMode: OriginalOpenMode = OriginalOpenMode.ASK,
) {
    /** El libro ya está en el idioma de traducción: no hay nada que traducir. */
    val sameLanguage: Boolean get() = summary?.let { LanguageTags.sameLanguage(it.learningLanguage, translationTarget) } ?: false
    val bundledTranslation: Boolean get() = summary?.translationLanguages?.any { LanguageTags.sameLanguage(it, translationTarget) } ?: false
    val machineTranslated: Boolean get() = translationTarget in machineTranslations
}

class BookDetailViewModel(
    savedState: SavedStateHandle,
    private val books: CompositeBookRepository,
    private val preferences: UserPreferencesRepository,
    private val imported: ImportedBookRepository,
    private val bookTranslations: BookTranslationService,
    private val bookAudio: BookAudioService,
    private val narrations: NarrationLibrary,
    private val supertonic: SupertonicEngine,
    private val thumbnails: CoverThumbnails,
    private val progressDao: ProgressDao,
) : ViewModel() {
    val bookId: String = savedState.toRoute<BookDetailRoute>().bookId

    private val summary = MutableStateFlow<BookSummary?>(null)
    private val cover = MutableStateFlow<ImageBitmap?>(null)
    private val progress = MutableStateFlow<Float?>(null)
    private val audioStatus = MutableStateFlow<BookAudioStatus?>(null)
    private val narration = MutableStateFlow<NarrationSection?>(null)
    private val failed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val s = runCatching { books.loadSummary(bookId) }.getOrNull()
            if (s == null) { failed.value = true; return@launch }
            summary.value = s
            progress.value = progressDao.get(bookId)?.let { (it.passageIndex + 1f) / it.totalPassages.coerceAtLeast(1) }
            val textPage = s.cover == null && imported.owns(s.id) && !LibraryPackManager.isLibraryBook(s.id)
            if (s.cover != null || textPage) {
                thumbnails.load(s, COVER_PX, textPage)?.let { cover.value = it.asImageBitmap() }
            }
        }
        // Narración pregrabada: al abrir, al empezar o terminar su descarga.
        viewModelScope.launch {
            combine(summary, narrations.downloading, narrations.ready.filter { it == bookId }.onStart { emit("") }) { s, d, _ -> s to (bookId in d) }
                .collect { (s, downloading) ->
                    if (s == null) return@collect
                    narration.value = withContext(Dispatchers.Default) { narrationSection(downloading) }
                }
        }
        // Recuento del audio en disco: al abrir, al cambiar de voz y cada vez que la generación termina o se borra.
        viewModelScope.launch {
            combine(summary, preferences.preferences.map { it.supertonicVoice }.distinctUntilChanged(), bookAudio.revision) { s, v, _ -> s to v }
                .collect { (s, voice) ->
                    if (s == null) return@collect
                    audioStatus.value = withContext(Dispatchers.Default) { bookAudio.status(bookId, voice) }
                }
        }
    }

    /** Otro libro generando audio ahora mismo, con su título (se carga al cambiar). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val otherGenerating = bookAudio.states
        .map { states -> states.entries.firstOrNull { it.key != bookId && it.value !is BookAudioState.Failed }?.key }
        .distinctUntilChanged()
        .mapLatest { id -> id?.let { it to (runCatching { books.loadSummary(it) }.getOrNull()?.title ?: it) } }

    val state: StateFlow<BookDetailUiState> = combine(
        combine(summary, cover, progress, failed) { s, c, p, f -> Triple(s, c, p) to f },
        preferences.preferences,
        combine(bookTranslations.states, imported.translationStatus, imported.originals) { jobs, statuses, originals -> Triple(jobs, statuses, originals) },
        combine(bookAudio.states, audioStatus, otherGenerating, narration) { jobs, status, other, n -> Triple(jobs[bookId], status, other) to n },
    ) { (loaded, f), prefs, (jobs, statuses, originals), (audioJobs, narr) ->
        val (audioJob, status, other) = audioJobs
        val (s, c, p) = loaded
        if (s == null) return@combine BookDetailUiState(loading = !f)
        val lang = LanguageTags.primary(s.learningLanguage)
        val unavailable = when {
            prefs.ttsEngineId != SupertonicModel.ENGINE_ID -> AudioUnavailable.NOT_SUPERTONIC
            !supertonic.isInstalled() -> AudioUnavailable.MODEL_NOT_INSTALLED
            lang !in SupertonicModel.LANGUAGES -> AudioUnavailable.LANGUAGE_NOT_SUPPORTED
            else -> null
        }
        BookDetailUiState(
            loading = false,
            summary = s,
            cover = c,
            progress = p,
            imported = imported.owns(s.id),
            fromLibrary = LibraryPackManager.isLibraryBook(s.id),
            translationTarget = prefs.translationLanguage,
            translationJob = jobs[s.id],
            translationPause = statuses[s.id]?.paused,
            machineTranslations = statuses[s.id]?.done.orEmpty(),
            audio = AudioSection(unavailable, prefs.supertonicVoice, audioJob, status, narr),
            otherGenerating = other,
            generateScreenOff = prefs.generateAudioScreenOff,
            generateScreenOffChosen = prefs.generateAudioScreenOffChosen,
            original = originals[s.id],
            originalOpenMode = prefs.originalOpenMode,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState())

    fun startTranslation(target: String) = bookTranslations.start(bookId, target)
    fun cancelTranslation() = bookTranslations.cancel(bookId)
    fun dismissTranslationError() = bookTranslations.dismissError(bookId)

    fun startAudio() = bookAudio.start(bookId, state.value.audio?.voice)
    fun cancelAudio() = bookAudio.cancel(bookId)
    fun dismissAudioError() = bookAudio.dismissError(bookId)
    fun deleteAudio() = viewModelScope.launch(Dispatchers.IO) { bookAudio.delete(bookId) }

    /** Descarga ahora la narración pregrabada (si no, se descarga al abrir el libro). */
    fun downloadNarration() = viewModelScope.launch(Dispatchers.IO) {
        val book = runCatching { books.loadBook(bookId) }.getOrNull() ?: return@launch
        narrations.download(book, retry = true)
    }

    private suspend fun narrationSection(downloading: Boolean): NarrationSection? {
        val book = runCatching { books.loadBook(bookId) }.getOrNull() ?: return null
        val ref = narrations.declared(book) ?: return null
        val bytes = (ref.audio.sizeBytes ?: 0L) + (ref.timings.sizeBytes ?: 0L)
        if (narrations.needsDownload(book)) return NarrationSection(ready = false, downloading = downloading, bytes = bytes)
        // Descargada pero sin casar con el texto: no sirve; se ofrece la generación local como a cualquier libro.
        if (narrations.cached(book) == null) return null
        return NarrationSection(ready = true, downloading = false, bytes = bytes)
    }

    /**
     * Respuesta al diálogo de «Generar audio»: guarda si seguir con la pantalla apagada ([screenOff]; con [remember]
     * no se vuelve a preguntar) y empieza la generación una vez guardado, para que el servicio en primer plano vea la
     * elección definitiva.
     */
    fun startAudio(screenOff: Boolean, remember: Boolean) = viewModelScope.launch {
        preferences.update { it.copy(generateAudioScreenOff = screenOff, generateAudioScreenOffChosen = remember) }
        startAudio()
    }

    fun setOriginalOpenMode(mode: OriginalOpenMode) = viewModelScope.launch { preferences.update { it.copy(originalOpenMode = mode) } }

    private companion object {
        /** Portada grande de la ficha (≈ 140 dp a 3x; se reduce al dibujar). */
        const val COVER_PX = 540
    }
}
