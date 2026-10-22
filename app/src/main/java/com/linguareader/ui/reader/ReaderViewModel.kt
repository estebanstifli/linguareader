package com.linguareader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.linguareader.R
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.Book
import com.linguareader.core.model.Chapter
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.Passage
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.narration.NarrationSelector
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
import com.linguareader.data.narration.NarrationLibrary
import com.linguareader.data.db.BookmarkDao
import com.linguareader.data.db.BookmarkEntity
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.db.ReadingProgressEntity
import com.linguareader.data.db.VocabularyDao
import com.linguareader.data.db.VocabularyEntity
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.content.BookExporter
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.library.LibraryRepository
import com.linguareader.data.library.LibraryState
import com.linguareader.engine.audio.CuePlayer
import com.linguareader.engine.packs.VoiceClip
import com.linguareader.engine.packs.VoicePackManager
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.ui.common.UiText
import com.linguareader.ui.common.toImageBitmap
import com.linguareader.ui.navigation.ReaderRoute
import com.linguareader.ui.reader.paging.BookBlocks
import com.linguareader.ui.reader.paging.TextPos
import java.text.Normalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Resultado de buscar texto en el libro. */
data class SearchHit(val pos: TextPos, val chapterTitle: String, val before: String, val match: String, val after: String)

data class WordCardState(
    val tokenId: String,
    val lookup: WordLookup? = null,
    val saved: Boolean = false,
    /** La lectura estaba sonando y se pausó al tocar: ofrecer "Reanudar". */
    val pausedForCard: Boolean = false,
    val targetLanguage: String,
    /** false = minitarjeta junto a la palabra; true = ficha completa («Más información»). */
    val expanded: Boolean = false,
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
    val error: UiText? = null,
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
    /** Búsqueda en el libro: null = sin buscar todavía. */
    val searchHits: List<SearchHit>? = null,
    val searching: Boolean = false,
) {
    val hasPrevious: Boolean get() = passageIndex > 0
    val hasNext: Boolean get() = passageIndex < totalPassages - 1
}

