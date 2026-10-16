package com.linguareader.core.reader

import com.linguareader.core.alignment.AlignmentQuality
import com.linguareader.core.alignment.AlignmentTimeline
import com.linguareader.core.alignment.AlignmentValidator
import com.linguareader.core.alignment.ClipAligner
import com.linguareader.core.alignment.GapFiller
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.TimedToken
import com.linguareader.core.model.Passage
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.text.OffsetIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [ReaderAudioController] para motores que generan audio (Supertonic y futuros motores ONNX).
 *
 * - Productor/consumidor: la síntesis genera **un clip por frase**, en orden y sin esperar al
 *   reproductor, y los encola en [player] según van estando listos. La reproducción empieza en
 *   cuanto existe el primero y el resto del párrafo (pasaje) se sintetiza mientras suena.
 * - Las pausas entre frases y al final del párrafo ([pauses]) las inserta el reproductor como
 *   silencio; el audio generado viene recortado (sin silencios iniciales/finales del modelo).
 * - Al terminar el pasaje actual, precarga el siguiente ([prefetch]) en la caché.
 * - Resaltado: **por frase** (los límites son exactos: un clip = una frase). Si hay un [aligner]
 *   con modelo para el idioma, cada clip se alinea en segundo plano **después** de encolarse (no
 *   retrasa el audio); cuando sus tiempos están listos y pasan la validación, esa frase se resalta
 *   **por palabra** siguiendo la posición del reproductor. Sin alineación fiable → por frase.
 * - Pausa/reanudación exactas al milisegundo (el reproductor conserva la posición). Tras un
 *   salto o al reabrir, se reanuda desde el inicio de la frase.
 *
 * Todo el estado se muta en [dispatcher]; la síntesis ocurre en el hilo del propio motor.
 */
