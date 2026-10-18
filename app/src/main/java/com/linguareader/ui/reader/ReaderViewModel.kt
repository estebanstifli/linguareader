package com.linguareader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.Book
import com.linguareader.core.model.Chapter
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.Passage
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.reader.PREFETCH_PAGES_AHEAD
import com.linguareader.core.reader.PrefetchWindow
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.PlaybackStatus
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.reader.TtsEngineRegistry
import com.linguareader.core.reader.TtsEngineSelector
import com.linguareader.core.reader.WarmUpPolicy
import com.linguareader.core.text.OffsetIndex
import com.linguareader.core.translation.WordLookup
import com.linguareader.core.translation.WordLookupService
import com.linguareader.engine.translation.ModelDownload
import com.linguareader.engine.translation.TranslationModelManager
import com.linguareader.data.db.BookmarkDao
import com.linguareader.data.db.BookmarkEntity
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.db.ReadingProgressEntity
import com.linguareader.data.db.VocabularyDao
import com.linguareader.data.db.VocabularyEntity
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.di.ApplicationScope
import com.linguareader.engine.audio.CuePlayer
import com.linguareader.engine.packs.VoiceClip
import com.linguareader.engine.packs.VoicePackManager
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.ui.common.toImageBitmap
import com.linguareader.ui.navigation.ReaderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WordCardState(
    val tokenId: String,
    val lookup: WordLookup? = null,
    val saved: Boolean = false,
    /** La lectura estaba sonando y se pausó al tocar: ofrecer "Reanudar". */
    val pausedForCard: Boolean = false,
    val targetLanguage: String,
)

