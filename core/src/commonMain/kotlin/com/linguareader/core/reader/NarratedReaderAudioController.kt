package com.linguareader.core.reader

import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.model.Passage
import com.linguareader.core.model.Token
import com.linguareader.core.narration.Narration
import com.linguareader.core.text.OffsetIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [ReaderAudioController] para una **narración pregrabada** del libro: una pista de audio continua (con sus pausas,
 * voces y efectos ya grabados) y los tiempos de cada palabra ([Narration.timeline]).
 *
 * - La pista suena sin cortes: el lector sigue trabajando por pasajes, así que al acabar la última palabra del pasaje
 *   se informa `COMPLETED` y el reproductor sigue sonando ([continuous]); el `load` del pasaje siguiente se reconoce
 *   como continuación y no toca el reproductor. Sin [continuous], se pausa al final del pasaje.
 * - Resaltado por palabra con la posición del reproductor (búsqueda binaria), interpolada entre sus saltos.
 * - Saltos exactos: a la frase, a la palabra (`seekToOffset`) o al milisegundo.
 */
class NarratedReaderAudioController(
    private val player: ClipPlayer,
    private val dispatcher: CoroutineDispatcher,
    /** «Continuar con el siguiente pasaje»: si es false, la narración se pausa al final de cada pasaje. */
    private val continuous: () -> Boolean = { true },
    private val tickMs: Long = 40,
    private val clockMs: () -> Long = monotonicClockMs(),
) : ReaderAudioController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(idleState(null))
    override val playbackState: StateFlow<PlaybackState> = _state.asStateFlow()

    private var narration: Narration? = null
    private var queuedUri: String? = null
    private var passage: Passage? = null
    private var tokensById: Map<String, Token> = emptyMap()
    private var pendingSeekMs: Long? = null
    private var playing = false
    private var completedFor: String? = null
    private var tickerJob: Job? = null

    private var anchorPositionMs = -1L
    private var anchorAtMs = 0L

    init {
        scope.launch { player.events.collect(::onPlayerEvent) }
    }

    /** Cambia de narración (otro libro) o la quita. */
    suspend fun attach(narration: Narration?) = withContext(dispatcher) {
        if (narration === this@NarratedReaderAudioController.narration) return@withContext
        player.pause()
        player.clear()
        playing = false
        queuedUri = null
        passage = null
        pendingSeekMs = null
        this@NarratedReaderAudioController.narration = narration
        _state.update { idleState(narration).copy(speed = it.speed) }
    }

    private fun idleState(n: Narration?) = PlaybackState(
        engineId = ENGINE_ID,
        supportsSpeed = true,
        canSeekByTime = n != null,
        readiness = if (n != null) EngineReadiness.Ready
            else EngineReadiness.Unavailable(EngineUnavailableReason.ENGINE_NOT_INSTALLED, ""),
    )

    override suspend fun load(passage: Passage, languageTag: String, voiceId: String?, startOffset: Int) = withContext(dispatcher) {
        val n = narration ?: return@withContext
        this@NarratedReaderAudioController.passage = passage
        tokensById = passage.tokens.associateBy { it.id }
        completedFor = null
        val offset = startOffset.coerceIn(0, passage.text.length)
        val range = n.timeline.passageRange(passage.index)
        val pos = currentPositionMs()
        val continuation = playing && pos != null && range != null &&
            pos >= range.first - CONTINUATION_SLACK_MS && pos <= range.last
        if (!continuation) seekInternal(startFor(passage, offset))
        val sentence = sentenceFor(passage, offset)
        _state.update {
            it.copy(
                status = if (playing) PlaybackStatus.PLAYING else PlaybackStatus.READY,
                passageId = passage.id, sentenceIndex = sentence.index, activeTokenId = null,
                granularity = if (playing) HighlightGranularity.SENTENCE else HighlightGranularity.NONE,
                resumeOffset = if (continuation) 0 else tokenStartAt(passage, offset) ?: sentence.rangeStart,
                buffering = false, errorCode = null, readiness = EngineReadiness.Ready,
            )
        }
    }

    override suspend fun play() = withContext(dispatcher) {
        val n = narration ?: return@withContext
        val p = passage ?: return@withContext
        if (playing) {
            _state.update { it.copy(status = PlaybackStatus.PLAYING) }
            return@withContext
        }
        if (queuedUri != n.audioUri) {
            player.clear()
            player.enqueue(TAG, n.audioUri)
            queuedUri = n.audioUri
            if (pendingSeekMs == null) pendingSeekMs = startFor(p, _state.value.resumeOffset)
        }
        // Tras terminar el pasaje (sin continuar al siguiente), play lo vuelve a leer, como los motores TTS.
        if (_state.value.status == PlaybackStatus.COMPLETED && completedFor == p.id) pendingSeekMs = startFor(p, 0)
        pendingSeekMs?.let { player.seekTo(TAG, it) }
        pendingSeekMs = null
        completedFor = null
        resetAnchor()
        player.play()
        playing = true
        _state.update { it.copy(status = PlaybackStatus.PLAYING, granularity = HighlightGranularity.SENTENCE, buffering = false) }
        ensureTicker()
    }

    override suspend fun pause() = withContext(dispatcher) {
        if (!playing) return@withContext
        player.pause()
        playing = false
        _state.update { it.copy(status = PlaybackStatus.PAUSED) }
    }

    override suspend fun stop() = pause()

    override suspend fun seekToSentence(index: Int) = withContext(dispatcher) {
        val p = passage ?: return@withContext
        val sentence = p.sentences[index.coerceIn(0, p.sentences.lastIndex)]
        seekInternal(startFor(p, sentence.rangeStart))
        _state.update {
            it.copy(
                sentenceIndex = sentence.index, resumeOffset = sentence.rangeStart, activeTokenId = null,
                status = if (it.status == PlaybackStatus.COMPLETED) PlaybackStatus.PAUSED else it.status,
            )
        }
    }

    /** Con tiempos por palabra, «leer desde aquí» empieza exactamente en la palabra. */
    override suspend fun seekToOffset(charOffset: Int) = withContext(dispatcher) {
        val p = passage ?: return@withContext
        val offset = charOffset.coerceIn(0, p.text.length)
        seekInternal(startFor(p, offset))
        val sentence = sentenceFor(p, offset)
        _state.update {
            it.copy(
                sentenceIndex = sentence.index, resumeOffset = tokenStartAt(p, offset) ?: sentence.rangeStart,
                status = if (it.status == PlaybackStatus.COMPLETED) PlaybackStatus.PAUSED else it.status,
            )
        }
    }

    override suspend fun seekTo(milliseconds: Long): Boolean = withContext(dispatcher) {
        if (narration == null) return@withContext false
        seekInternal(milliseconds)
        true
    }

    override suspend fun setSpeed(speed: Float) = withContext(dispatcher) {
        _state.update { it.copy(speed = speed) }
        player.setPlaybackSpeed(speed)
    }

    /** El audio de una palabra suelta lo da un motor TTS ([SwitchingReaderAudioController]); aquí solo se pausa. */
    override suspend fun speakSnippet(text: String, languageTag: String) = pause()

    private fun seekInternal(ms: Long?) {
        if (ms == null) return
        completedFor = null
        if (playing) {
            player.seekTo(TAG, ms)
            resetAnchor()
        } else {
            pendingSeekMs = ms
        }
    }

    /** Inicio de la palabra en [offset] o de la siguiente con tiempo; si el pasaje no tiene, el del siguiente que tenga. */
    private fun startFor(p: Passage, offset: Int): Long? {
        val tl = narration?.timeline ?: return null
        val token = p.tokens.firstOrNull { it.rangeEnd > offset && tl.timeOf(it.id) != null }
        return token?.let { tl.timeOf(it.id)?.startMs } ?: tl.startFrom(p.index)
    }

    private fun tokenStartAt(p: Passage, offset: Int): Int? = p.tokens.firstOrNull { it.rangeEnd > offset }?.rangeStart

    private fun currentPositionMs(): Long? = player.position()?.takeIf { it.tag == TAG }?.positionMs

    private fun onPlayerEvent(event: ClipEvent) {
        when (event) {
            is ClipEvent.Started -> Unit
            ClipEvent.QueueEnded -> {
                playing = false
                _state.update { it.copy(status = PlaybackStatus.COMPLETED, granularity = HighlightGranularity.NONE, activeTokenId = null, resumeOffset = 0) }
            }
            is ClipEvent.Error -> {
                playing = false
                _state.update { it.copy(status = PlaybackStatus.ERROR, buffering = false) }
            }
        }
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && playing) {
                tick()
                delay(tickMs)
            }
        }
    }

    private fun resetAnchor() {
        anchorPositionMs = -1L
    }

    /**
     * Posición del reproductor → palabra activa. La posición llega a saltos (≈ 250 ms según el dispositivo): se
     * interpola con el reloj entre saltos, como en [RenderedReaderAudioController].
     */
    private fun tick() {
        val n = narration ?: return
        val p = passage ?: return
        val raw = currentPositionMs() ?: return
        val now = clockMs()
        if (raw != anchorPositionMs) {
            anchorPositionMs = raw
            anchorAtMs = now
        }
        val pos = raw + ((now - anchorAtMs) * _state.value.speed).toLong().coerceIn(0L, MAX_EXTRAPOLATION_MS)
        if (completedFor == p.id) return // la pista sigue en el pasaje siguiente; el lector cargará ese pasaje
        val range = n.timeline.passageRange(p.index)
        if (range == null || pos > range.last) {
            completedFor = p.id
            if (!continuous()) {
                player.pause()
                playing = false
            }
            _state.update {
                it.copy(status = PlaybackStatus.COMPLETED, granularity = HighlightGranularity.NONE, activeTokenId = null, resumeOffset = 0)
            }
            return
        }
        val timed = n.timeline.tokenAt(pos) ?: return
        val token = tokensById[timed.tokenId] ?: return // aún en el pasaje anterior (salto al inicio de este)
        val sentence = p.sentences.firstOrNull { it.id == token.sentenceId } ?: return
        _state.update {
            if (!it.isPlaying || it.passageId != p.id) it
            else it.copy(
                activeTokenId = token.id, granularity = HighlightGranularity.WORD,
                sentenceIndex = sentence.index, resumeOffset = token.rangeStart,
            )
        }
    }

    private fun sentenceFor(p: Passage, offset: Int) =
        OffsetIndex.sentenceAt(p.sentences, offset)
            ?: p.sentences.lastOrNull { it.rangeStart <= offset }
            ?: p.sentences.first()

    companion object {
        const val ENGINE_ID = "narration"
        private const val TAG = 0

        /** Margen para reconocer como continuación un pasaje que empieza justo después de la posición actual. */
        private const val CONTINUATION_SLACK_MS = 3_000L
        private const val MAX_EXTRAPOLATION_MS = 350L
    }
}