class ReaderViewModel(
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
    private val libraryRepository: LibraryRepository,
    private val exporter: BookExporter,
    private val importedBooks: ImportedBookRepository,
    private val narrations: NarrationLibrary,
    private val narrationSelector: NarrationSelector,
    private val appScope: CoroutineScope,
) : ViewModel() {

    /** Estanterías y papelera (menú «Agregar» del lector). */
    val library: StateFlow<LibraryState> = libraryRepository.state.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    /** Palabras y expresiones guardadas de este libro (pestaña «Vocabulario» del índice). */
    val bookVocabulary: StateFlow<List<VocabularyEntity>> = vocabularyDao.observeAll()
        .map { list -> list.filter { it.bookId == bookId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Va a la frase en la que se guardó una palabra. */
    fun goToContext(entry: VocabularyEntity) {
        val book = _state.value.book ?: return
        for (p in book.passages) {
            val at = p.text.indexOf(entry.context)
            if (at >= 0) { jumpTo(TextPos(p.index, at)); return }
        }
    }

    /** Libro importado por el usuario (para «Acerca del documento»). */
    val isImported: Boolean get() = importedBooks.owns(bookId)

    fun setInShelf(shelfId: Long, inShelf: Boolean) = viewModelScope.launch { libraryRepository.setInShelf(shelfId, bookId, inShelf) }

    fun createCollection(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) libraryRepository.createCollection(name, bookId)
    }

    /** Envía el libro a la papelera (sale de la biblioteca; se puede restaurar). */
    fun moveToTrash(onDone: () -> Unit) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        audio.pause()
        libraryRepository.trash(book.id, book.title)
        onDone()
    }

    /** TXT del libro listo para compartir, o null si falla. */
    suspend fun exportForSharing(): android.net.Uri? {
        val book = _state.value.book ?: return null
        return runCatching { exporter.exportText(book) }.getOrNull()
    }


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

    /** Posición de la página visible (o de la última leída). La pantalla pagina alrededor de ella. */
    private val _anchor = MutableStateFlow<TextPos?>(null)

    /**
     * Punto exacto elegido al abrir el libro, pasar página o saltar. El audio se coloca al principio de la frase (que
     * puede estar en la página anterior): mientras no suene ni cambie de frase, la posición de lectura es este punto.
     */
    private var pinned: TextPos? = null
    val anchor: StateFlow<TextPos?> = _anchor.asStateFlow()

    /** Saltos explícitos (índice, marcadores, búsqueda): la pantalla va a la página de esa posición. */
    private val _jumps = MutableSharedFlow<TextPos>(extraBufferCapacity = 4)
    val jumps: SharedFlow<TextPos> = _jumps

    /** Traducciones de frases conocidas para todo el libro (clave [BookBlocks.key]). */
    private val _translations = MutableStateFlow<Map<String, String>>(emptyMap())
    val translations: StateFlow<Map<String, String>> = _translations.asStateFlow()

    /** Ilustraciones cargadas, por índice global de pasaje. */
    private val _images = MutableStateFlow<Map<Int, ImageBitmap>>(emptyMap())
    val images: StateFlow<Map<Int, ImageBitmap>> = _images.asStateFlow()
    private val imageRequests = mutableSetOf<Int>()
    private var translationTarget: String? = null
    private val translatedPassages = mutableSetOf<Int>()

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val book = runCatching { books.loadBook(bookId) }.getOrElse {
            _state.update { s -> s.copy(loading = false, error = UiText.res(R.string.book_edit_failed)) }
            return
        }
        _state.update { it.copy(book = book, totalPassages = book.passages.size) }
        val prefs = preferencesRepository.preferences.first()
        audio.setSpeed(prefs.speechRate)

        val saved = progressDao.get(bookId)
        val index = saved?.passageIndex?.coerceIn(0, book.passages.lastIndex) ?: 0
        val offset = saved?.takeIf { book.passages[index].id == it.passageId }?.charOffset ?: 0
        _anchor.value = TextPos(index, offset)
        pinned = _anchor.value
        seedTranslations(book, prefs.translationLanguage)
        // Narración pregrabada: si ya está descargada se lee con ella en lugar del motor TTS.
        narrationSelector.setNarration(narrations.cached(book))
        if (narrations.needsDownload(book)) awaitNarration(book)
        showPassage(index, offset, autoplay = false, immediate = true)

        viewModelScope.launch { bookmarkDao.observeForBook(bookId).collect { list -> _state.update { it.copy(bookmarks = list) } } }
        viewModelScope.launch {
            // También al cambiar de modo: «solo texto» no traduce nada en segundo plano.
            preferences.map { it.translationLanguage to it.displayMode }.distinctUntilChanged().collect { (target, _) ->
                seedTranslations(book, target)
                refreshTranslations()
            }
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

    /**
     * Descarga en segundo plano la narración del libro. Mientras tanto (o si falla) se lee con el motor TTS; cuando
     * está lista se cambia a ella, pero no a mitad de una lectura: en la siguiente pausa, en el mismo punto.
     */
    private fun awaitNarration(book: Book) = viewModelScope.launch {
        narrations.ready.onSubscription { narrations.download(book) }.first { it == book.id }
        playback.first { !it.isPlaying }
        val narration = narrations.cached(book) ?: return@launch
        narrationSelector.setNarration(narration)
        retryAudio()
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
        if (!preferences.value.ttsMode || !playback.value.isAudioAvailable) return
        val from = OffsetIndex.sentenceAt(passage.sentences, offset)?.index ?: 0
        // Voz del sistema: genera rápido, basta con tener lista la primera frase (sin pantalla de preparación).
        if (!isSupertonicActive()) {
            audio.warmUp(passage, book.learningLanguage, voiceFor(book), WarmUpPolicy(maxSentences = 1), from)
            return
        }
        preparingFor = passage.id
        try {
            coroutineScope {
                val overlay = async {
                    if (!immediate) delay(PREPARING_GRACE_MS) // si va rápido, ni se nota
                    _state.update { it.copy(preparing = true) }
                    voicePacks.clip(book.learningLanguage, voiceFor(book) ?: SupertonicModel.DEFAULT_VOICE, VoiceClip.READY)?.let { cue.play(it.toFile()) }
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

    fun isSupertonicActive() = engineSelector.selectedEngineId.value == SupertonicModel.ENGINE_ID

    /**
     * Precarga (en motores que generan audio) las [PREFETCH_PAGES_AHEAD] páginas siguientes mientras suena la actual.
     * La ventana se desplaza con cada cambio de página: la generación no se detiene al final de la página que suena.
     */
    private suspend fun prefetchNext(book: Book, index: Int) {
        if (!preferences.value.ttsMode) return // lector clásico: no se genera audio
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
            // Pasar páginas en pausa no genera audio: solo al abrir el libro o al empezar a leer.
            if (!continuation && (autoplay || immediate)) prepareAudio(book, passage, offset, immediate)
            if (autoplay) audio.play()
            prefetchNext(book, index)
            refreshTranslations()
        }
    }

    // --------------------------------------------------------------------------------------
    // Páginas
    // --------------------------------------------------------------------------------------

    /**
     * La pantalla se asentó en una página que empieza en [pos]. Si la cambió el usuario ([user]), la lectura se
     * pausa (como al pulsar pausa) y se reanudará desde el principio de esa página.
     */
    fun onPageSettled(pos: TextPos, passagesOnPage: Set<Int>, user: Boolean) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        requestImages(book, passagesOnPage)
        // Si la página la puso el programa (abrir, girar, seguir la voz), el punto de lectura no cambia: con el inicio
        // de la página retrocedería un poco en cada nueva paginación.
        if (!user) {
            ensureTranslations(book, passagesOnPage)
            return@launch
        }
        val passage = book.passages.getOrNull(pos.passage) ?: return@launch
        _anchor.value = pos
        pinned = pos
        if (playback.value.isPlaying) audio.pause()
        if (passage.index != _state.value.passageIndex) {
            showPassage(passage.index, pos.offset, autoplay = false)
        } else {
            audio.seekToOffset(pos.offset)
            persist(passage, pos.offset)
        }
        ensureTranslations(book, passagesOnPage)
    }

    private fun jumpTo(pos: TextPos, autoplay: Boolean = false) {
        _anchor.value = pos
        pinned = pos
        _jumps.tryEmit(pos)
        if (pos.passage != _state.value.passageIndex) showPassage(pos.passage, pos.offset, autoplay = autoplay)
        else viewModelScope.launch { audio.seekToOffset(pos.offset) }
    }

    private fun requestImages(book: Book, passages: Set<Int>) {
        for (index in passages) {
            val media = book.passages.getOrNull(index)?.image ?: continue
            if (!imageRequests.add(index)) continue
            viewModelScope.launch {
                books.loadMedia(book.id, media).toImageBitmap()?.let { bitmap -> _images.update { it + (index to bitmap) } }
            }
        }
    }

    /** Traducciones incluidas en el libro (instantáneas): toda la obra, para paginar con ellas desde el principio. */
    private fun seedTranslations(book: Book, target: String) {
        if (translationTarget == target) return
        translationTarget = target
        translatedPassages.clear()
        _translations.value = buildMap {
            for (p in book.passages) for (s in p.sentences) {
                LanguageTags.lookup(s.translations, target)?.let { put(BookBlocks.key(p.id, s.id), it) }
            }
        }
    }

    /** Traduce (traductor del dispositivo) las frases de los pasajes visibles en los modos que las muestran. */
    private suspend fun ensureTranslations(book: Book, passages: Set<Int>) {
        val prefs = preferences.value
        if (prefs.displayMode != DisplayMode.TRANSLATION_BELOW) return
        val target = prefs.translationLanguage
        for (index in passages.sorted()) {
            if (!translatedPassages.add(index)) continue
            val passage = book.passages.getOrNull(index) ?: continue
            val found = passage.sentences.mapNotNull { s ->
                val key = BookBlocks.key(passage.id, s.id)
                if (key in _translations.value) return@mapNotNull null
                (lookupService.translateSentence(book, passage, s, target) as? TranslationResult.Translated)?.let { key to it.text }
            }
            if (target != preferences.value.translationLanguage) return
            if (found.isNotEmpty()) _translations.update { it + found }
        }
    }

    /** Busca [query] en todo el libro (sin distinguir mayúsculas ni acentos). Máximo [MAX_SEARCH_HITS] resultados. */
    fun search(query: String) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        val q = fold(query.trim())
        if (q.length < 2) { _state.update { it.copy(searchHits = null) }; return@launch }
        _state.update { it.copy(searching = true) }
        val hits = withContext(Dispatchers.Default) {
            val out = mutableListOf<SearchHit>()
            for (p in book.passages) {
                // El texto plegado conserva la longitud (cada carácter se pliega a uno): offsets válidos en el original.
                val folded = fold(p.text)
                var from = folded.indexOf(q)
                while (from >= 0 && out.size < MAX_SEARCH_HITS) {
                    val end = from + q.length
                    out += SearchHit(
                        pos = TextPos(p.index, from),
                        chapterTitle = book.chapterOf(p)?.title.orEmpty(),
                        before = p.text.substring(maxOf(0, from - 40), from),
                        match = p.text.substring(from, end),
                        after = p.text.substring(end, minOf(p.text.length, end + 60)),
                    )
                    from = folded.indexOf(q, end)
                }
                if (out.size >= MAX_SEARCH_HITS) break
            }
            out
        }
        _state.update { it.copy(searchHits = hits, searching = false) }
    }

    fun clearSearch() = _state.update { it.copy(searchHits = null, searching = false) }

    fun goToSearchHit(hit: SearchHit) = jumpTo(hit.pos)

    fun goToBookmark(bookmark: BookmarkEntity) = jumpTo(TextPos(bookmark.passageIndex, bookmark.charOffset))

    /** Marcador al principio de la página visible. */
    fun addBookmark(pos: TextPos) = viewModelScope.launch {
        val passage = _state.value.book?.passages?.getOrNull(pos.passage) ?: return@launch
        val excerpt = passage.text.substring(pos.offset.coerceIn(0, passage.text.length)).take(140).trim()
        bookmarkDao.insert(
            BookmarkEntity(
                bookId = bookId, passageId = passage.id, passageIndex = passage.index,
                charOffset = pos.offset, excerpt = excerpt, createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Lectura en voz alta desde la página visible. */
    fun playFrom(pos: TextPos) {
        if (pos.passage != _state.value.passageIndex) showPassage(pos.passage, pos.offset, autoplay = true)
        else viewModelScope.launch { audio.seekToOffset(pos.offset); audio.play() }
    }

    fun pause() = viewModelScope.launch { audio.pause() }

    fun nextPassage() = showPassage(_state.value.passageIndex + 1, 0, autoplay = playback.value.isPlaying)
    fun previousPassage() = showPassage(_state.value.passageIndex - 1, 0, autoplay = playback.value.isPlaying)

    fun goToPassage(passageId: String, offset: Int = 0) {
        val index = _state.value.book?.passages?.indexOfFirst { it.id == passageId } ?: return
        if (index >= 0) jumpTo(TextPos(index, offset))
    }

    private fun onPlayback(st: PlaybackState) {
        val current = _state.value
        val passage = current.passage ?: return
        if (st.passageId != passage.id) return
        if (st.status != PlaybackStatus.COMPLETED) {
            // El punto de lectura sigue a la voz (para volver a paginar en el mismo sitio, p. ej. al girar).
            _anchor.value = TextPos(passage.index, readingOffset(passage, st))
            return
        }
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
        val passage = passageOfToken(tokenId) ?: return@launch
        val token = passage.tokenById(tokenId) ?: return@launch
        _state.update { it.copy(wordCard = null) }
        // «Leer desde aquí» en el lector clásico activa la lectura en voz alta (altavoz y controles).
        if (!preferences.value.ttsMode) preferencesRepository.update { it.copy(ttsMode = true, ttsModeChosen = true) }
        if (passage.index != _state.value.passageIndex) {
            showPassage(passage.index, token.rangeStart, autoplay = true)
            return@launch
        }
        audio.seekToOffset(token.rangeStart)
        audio.play()
    }

    /** Pasaje al que pertenece un token (una página puede mostrar el final de un pasaje y el principio de otro). */
    private fun passageOfToken(tokenId: String): Passage? {
        val book = _state.value.book ?: return null
        val passageId = tokenId.substringBeforeLast(":t")
        return book.passages.firstOrNull { it.id == passageId }
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
        val passage = passageOfToken(tokenId) ?: return@launch
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
        val passage = passageOfToken(tokenId) ?: return@launch
        val sentence = passage.tokenById(tokenId)?.let { passage.sentenceById(it.sentenceId) } ?: return@launch
        openSentenceCard(book, passage, sentence.id)
    }

    fun onSentenceAction(passageIndex: Int, sentenceId: String) = viewModelScope.launch {
        val book = _state.value.book ?: return@launch
        val passage = book.passages.getOrNull(passageIndex) ?: return@launch
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

    /** De la minitarjeta a la ficha completa de la palabra. */
    fun expandWordCard() = _state.update { s -> s.copy(wordCard = s.wordCard?.copy(expanded = true)) }

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
        // En «Traducción al tocar» no se pagan traducciones por adelantado: se traduce al tocar una palabra o frase.
        if (prefs.displayMode != DisplayMode.TRANSLATION_BELOW) return
        val target = prefs.translationLanguage
        val map = passage.sentences.associate { it.id to lookupService.translateSentence(book, passage, it, target) }
        noteMissingModels(book, target, *map.values.toTypedArray())
        _state.update { s -> if (s.passage?.id == passage.id) s.copy(sentenceTranslations = map) else s }
        if (target == preferences.value.translationLanguage) {
            val found = map.mapNotNull { (id, t) -> (t as? TranslationResult.Translated)?.let { BookBlocks.key(passage.id, id) to it.text } }
            if (found.isNotEmpty()) _translations.update { it + found }
        }
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
        persist(passage, if (st.status == PlaybackStatus.COMPLETED) 0 else readingOffset(passage, st))
    }

    /** Dónde se va leyendo: el punto elegido ([pinned]) si el audio sigue parado en su frase; si no, el del audio. */
    private fun readingOffset(passage: Passage, st: PlaybackState): Int {
        val pin = pinned?.takeIf { it.passage == passage.index } ?: return st.resumeOffset
        val pinSentence = passage.sentences.lastOrNull { it.rangeStart <= pin.offset }?.index ?: 0
        if (st.status != PlaybackStatus.PLAYING && st.sentenceIndex == pinSentence) return pin.offset
        pinned = null
        return st.resumeOffset
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
            if (passage != null && playback.value.passageId == passage.id) persist(passage, readingOffset(passage, playback.value))
        }
    }
}

private const val PREPARING_GRACE_MS = 300L
private const val MAX_SEARCH_HITS = 200

/** Minúsculas y sin diacríticos, carácter a carácter (misma longitud que el original). */
private fun fold(text: String): String = buildString(text.length) {
    for (c in text) {
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
        append(base.lowercaseChar())
    }
}