data class SentenceCardState(
    val sentenceId: String,
    val text: String,
    val translation: TranslationResult?,
    val pausedForCard: Boolean,
    val targetLanguage: String,
)

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val book: Book? = null,
    val chapter: Chapter? = null,
    val passage: Passage? = null,
    val passageIndex: Int = 0,
    val totalPassages: Int = 0,
    val image: ImageBitmap? = null,
    val sentenceTranslations: Map<String, TranslationResult> = emptyMap(),
    val revealedSentences: Set<String> = emptySet(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val wordCard: WordCardState? = null,
    val sentenceCard: SentenceCardState? = null,
    val voices: List<VoiceInfo> = emptyList(),
    /** Pantalla «Preparando la lectura…» mientras el motor genera las primeras frases. */
    val preparing: Boolean = false,
    /** Idiomas (código ML Kit) cuyo modelo de traducción falta para traducir este libro. */
    val missingTranslationModels: List<String> = emptyList(),
) {
    val hasPrevious: Boolean get() = passageIndex > 0
    val hasNext: Boolean get() = passageIndex < totalPassages - 1
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val books: BookRepository,
    private val audio: ReaderAudioController,
    private val engines: TtsEngineRegistry,
    private val engineSelector: TtsEngineSelector,
    private val lookupService: WordLookupService,
    private val preferencesRepository: UserPreferencesRepository,
    private val progressDao: ProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val vocabularyDao: VocabularyDao,
    private val voicePacks: VoicePackManager,
    private val cue: CuePlayer,
    private val translationModels: TranslationModelManager,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** Descargas de modelos de traducción en curso o fallidas (para el aviso del lector). */
    val translationDownloads: StateFlow<Map<String, ModelDownload>> = translationModels.downloads

    private val bookId = savedStateHandle.toRoute<ReaderRoute>().bookId

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    val playback: StateFlow<PlaybackState> = audio.playbackState

    private var passageJob: Job? = null
    private var completionHandledFor: String? = null

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val book = runCatching { books.loadBook(bookId) }.getOrElse {
            _state.update { s -> s.copy(loading = false, error = it.message ?: "No se pudo abrir el libro") }
            return
        }
        _state.update { it.copy(book = book, totalPassages = book.passages.size) }
        val prefs = preferencesRepository.preferences.first()
        audio.setSpeed(prefs.speechRate)

        val saved = progressDao.get(bookId)
        val index = saved?.passageIndex?.coerceIn(0, book.passages.lastIndex) ?: 0
        val offset = saved?.takeIf { book.passages[index].id == it.passageId }?.charOffset ?: 0
        showPassage(index, offset, autoplay = false, immediate = true)

        viewModelScope.launch { bookmarkDao.observeForBook(bookId).collect { list -> _state.update { it.copy(bookmarks = list) } } }
        viewModelScope.launch {
            // También al cambiar de modo: «solo texto» no traduce nada en segundo plano.
            preferences.map { it.translationLanguage to it.displayMode }.distinctUntilChanged().collect { refreshTranslations() }
        }
        viewModelScope.launch {
            preferences.map { it.speechRate }.distinctUntilChanged().collect { audio.setSpeed(it) }
        }
        viewModelScope.launch { audio.playbackState.collect(::onPlayback) }
        viewModelScope.launch {
            audio.playbackState
                .distinctUntilChanged { a, b -> a.passageId == b.passageId && a.sentenceIndex == b.sentenceIndex && a.status == b.status }
                .collect { saveProgress(it) }
        }
        viewModelScope.launch { refreshVoices() }
        viewModelScope.launch {
            // Cambio de motor (Android TTS ↔ Supertonic): recargar el pasaje en el mismo punto.
            engineSelector.selectedEngineId.drop(1).collect { retryAudio() }
        }
        viewModelScope.launch {
            preferences.map { it.supertonicVoice }.distinctUntilChanged().drop(1).collect {
                if (isSupertonicActive()) retryAudio()
            }
        }
    }

    private suspend fun refreshVoices() {
        val book = _state.value.book ?: return
        val engine = engines[engineSelector.selectedEngineId.value] ?: return
        _state.update { it.copy(voices = engine.listVoices(book.learningLanguage)) }
    }

    private var preparingFor: String? = null

    /**
     * Motores que sintetizan (Supertonic): antes de empezar a leer, genera las primeras frases hasta reunir unos
     * [WarmUpPolicy.targetDurationMs] de audio (como mucho 2 frases): con una primera frase larga no espera a la
     * segunda. Así hay ya un pequeño búfer para arrancar sin cortes. Si tarda (carga del modelo, móvil lento) se muestra la
     * pantalla «Preparando la lectura…» y suena la frase pregenerada del paquete de voces, que se deja
     * terminar. Si las frases ya estaban en caché no se ve nada.
     * @param immediate al abrir el libro: pantalla de preparación desde el primer momento.
     */
    private suspend fun prepareAudio(book: Book, passage: Passage, offset: Int, immediate: Boolean) {
        if (!isSupertonicActive() || !playback.value.isAudioAvailable) return
        val from = OffsetIndex.sentenceAt(passage.sentences, offset)?.index ?: 0
        preparingFor = passage.id
        try {
            coroutineScope {
                val overlay = async {
                    if (!immediate) delay(PREPARING_GRACE_MS) // si va rápido, ni se nota
                    _state.update { it.copy(preparing = true) }
                    voicePacks.clip(book.learningLanguage, voiceFor(book) ?: SupertonicModel.DEFAULT_VOICE, VoiceClip.READY)?.let { cue.play(it) }
                }
                audio.warmUp(passage, book.learningLanguage, voiceFor(book), WarmUpPolicy(), from)
                // Si la pantalla aún no había salido, no hace falta; si salió, se deja acabar la frase.
                if (overlay.isActive && !_state.value.preparing) overlay.cancel() else overlay.await()
            }
        } finally {
            if (preparingFor == passage.id) {
                preparingFor = null
                _state.update { it.copy(preparing = false) }
            }
        }
    }

    private fun isSupertonicActive() = engineSelector.selectedEngineId.value == SupertonicModel.ENGINE_ID

    /**
     * Precarga (en motores que generan audio) las [PREFETCH_PAGES_AHEAD] páginas siguientes mientras suena la actual.
     * La ventana se desplaza con cada cambio de página: la generación no se detiene al final de la página que suena.
     */
    private suspend fun prefetchNext(book: Book, index: Int) {
        val upcoming = PrefetchWindow.after(book.passages, index)
        if (upcoming.isNotEmpty()) audio.prefetch(upcoming, book.learningLanguage, voiceFor(book))
    }

    // --------------------------------------------------------------------------------------
    // Navegación por pasajes
    // --------------------------------------------------------------------------------------

    /**
     * @param continuation cambio de página automático al terminar la anterior mientras se leía: la lectura debe
     *   continuar sin interrupciones, así que no se muestra «Preparando la lectura» ni suena su aviso. Normalmente
     *   el audio de la página ya está en caché (precarga de [PREFETCH_PAGES_AHEAD] páginas); si no, la barra del
     *   reproductor indica «Generando audio…» y empieza a sonar con la primera frase lista.
     */
    private fun showPassage(index: Int, offset: Int, autoplay: Boolean, immediate: Boolean = false, continuation: Boolean = false) {
        val book = _state.value.book ?: return
        val passage = book.passages.getOrNull(index) ?: return
        passageJob?.cancel()
        completionHandledFor = null
        _state.update {
            it.copy(
                loading = false, passage = passage, chapter = book.chapterOf(passage), passageIndex = index,
                image = null, revealedSentences = emptySet(), wordCard = null, sentenceCard = null,
            )
        }
        passageJob = viewModelScope.launch {
            audio.load(passage, book.learningLanguage, voiceFor(book), offset)
            persist(passage, offset)
            if (!continuation) prepareAudio(book, passage, offset, immediate)
            if (autoplay) audio.play()
            prefetchNext(book, index)
            refreshTranslations()
            passage.image?.let { media ->
                val bitmap = books.loadMedia(book.id, media).toImageBitmap()
                _state.update { s -> if (s.passage?.id == passage.id) s.copy(image = bitmap) else s }
            }
        }
    }

    fun nextPassage() = showPassage(_state.value.passageIndex + 1, 0, autoplay = playback.value.isPlaying)
    fun previousPassage() = showPassage(_state.value.passageIndex - 1, 0, autoplay = playback.value.isPlaying)

    fun goToPassage(passageId: String, offset: Int = 0) {
        val index = _state.value.book?.passages?.indexOfFirst { it.id == passageId } ?: return
        if (index >= 0) showPassage(index, offset, autoplay = false)
    }

    private fun onPlayback(st: PlaybackState) {
        val current = _state.value
        val passage = current.passage ?: return
        if (st.passageId != passage.id || st.status != PlaybackStatus.COMPLETED) return
        if (completionHandledFor == passage.id) return
        completionHandledFor = passage.id
        if (preferences.value.autoContinue && current.hasNext) {
            showPassage(current.passageIndex + 1, 0, autoplay = true, continuation = true)
        }
    }

    // --------------------------------------------------------------------------------------
    // Reproducción
    // --------------------------------------------------------------------------------------

    fun togglePlay() = viewModelScope.launch {
        if (playback.value.isPlaying) audio.pause() else audio.play()
    }

    fun nextSentence() = viewModelScope.launch {
        val passage = _state.value.passage ?: return@launch
        val next = playback.value.sentenceIndex + 1
        if (next <= passage.sentences.lastIndex) audio.seekToSentence(next)
        else if (_state.value.hasNext) nextPassage()
    }

    fun previousSentence() = viewModelScope.launch {
        val st = playback.value
        if (st.sentenceIndex > 0) audio.seekToSentence(st.sentenceIndex - 1)
        else if (_state.value.hasPrevious) previousPassage()
    }

    fun repeatSentence() = viewModelScope.launch {
        audio.seekToSentence(playback.value.sentenceIndex)
        if (!playback.value.isPlaying) audio.play()
    }

    fun readFromToken(tokenId: String) = viewModelScope.launch {
        val token = _state.value.passage?.tokenById(tokenId) ?: return@launch
        _state.update { it.copy(wordCard = null) }
        audio.seekToOffset(token.rangeStart)
        audio.play()
    }

    fun retryAudio() = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        val passage = _state.value.passage ?: return@launch
        audio.load(passage, book.learningLanguage, voiceFor(book), playback.value.resumeOffset)
        prepareAudio(book, passage, playback.value.resumeOffset, immediate = false)
        refreshVoices()
        prefetchNext(book, passage.index)
    }

    private fun voiceFor(book: Book): String? =
        if (isSupertonicActive()) preferences.value.supertonicVoice
        else preferences.value.voiceByLanguage[LanguageTags.primary(book.learningLanguage)]

    /** Voz seleccionada en el motor activo (para la hoja de ajustes del lector). */
    fun selectedVoice(): String? = _state.value.book?.let(::voiceFor)

    // --------------------------------------------------------------------------------------
    // Palabras, frases y traducción
    // --------------------------------------------------------------------------------------

    fun onTokenTap(tokenId: String) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        val passage = _state.value.passage ?: return@launch
        val token = passage.tokenById(tokenId) ?: return@launch
        val prefs = preferences.value
        val paused = pauseIfNeeded(prefs)
        if (prefs.displayMode == DisplayMode.TRANSLATION_ON_TAP) {
            _state.update { it.copy(revealedSentences = it.revealedSentences + token.sentenceId) }
        }
        val target = prefs.translationLanguage
        _state.update { it.copy(wordCard = WordCardState(tokenId, pausedForCard = paused, targetLanguage = target), sentenceCard = null) }
        val lookup = lookupService.lookup(book, passage, token, target)
        noteMissingModels(book, target, lookup.word, lookup.sentenceTranslation)
        val term = lookup.expression?.surface ?: token.text
        val saved = vocabularyDao.exists(book.id, term.lowercase(), target)
        _state.update { s ->
            val card = s.wordCard
            if (card != null && card.tokenId == tokenId) s.copy(wordCard = card.copy(lookup = lookup, saved = saved)) else s
        }
    }

    fun onTokenLongPress(tokenId: String) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        val passage = _state.value.passage ?: return@launch
        val sentence = passage.tokenById(tokenId)?.let { passage.sentenceById(it.sentenceId) } ?: return@launch
        openSentenceCard(book, passage, sentence.id)
    }

    fun onSentenceAction(sentenceId: String) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        val passage = _state.value.passage ?: return@launch
        openSentenceCard(book, passage, sentenceId)
    }

    private suspend fun openSentenceCard(book: Book, passage: Passage, sentenceId: String) {
        val sentence = passage.sentenceById(sentenceId) ?: return
        val prefs = preferences.value
        val paused = pauseIfNeeded(prefs)
        val translation = lookupService.translateSentence(book, passage, sentence, prefs.translationLanguage)
        noteMissingModels(book, prefs.translationLanguage, translation)
        _state.update {
            it.copy(
                wordCard = null,
                sentenceCard = SentenceCardState(sentence.id, sentence.text, translation, paused, prefs.translationLanguage),
            )
        }
    }

    private suspend fun pauseIfNeeded(prefs: UserPreferences): Boolean {
        val wasPlaying = playback.value.isPlaying
        if (wasPlaying && prefs.pauseOnWordTap) audio.pause()
        return wasPlaying && prefs.pauseOnWordTap
    }

    fun dismissCards() = _state.update { it.copy(wordCard = null, sentenceCard = null) }

    fun resumeAfterCard() = viewModelScope.launch {
        dismissCards()
        audio.play()
    }

    fun speak(text: String) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        audio.speakSnippet(text, book.learningLanguage)
    }

    fun toggleRevealed(sentenceId: String) = _state.update {
        it.copy(revealedSentences = if (sentenceId in it.revealedSentences) it.revealedSentences - sentenceId else it.revealedSentences + sentenceId)
    }

    private suspend fun refreshTranslations() {
        val book = _state.value.book ?: return
        val passage = _state.value.passage ?: return
        val prefs = preferences.value
        // Quien solo quiere leer no paga traducciones: se traduce al tocar una palabra o frase.
        if (prefs.displayMode == DisplayMode.TEXT_ONLY) return
        val target = prefs.translationLanguage
        val map = passage.sentences.associate { it.id to lookupService.translateSentence(book, passage, it, target) }
        noteMissingModels(book, target, *map.values.toTypedArray())
        _state.update { s -> if (s.passage?.id == passage.id) s.copy(sentenceTranslations = map) else s }
    }

    /** Si alguna traducción falló por falta de modelo, anota qué idiomas hay que descargar. */
    private suspend fun noteMissingModels(book: Book, target: String, vararg results: TranslationResult) {
        val missing = if (results.any { (it as? TranslationResult.Unavailable)?.reason == TranslationResult.Reason.MODEL_MISSING }) {
            listOfNotNull(translationModels.code(book.learningLanguage), translationModels.code(target))
                .distinct().filterNot { translationModels.isDownloaded(it) }
        } else {
            emptyList()
        }
        _state.update { it.copy(missingTranslationModels = missing) }
    }

    /** Descarga los modelos que faltan y vuelve a traducir el pasaje. */
    fun downloadTranslationModels() = viewModelScope.launch {
        val ok = _state.value.missingTranslationModels.all { translationModels.awaitDownload(it) }
        if (ok) {
            _state.update { it.copy(missingTranslationModels = emptyList()) }
            refreshTranslations()
        }
    }

    fun saveWord() = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        val card = _state.value.wordCard ?: return@launch
        val lookup = card.lookup ?: return@launch
        val expression = lookup.expression
        val term = expression?.surface ?: lookup.token.text
        val translation = (lookup.expressionTranslation as? TranslationResult.Translated)?.text
            ?: (lookup.word as? TranslationResult.Translated)?.text
        vocabularyDao.upsert(
            VocabularyEntity(
                bookId = book.id,
                bookTitle = book.title,
                term = term.lowercase(),
                lemma = expression?.entry?.lemma ?: lookup.wordLemma,
                translation = translation,
                sourceLanguage = book.learningLanguage,
                targetLanguage = card.targetLanguage,
                context = lookup.sentence.text,
                createdAt = System.currentTimeMillis(),
            ),
        )
        _state.update { s -> s.copy(wordCard = s.wordCard?.copy(saved = true)) }
    }

    // --------------------------------------------------------------------------------------
    // Marcadores, progreso y preferencias
    // --------------------------------------------------------------------------------------

    /** Marcador del pasaje/frase actual, si existe. */
    fun currentBookmark(): BookmarkEntity? {
        val passage = _state.value.passage ?: return null
        val sentence = passage.sentences.getOrNull(playback.value.sentenceIndex) ?: return null
        return _state.value.bookmarks.firstOrNull { it.passageId == passage.id && it.charOffset in sentence.rangeStart until sentence.rangeEnd }
    }

    fun toggleBookmark() = viewModelScope.launch {
        val existing = currentBookmark()
        if (existing != null) {
            bookmarkDao.delete(existing.id)
            return@launch
        }
        val passage = _state.value.passage ?: return@launch
        val sentence = passage.sentences.getOrNull(playback.value.sentenceIndex) ?: return@launch
        bookmarkDao.insert(
            BookmarkEntity(
                bookId = bookId, passageId = passage.id, passageIndex = passage.index,
                charOffset = sentence.rangeStart, excerpt = sentence.text, createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch { bookmarkDao.delete(id) }

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) = viewModelScope.launch {
        val before = preferences.value
        preferencesRepository.update(transform)
        val after = preferencesRepository.preferences.first()
        if (before.voiceByLanguage != after.voiceByLanguage) retryAudio()
    }

    fun setVoice(voiceId: String?) {
        val book = _state.value.book ?: return
        if (isSupertonicActive()) {
            updatePreferences { it.copy(supertonicVoice = voiceId ?: "F1") }
            return
        }
        val key = LanguageTags.primary(book.learningLanguage)
        updatePreferences { p ->
            p.copy(voiceByLanguage = if (voiceId == null) p.voiceByLanguage - key else p.voiceByLanguage + (key to voiceId))
        }
    }

    private suspend fun saveProgress(st: PlaybackState) {
        val passage = _state.value.passage ?: return
        if (st.passageId != passage.id) return
        persist(passage, if (st.status == PlaybackStatus.COMPLETED) 0 else st.resumeOffset)
    }

    private suspend fun persist(passage: Passage, offset: Int) {
        progressDao.upsert(
            ReadingProgressEntity(
                bookId = bookId, passageId = passage.id, passageIndex = passage.index,
                totalPassages = _state.value.totalPassages, charOffset = offset, updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override fun onCleared() {
        cue.stop()
        val passage = _state.value.passage
        appScope.launch {
            audio.stop()
            if (passage != null && playback.value.passageId == passage.id) persist(passage, playback.value.resumeOffset)
        }
    }
}

private const val PREPARING_GRACE_MS = 300L
