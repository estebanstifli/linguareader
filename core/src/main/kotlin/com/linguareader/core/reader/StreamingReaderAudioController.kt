package com.linguareader.core.reader

import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.model.Passage
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.text.OffsetIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [ReaderAudioController] sobre un motor de reproducción directa (Android TTS).
 *
 * - Un enunciado por frase → resaltado por frase siempre disponible.
 * - Resaltado por palabra solo cuando llegan [SpeechEvent.WordRange] válidos (dentro de rango y
 *   monótonos). Si una frase recibe un evento incoherente, esa frase vuelve a resaltado por frase.
 * - Pausa = detener y recordar el inicio de la última palabra oída; reanudar re-encola desde ahí.
 *
 * Todo el estado se muta en [dispatcher] (confinado), lo que permite tests deterministas.
 */
class StreamingReaderAudioController(
    private val engine: StreamingSpeechEngine,
    private val focus: AudioFocusGate,
    private val dispatcher: CoroutineDispatcher,
) : ReaderAudioController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(
        PlaybackState(engineId = engine.id, supportsSpeed = engine.supportsSpeed, canSeekByTime = false),
    )
    override val playbackState: StateFlow<PlaybackState> = _state.asStateFlow()

    private var passage: Passage? = null
    private var languageTag: String = ""
    private var voiceId: String? = null
    private var generation = 0
    private var currentSentenceReliable = true
    private var lastRangeStart = -1

    init {
        scope.launch { engine.events.collect(::onEvent) }
    }

    override suspend fun load(passage: Passage, languageTag: String, voiceId: String?, startOffset: Int) =
        withContext(dispatcher) {
            generation++
            engine.stop()
            focus.abandon()
            this@StreamingReaderAudioController.passage = passage
            this@StreamingReaderAudioController.languageTag = languageTag
            this@StreamingReaderAudioController.voiceId = voiceId
            val offset = startOffset.coerceIn(0, passage.text.length)
            val sentence = sentenceFor(passage, offset)
            _state.update {
                it.copy(
                    status = PlaybackStatus.PREPARING, passageId = passage.id, sentenceIndex = sentence.index,
                    activeTokenId = null, granularity = HighlightGranularity.NONE,
                    resumeOffset = offset, wordEventsObserved = false, errorCode = null,
                )
            }
            val readiness = runCatching { engine.prepare(languageTag, voiceId) }
                .getOrElse { EngineReadiness.Unavailable(com.linguareader.core.engine.EngineUnavailableReason.INIT_FAILED, languageTag) }
            _state.update { it.copy(status = PlaybackStatus.READY, readiness = readiness) }
        }

    override suspend fun play() = withContext(dispatcher) {
        val p = passage ?: return@withContext
        val current = _state.value
        if (current.status == PlaybackStatus.PLAYING || !current.isAudioAvailable) return@withContext
        if (!focus.request(onLoss = { scope.launch { pause() } })) return@withContext

        val resume = if (current.status == PlaybackStatus.COMPLETED) 0 else current.resumeOffset
        val sentence = sentenceFor(p, resume)
        val base = (resume - sentence.rangeStart).coerceIn(0, sentence.text.length).let { if (it >= sentence.text.length) 0 else it }
        val gen = ++generation
        for (i in sentence.index until p.sentences.size) {
            val s = p.sentences[i]
            val b = if (i == sentence.index) base else 0
            engine.enqueue(utteranceId(gen, i, b), s.text.substring(b), current.speed, flush = i == sentence.index)
        }
        _state.update {
            it.copy(
                status = PlaybackStatus.PLAYING, sentenceIndex = sentence.index,
                resumeOffset = sentence.rangeStart + base,
                granularity = HighlightGranularity.SENTENCE, activeTokenId = null, errorCode = null,
            )
        }
    }

    override suspend fun pause() = withContext(dispatcher) {
        if (_state.value.status != PlaybackStatus.PLAYING) return@withContext
        generation++
        engine.stop()
        focus.abandon()
        _state.update { it.copy(status = PlaybackStatus.PAUSED) }
    }

    override suspend fun stop() = pause()

    override suspend fun seekToSentence(index: Int) = withContext(dispatcher) {
        val p = passage ?: return@withContext
        val sentence = p.sentences[index.coerceIn(0, p.sentences.lastIndex)]
        reposition(sentence.rangeStart)
    }

    override suspend fun seekToOffset(charOffset: Int) = withContext(dispatcher) {
        val p = passage ?: return@withContext
        val offset = charOffset.coerceIn(0, p.text.length)
        reposition(OffsetIndex.tokenAt(p.tokens, offset)?.rangeStart ?: offset)
    }

    override suspend fun seekTo(milliseconds: Long): Boolean = false

    override suspend fun setSpeed(speed: Float) = withContext(dispatcher) {
        if (!engine.supportsSpeed) return@withContext
        val clamped = speed.coerceIn(UserPreferences.MIN_SPEECH_RATE, UserPreferences.MAX_SPEECH_RATE)
        if (clamped == _state.value.speed) return@withContext
        _state.update { it.copy(speed = clamped) }
        if (_state.value.isPlaying) reposition(_state.value.resumeOffset)
    }

    override suspend fun speakSnippet(text: String, languageTag: String) = withContext(dispatcher) {
        pause()
        if (!_state.value.isAudioAvailable) return@withContext
        engine.enqueue("s|${++generation}", text, _state.value.speed, flush = true)
    }

    private suspend fun reposition(offset: Int) {
        val p = passage ?: return
        val wasPlaying = _state.value.isPlaying
        generation++
        engine.stop()
        val sentence = sentenceFor(p, offset)
        _state.update {
            it.copy(
                status = if (wasPlaying) PlaybackStatus.PAUSED else it.status.takeIf { s -> s != PlaybackStatus.COMPLETED } ?: PlaybackStatus.PAUSED,
                sentenceIndex = sentence.index, resumeOffset = offset, activeTokenId = null,
                granularity = if (wasPlaying) HighlightGranularity.SENTENCE else HighlightGranularity.NONE,
            )
        }
        if (wasPlaying) {
            focus.abandon()
            play()
        }
    }

    private fun onEvent(event: SpeechEvent) {
        val parsed = parseUtterance(event.utteranceId) ?: return
        if (parsed.generation != generation) return
        val p = passage ?: return
        val sentence = p.sentences.getOrNull(parsed.sentenceIndex) ?: return

        when (event) {
            is SpeechEvent.Started -> {
                currentSentenceReliable = true
                lastRangeStart = -1
                _state.update {
                    it.copy(
                        sentenceIndex = sentence.index, activeTokenId = null,
                        granularity = HighlightGranularity.SENTENCE,
                        resumeOffset = sentence.rangeStart + parsed.base,
                    )
                }
            }
            is SpeechEvent.WordRange -> {
                if (!currentSentenceReliable) return
                val utteranceLength = sentence.text.length - parsed.base
                val valid = event.start >= 0 && event.end > event.start && event.end <= utteranceLength &&
                    event.start >= lastRangeStart
                if (!valid) {
                    currentSentenceReliable = false
                    _state.update { it.copy(activeTokenId = null, granularity = HighlightGranularity.SENTENCE) }
                    return
                }
                lastRangeStart = event.start
                val absStart = sentence.rangeStart + parsed.base + event.start
                val absEnd = sentence.rangeStart + parsed.base + event.end
                val token = OffsetIndex.firstTokenIn(p.tokens, absStart, absEnd)
                    ?.takeIf { it.sentenceId == sentence.id } ?: return
                _state.update {
                    it.copy(
                        activeTokenId = token.id, granularity = HighlightGranularity.WORD,
                        resumeOffset = token.rangeStart, wordEventsObserved = true, sentenceIndex = sentence.index,
                    )
                }
            }
            is SpeechEvent.Done -> {
                if (sentence.index == p.sentences.lastIndex) {
                    focus.abandon()
                    _state.update {
                        it.copy(status = PlaybackStatus.COMPLETED, activeTokenId = null, granularity = HighlightGranularity.NONE, resumeOffset = 0)
                    }
                } else {
                    _state.update { it.copy(resumeOffset = p.sentences[sentence.index + 1].rangeStart) }
                }
            }
            is SpeechEvent.Stopped -> Unit
            is SpeechEvent.Error -> {
                generation++
                engine.stop()
                focus.abandon()
                _state.update { it.copy(status = PlaybackStatus.ERROR, errorCode = event.code) }
            }
        }
    }

    private fun sentenceFor(passage: Passage, offset: Int) =
        OffsetIndex.sentenceAt(passage.sentences, offset)
            ?: passage.sentences.lastOrNull { it.rangeStart <= offset }
            ?: passage.sentences.first()

    private data class ParsedUtterance(val generation: Int, val sentenceIndex: Int, val base: Int)

    private fun utteranceId(gen: Int, sentence: Int, base: Int) = "u|$gen|$sentence|$base"

    private fun parseUtterance(id: String): ParsedUtterance? {
        val parts = id.split('|')
        if (parts.size != 4 || parts[0] != "u") return null
        return ParsedUtterance(parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: return null)
    }
}