class RenderedReaderAudioController(
    private val synthesizer: SpeechSynthesizer,
    private val player: ClipPlayer,
    private val snippetPlayer: ClipPlayer,
    private val dispatcher: CoroutineDispatcher,
    /** Parámetros del motor que cambian el audio (p. ej. pasos de difusión); van a la clave de caché. */
    private val parameters: () -> Map<String, String> = { emptyMap() },
    private val pauses: () -> SpeechPauses = { SpeechPauses() },
    private val aligner: ClipAligner? = null,
    private val alignDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val tickMs: Long = 40,
    /** Reloj monótono en ms; inyectable para pruebas con tiempo virtual. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : ReaderAudioController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(
        PlaybackState(
            engineId = synthesizer.id,
            supportsSpeed = true,
            canSeekByTime = false,
            engineLacksWordTimings = !synthesizer.info.providesWordTimings,
        ),
    )
    override val playbackState: StateFlow<PlaybackState> = _state.asStateFlow()

    private var passage: Passage? = null
    private var languageTag = ""
    private var voiceId: String? = null
    private var generation = 0
    private var synthJob: Job? = null
    private var prefetchJob: Job? = null
    private var pendingPrefetch: Triple<Passage, String, String?>? = null

    /** Frases encoladas en el reproductor en esta generación: [queuedFrom, lastQueued]. */
    private var queuedFrom = -1
    private var lastQueued = -1

    /** Tiempos por palabra de cada frase ya alineada, válidos solo para el clip [ClipTiming.path]. */
    private class ClipTiming(val path: String, val timeline: AlignmentTimeline, val tokens: Map<String, com.linguareader.core.model.Token>)
    private val timings = HashMap<Int, ClipTiming>()
    private val clipPaths = HashMap<Int, String>()
    private val alignMutex = Mutex()
    private var loadGeneration = 0
    private var tickerJob: Job? = null

    // Interpolación de la posición del reproductor (ver tick()).
    private var anchorTag = -1
    private var anchorPositionMs = 0L
    private var anchorAtMs = 0L

    init {
        scope.launch { player.events.collect(::onPlayerEvent) }
    }

    override suspend fun load(passage: Passage, languageTag: String, voiceId: String?, startOffset: Int) =
        withContext(dispatcher) {
            invalidateQueue()
            prefetchJob?.cancel()
            pendingPrefetch = null
            timings.clear()
            loadGeneration++
            this@RenderedReaderAudioController.passage = passage
            this@RenderedReaderAudioController.languageTag = languageTag
            this@RenderedReaderAudioController.voiceId = voiceId
            val offset = startOffset.coerceIn(0, passage.text.length)
            val sentence = sentenceFor(passage, offset)
            _state.update {
                it.copy(
                    status = PlaybackStatus.PREPARING, passageId = passage.id, sentenceIndex = sentence.index,
                    activeTokenId = null, granularity = HighlightGranularity.NONE, resumeOffset = sentence.rangeStart,
                    errorCode = null, buffering = false, wordEventsObserved = false,
                )
            }
            val readiness = runCatching { synthesizer.availability(languageTag, voiceId) }
                .getOrElse { EngineReadiness.Unavailable(EngineUnavailableReason.INIT_FAILED, languageTag) }
            _state.update { it.copy(status = PlaybackStatus.READY, readiness = readiness) }
        }

    override suspend fun play() = withContext(dispatcher) {
        val p = passage ?: return@withContext
        val st = _state.value
        if (st.isPlaying || !st.isAudioAvailable) return@withContext
        val start = if (st.status == PlaybackStatus.COMPLETED) 0 else st.sentenceIndex
        if (st.status == PlaybackStatus.PAUSED && start in queuedFrom..lastQueued) {
            player.play()
            _state.update { it.copy(status = PlaybackStatus.PLAYING) }
            ensureTicker()
            return@withContext
        }
        startPipeline(p, start)
    }

    private fun startPipeline(p: Passage, from: Int) {
        invalidateQueue()
        val gen = generation
        queuedFrom = from
        val sentence = p.sentences[from]
        _state.update {
            it.copy(
                status = PlaybackStatus.PLAYING, sentenceIndex = from, resumeOffset = sentence.rangeStart,
                granularity = HighlightGranularity.SENTENCE, activeTokenId = null, buffering = true, errorCode = null,
            )
        }
        player.play()
        ensureTicker()
        prefetchJob?.cancel() // el pasaje que suena va primero; la precarga se reanuda al terminar su síntesis
        synthJob = scope.launch {
            for (i in from until p.sentences.size) {
                val result = try {
                    synthesizer.synthesize(request(p.sentences[i].text))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    SpeechResult.Failure(EngineUnavailableReason.OUT_OF_MEMORY, e.message ?: "OOM")
                } catch (e: Exception) {
                    SpeechResult.Failure(EngineUnavailableReason.SYNTHESIS_ERROR, e.message ?: e.javaClass.simpleName)
                }
                if (gen != generation || !isActive) return@launch
                when (result) {
                    is SpeechResult.Failure -> {
                        player.clear()
                        _state.update {
                            it.copy(
                                status = PlaybackStatus.ERROR, buffering = false,
                                readiness = if (result.reason == EngineUnavailableReason.SYNTHESIS_ERROR) it.readiness
                                else EngineReadiness.Unavailable(result.reason, languageTag),
                            )
                        }
                        return@launch
                    }
                    is SpeechResult.Success -> {
                        if (i > from) player.enqueueSilence(pauses().sentenceMs)
                        player.enqueue(i, result.audio.path)
                        clipPaths[i] = result.audio.path
                        requestAlignment(p, i, result.audio.path)
                        lastQueued = i
                        if (i == p.sentences.lastIndex) player.enqueueSilence(pauses().paragraphMs)
                        if (i == _state.value.sentenceIndex) _state.update { it.copy(buffering = false) }
                    }
                }
            }
            pendingPrefetch?.let { (np, lang, voice) -> runPrefetch(np, lang, voice) }
        }
    }

    override suspend fun pause() = withContext(dispatcher) {
        if (!_state.value.isPlaying) return@withContext
        player.pause()
        _state.update { it.copy(status = PlaybackStatus.PAUSED) }
    }

    override suspend fun stop() = withContext(dispatcher) {
        val wasActive = _state.value.isPlaying || _state.value.status == PlaybackStatus.PAUSED
        invalidateQueue()
        prefetchJob?.cancel()
        if (wasActive) _state.update { it.copy(status = PlaybackStatus.PAUSED, buffering = false) }
        aligner?.release()
        Unit
    }

    override suspend fun seekToSentence(index: Int) = withContext(dispatcher) {
        val p = passage ?: return@withContext
        val target = index.coerceIn(0, p.sentences.lastIndex)
        val st = _state.value
        val sentence = p.sentences[target]
        if (target in queuedFrom..lastQueued && player.seekToTag(target)) {
            _state.update {
                it.copy(
                    sentenceIndex = target, resumeOffset = sentence.rangeStart,
                    status = if (it.status == PlaybackStatus.COMPLETED) PlaybackStatus.PAUSED else it.status,
                )
            }
            return@withContext
        }
        if (st.isPlaying) {
            startPipeline(p, target)
        } else {
            invalidateQueue()
            _state.update {
                it.copy(
                    sentenceIndex = target, resumeOffset = sentence.rangeStart, buffering = false,
                    status = if (it.status == PlaybackStatus.COMPLETED || it.status == PlaybackStatus.PAUSED) PlaybackStatus.PAUSED else it.status,
                    granularity = if (it.status == PlaybackStatus.PAUSED) HighlightGranularity.SENTENCE else HighlightGranularity.NONE,
                )
            }
        }
    }

    /** Sin tiempos por palabra: «leer desde aquí» empieza en el inicio de la frase que contiene el offset. */
    override suspend fun seekToOffset(charOffset: Int) {
        val p = withContext(dispatcher) { passage } ?: return
        val offset = charOffset.coerceIn(0, p.text.length)
        seekToSentence((OffsetIndex.sentenceAt(p.sentences, offset) ?: sentenceFor(p, offset)).index)
    }

    override suspend fun seekTo(milliseconds: Long): Boolean = false

    override suspend fun setSpeed(speed: Float) = withContext(dispatcher) {
        val clamped = speed.coerceIn(UserPreferences.MIN_SPEECH_RATE, UserPreferences.MAX_SPEECH_RATE)
        if (clamped == _state.value.speed) return@withContext
        val p = passage
        val wasPlaying = _state.value.isPlaying
        _state.update { it.copy(speed = clamped) }
        // La velocidad cambia el audio generado (y la clave de caché): regenerar desde la frase actual.
        if (p != null && wasPlaying) startPipeline(p, _state.value.sentenceIndex) else invalidateQueue()
    }

    override suspend fun speakSnippet(text: String, languageTag: String) = withContext(dispatcher) {
        pause()
        if (!_state.value.isAudioAvailable) return@withContext
        scope.launch {
            val r = runCatching { synthesizer.synthesize(request(text, languageTag)) }.getOrNull()
            if (r is SpeechResult.Success) {
                snippetPlayer.clear()
                snippetPlayer.enqueue(0, r.audio.path)
                snippetPlayer.play()
            }
        }
        Unit
    }

    /**
     * La precarga del pasaje siguiente tiene **menos prioridad** que el actual: solo se lanza cuando el
     * productor del pasaje que suena ya terminó (así no compite por la CPU con sus frases) y se
     * cancela si hay que volver a sintetizar el actual. Si no se está reproduciendo, espera.
     */
    override suspend fun prefetch(passage: Passage, languageTag: String, voiceId: String?) = withContext(dispatcher) {
        pendingPrefetch = Triple(passage, languageTag, voiceId)
        if (synthJob?.isActive != true && _state.value.isPlaying) runPrefetch(passage, languageTag, voiceId)
    }

    /** Sintetiza (solo a la caché) las primeras frases antes de empezar; no usa el reproductor. */
    override suspend fun warmUp(passage: Passage, languageTag: String, voiceId: String?, sentenceCount: Int, fromSentence: Int): Boolean {
        for (s in passage.sentences.drop(fromSentence).take(sentenceCount)) {
            val result = try {
                synthesizer.synthesize(request(s.text, languageTag, voiceId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false
            }
            if (result is SpeechResult.Failure) return false
        }
        return true
    }

    private fun runPrefetch(p: Passage, lang: String, voice: String?) {
        val request = Triple(p, lang, voice)
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            for (s in p.sentences) {
                if (!isActive) return@launch
                runCatching { synthesizer.synthesize(request(s.text, lang, voice)) }
            }
            if (pendingPrefetch == request) pendingPrefetch = null
        }
    }

    private fun onPlayerEvent(event: ClipEvent) {
        val p = passage ?: return
        when (event) {
            is ClipEvent.Started -> {
                val sentence = p.sentences.getOrNull(event.tag) ?: return
                _state.update {
                    it.copy(
                        sentenceIndex = sentence.index, resumeOffset = sentence.rangeStart, activeTokenId = null,
                        granularity = HighlightGranularity.SENTENCE, buffering = false,
                    )
                }
                ensureTicker()
            }
            ClipEvent.QueueEnded -> {
                if (!_state.value.isPlaying) return
                if (lastQueued == p.sentences.lastIndex && synthJob?.isActive != true) {
                    _state.update {
                        it.copy(status = PlaybackStatus.COMPLETED, granularity = HighlightGranularity.NONE, buffering = false, resumeOffset = 0)
                    }
                } else {
                    // La síntesis va por detrás de la reproducción: esperar al siguiente clip.
                    val next = (lastQueued + 1).coerceAtMost(p.sentences.lastIndex)
                    _state.update { it.copy(buffering = true, sentenceIndex = next, resumeOffset = p.sentences[next].rangeStart) }
                }
            }
            is ClipEvent.Error -> _state.update { it.copy(status = PlaybackStatus.ERROR, buffering = false) }
        }
    }

    /** Alinea en segundo plano el clip ya encolado; la frase se resalta por palabra en cuanto estén los tiempos. */
    private fun requestAlignment(p: Passage, index: Int, path: String) {
        val al = aligner ?: return
        if (!al.isAvailable(languageTag) || timings[index]?.path == path) return
        val sentence = p.sentences[index]
        val tokens = p.tokensOf(sentence)
        if (tokens.isEmpty()) return
        val loadGen = loadGeneration
        val lang = languageTag
        scope.launch {
            val spans = alignMutex.withLock {
                if (loadGen != loadGeneration) return@withLock null
                withContext(alignDispatcher) {
                    // Un fallo del modelo (p. ej. audio más corto que el texto) cuenta como «sin señal».
                    runCatching { al.align(path, tokens.map { it.text }, lang) }.getOrNull().orEmpty()
                }
            } ?: return@launch
            if (loadGen != loadGeneration) return@launch
            val real = spans.filter { it.wordIndex in tokens.indices }
                .map { TimedToken(tokens[it.wordIndex].id, it.startMs, it.endMs, it.confidence) }
            // Los tiempos del modelo deben ser monótonos y fiables; los huecos (números, símbolos, palabras
            // sin señal) se reparten entre sus vecinas alineadas y se marcan como estimados.
            if (real.isNotEmpty() && AlignmentValidator.validate(tokens, real, minCoverage = 0.0, minMeanConfidence = MIN_CONFIDENCE) !is AlignmentQuality.WordLevel) return@launch
            val duration = GapFiller.wavDurationMs(java.io.File(path)) ?: real.maxOfOrNull { it.endMs }?.plus(GapFiller.EDGE_MARGIN_MS) ?: return@launch
            val timed = GapFiller.fill(tokens, real, duration)
            timings[index] = ClipTiming(path, AlignmentTimeline(timed), tokens.associateBy { it.id })
            ensureTicker()
        }
    }

    private fun ensureTicker() {
        if (aligner == null || tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            anchorTag = -1
            while (isActive && _state.value.isPlaying) {
                tick()
                delay(tickMs)
            }
        }
    }

    /**
     * Correlaciona la posición del reproductor con la palabra activa (búsqueda binaria).
     *
     * La posición que informa el reproductor avanza **a saltos** (≈ 250 ms en el emulador; depende de la
     * salida de audio del dispositivo): sin corregirlo, las palabras cortas caen entre dos saltos y nunca se
     * marcan. Se interpola con el reloj entre saltos, acotando la extrapolación ([MAX_EXTRAPOLATION_MS]),
     * y se re-ancla en cuanto el reproductor da una posición nueva (salto, cambio de frase, pausa).
     */
    private fun tick() {
        val raw = player.position() ?: return
        val now = clockMs()
        if (raw.tag != anchorTag || raw.positionMs != anchorPositionMs) {
            anchorTag = raw.tag
            anchorPositionMs = raw.positionMs
            anchorAtMs = now
        }
        val pos = ClipPosition(raw.tag, anchorPositionMs + (now - anchorAtMs).coerceIn(0L, MAX_EXTRAPOLATION_MS))
        val timing = timings[pos.tag]?.takeIf { it.path == clipPaths[pos.tag] } ?: return
        val token = timing.timeline.tokenAt(pos.positionMs)?.let { timing.tokens[it.tokenId] }
        _state.update {
            if (it.sentenceIndex != pos.tag || !it.isPlaying) it
            else if (token == null) it.copy(activeTokenId = null, granularity = HighlightGranularity.SENTENCE)
            else it.copy(activeTokenId = token.id, granularity = HighlightGranularity.WORD, resumeOffset = token.rangeStart)
        }
    }

    private fun invalidateQueue() {
        clipPaths.clear()
        generation++
        synthJob?.cancel()
        synthJob = null
        player.clear()
        queuedFrom = -1
        lastQueued = -1
    }

    private fun request(text: String, lang: String = languageTag, voice: String? = voiceId) =
        SpeechRequest(text = text, languageTag = lang, voiceId = voice, speed = _state.value.speed, parameters = parameters())

    private fun sentenceFor(passage: Passage, offset: Int) =
        OffsetIndex.sentenceAt(passage.sentences, offset)
            ?: passage.sentences.lastOrNull { it.rangeStart <= offset }
            ?: passage.sentences.first()

    private companion object {
        const val MIN_CONFIDENCE = 0.3f

        /** Máximo que se extrapola la posición entre dos actualizaciones del reproductor. */
        const val MAX_EXTRAPOLATION_MS = 350L
    }
}
