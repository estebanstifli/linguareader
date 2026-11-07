package com.linguareader.core.reader

import com.linguareader.core.alignment.AlignmentQuality
import com.linguareader.core.alignment.AlignmentTimeline
import com.linguareader.core.alignment.AlignmentValidator
import com.linguareader.core.alignment.ClipAligner
import com.linguareader.core.alignment.GapFiller
import com.linguareader.core.alignment.MarkTimings
import com.linguareader.core.audio.AlignmentSidecar
import com.linguareader.core.audio.CachingSpeechSynthesizer
import com.linguareader.core.audio.SidecarData
import com.linguareader.core.audio.SidecarEntry
import com.linguareader.core.audio.SpokenMarksSidecar
import com.linguareader.core.content.Hashing
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.TimedToken
import com.linguareader.core.model.Passage
import com.linguareader.core.model.Sentence
import com.linguareader.core.model.Token
import com.linguareader.core.text.speech.IdentityNormalizer
import com.linguareader.core.text.speech.SpeechTextNormalizer
import com.linguareader.core.text.speech.SpokenText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/** Tiempos por palabra de una frase ya alineada, válidos solo para el clip [path]. */
class ClipTiming(val path: String, val timeline: AlignmentTimeline, val tokens: Map<String, Token>)

/**
 * Las dos etapas que convierten una frase en audio listo para sonar con sus palabras, **persistidas en la caché
 * de disco**: texto → WAV ([synthesize]) y WAV → tiempos por palabra ([align]). Las comparten el lector
 * ([RenderedReaderAudioController], que las encadena mientras suena) y la generación de libros enteros en segundo
 * plano (`BookAudioService`), de modo que lo que genera una la encuentra la otra en la caché sin repetir nada.
 *
 * La alineación se serializa con [alignMutex]: el modelo ONNX es pesado y una sola inferencia a la vez basta.
 */
class SentenceClipPipeline(
    private val synthesizer: SpeechSynthesizer,
    /** Parámetros del motor que cambian el audio (p. ej. pasos de difusión); van a la clave de caché. */
    private val parameters: () -> Map<String, String> = { emptyMap() },
    private val aligner: ClipAligner? = null,
    /** Texto mostrado → texto hablado (números, abreviaturas...). El audio y la alineación usan la forma hablada. */
    private val normalizer: SpeechTextNormalizer = IdentityNormalizer,
    private val alignDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Sistema de ficheros de la caché de audio (inyectable en pruebas). */
    val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val alignMutex = Mutex()

    val engineId: String get() = synthesizer.id

    /** true si el motor o el alineador pueden dar tiempos por palabra para [languageTag]. */
    fun providesWordTimings(languageTag: String): Boolean =
        synthesizer.info.providesWordTimings || aligner?.isAvailable(languageTag) == true

    fun request(text: String, lang: String, voice: String?): SpeechRequest =
        SpeechRequest(text = text, languageTag = lang, voiceId = voice, speed = 1.0f, parameters = parameters())

    /** Petición de síntesis de una frase tal como la haría [synthesize] (misma clave de caché). */
    fun requestFor(p: Passage, sentence: Sentence, lang: String, voice: String?): SpeechRequest =
        request(spokenOf(p, sentence, lang).spoken, lang, voice)

    /** Clave en la caché de disco del audio de una frase, si el motor cachea (null si no). */
    fun cacheKeyFor(p: Passage, sentence: Sentence, lang: String, voice: String?): String? =
        (synthesizer as? CachingSpeechSynthesizer)?.keyFor(requestFor(p, sentence, lang, voice))

    /**
     * Forma hablada de una frase. Si el normalizador falla se usa el texto tal cual (nunca bloquea la lectura).
     * La clave de caché del audio incluye el texto hablado, así que un cambio de reglas regenera solo lo que cambia.
     */
    fun spokenOf(p: Passage, sentence: Sentence, lang: String): SpokenText =
        try {
            normalizer.normalize(p, sentence, lang)
        } catch (e: Exception) {
            IdentityNormalizer.normalize(p, sentence, lang)
        }

    /** Etapa 1: texto → WAV en la caché de disco (acierto de caché = instantáneo). Nunca lanza salvo cancelación. */
    suspend fun synthesize(p: Passage, sentence: Sentence, lang: String, voice: String?): SpeechResult = try {
        synthesizer.synthesize(requestFor(p, sentence, lang, voice))
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        SpeechResult.Failure(EngineUnavailableReason.OUT_OF_MEMORY, e.message ?: "OOM")
    } catch (e: Exception) {
        SpeechResult.Failure(EngineUnavailableReason.SYNTHESIS_ERROR, e.message ?: (e::class.simpleName ?: "error"))
    }

    /** Fragmento suelto (audio de palabra): sin pasaje ni frase, con la forma hablada del texto. */
    suspend fun synthesizeSnippet(text: String, lang: String, voice: String?): SpeechResult? {
        val spoken = runCatching { normalizer.normalizeText(text, lang) }.getOrDefault(text)
        return runCatching { synthesizer.synthesize(request(spoken, lang, voice)) }.getOrNull()
    }

    /**
     * Etapa 2: WAV de disco → tiempos por palabra, persistidos junto al WAV ([AlignmentSidecar]); si ya hay una
     * alineación válida para ese audio y ese modelo se reutiliza sin recalcular. Devuelve null si no hay alineador
     * o la alineación no es fiable (esa frase se resaltará entera). Los huecos (números, símbolos, palabras sin
     * señal) se reparten entre sus vecinas ([GapFiller]).
     */
    suspend fun align(p: Passage, index: Int, path: String, lang: String): ClipTiming? {
        val sentence = p.sentences[index]
        val tokens = p.tokensOf(sentence)
        if (tokens.isEmpty()) return null
        // Las marcas del motor no requieren otra inferencia; sus límites siguen siendo estimaciones acústicas.
        timingFromMarks(p, sentence, tokens, path, lang)?.let { return it }
        val al = aligner ?: return null
        // Lo que el alineador busca en el audio es lo que se pronuncia: «cuarenta y dos» para el token «42».
        val words = spokenOf(p, sentence, lang).tokenSpoken?.takeIf { it.size == tokens.size } ?: return null
        val modelSignature = al.signature(lang) ?: return null
        // Si el habla difiere del texto, el reparto palabra → token forma parte de la alineación persistida.
        val signature = if (words == tokens.map { it.text }) modelSignature
            else "$modelSignature|spoken=${Hashing.sha256Hex(words.joinToString("|")).take(16)}"
        val wav = path.toPath()
        val wavBytes = fileSystem.metadataOrNull(wav)?.size ?: 0L

        AlignmentSidecar.read(fileSystem, wav, signature)?.let { saved ->
            if (!saved.ok) return null
            if (saved.entries.all { it.i in tokens.indices }) return timingOf(path, tokens, saved.entries.map { tokens[it.i].id to it })
        }

        val spans = alignMutex.withLock {
            withContext(alignDispatcher) {
                try {
                    al.align(path, words, lang)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // fallo del modelo (audio más corto que el texto…): «sin señal», pero no se persiste
                }
            }
        }
        val real = spans.orEmpty().filter { it.wordIndex in tokens.indices }
            .map { TimedToken(tokens[it.wordIndex].id, it.startMs, it.endMs, it.confidence) }
        // Los tiempos del modelo deben ser monótonos y fiables; si no, la frase no se alinea (y se recuerda).
        val reliable = real.isEmpty() ||
            AlignmentValidator.validate(tokens, real, minCoverage = 0.0, minMeanConfidence = MIN_CONFIDENCE) is AlignmentQuality.WordLevel
        val duration = GapFiller.wavDurationMs(wav, fileSystem) ?: real.maxOfOrNull { it.endMs }?.plus(GapFiller.EDGE_MARGIN_MS)
        if (!reliable || duration == null) {
            if (spans != null) AlignmentSidecar.write(fileSystem, wav, SidecarData(signature, wavBytes, ok = false))
            return null
        }
        val timed = GapFiller.fill(tokens, real, duration)
        if (spans != null) {
            val entries = timed.mapIndexed { i, t -> SidecarEntry(i, t.startMs, t.endMs, t.confidence, t.estimated) }
            AlignmentSidecar.write(fileSystem, wav, SidecarData(signature, wavBytes, ok = true, entries = entries))
        }
        return timingOf(path, tokens, timed.map { it.tokenId to SidecarEntry(0, it.startMs, it.endMs, it.confidence, it.estimated) })
    }

    /** Tiempos por palabra a partir de las marcas que el motor guardó junto al clip; null si no hay. */
    private fun timingFromMarks(p: Passage, sentence: Sentence, tokens: List<Token>, path: String, lang: String): ClipTiming? {
        val wav = path.toPath()
        val marks = SpokenMarksSidecar.read(fileSystem, wav)?.takeIf { it.isNotEmpty() } ?: return null
        val duration = GapFiller.wavDurationMs(wav, fileSystem) ?: return null
        val spoken = spokenOf(p, sentence, lang)
        val words = spoken.tokenSpoken?.takeIf { it.size == tokens.size } ?: tokens.map { it.text }
        val real = MarkTimings.toTimed(tokens, MarkTimings.spokenSpans(spoken.spoken, words), marks, duration)
        if (real.isEmpty()) return null
        if (marks.any { it.endMs != null }) {
            // Los tiempos internos tienen ambos límites: conservar pausas y omisiones, sin repartir huecos.
            if (AlignmentValidator.validate(tokens, real, minCoverage = 0.9, minMeanConfidence = 0f) !is AlignmentQuality.WordLevel) return null
            return timingOf(path, tokens, real.map { it.tokenId to SidecarEntry(0, it.startMs, it.endMs, it.confidence) }, strictIntervals = true)
        }
        val timed = GapFiller.fill(tokens, real, duration)
        return timingOf(path, tokens, timed.map { it.tokenId to SidecarEntry(0, it.startMs, it.endMs, it.confidence, it.estimated) })
    }

    private fun timingOf(path: String, tokens: List<Token>, items: List<Pair<String, SidecarEntry>>, strictIntervals: Boolean = false): ClipTiming {
        val timed = items.map { (id, e) -> TimedToken(id, e.s, e.e, e.c, e.est) }
        return ClipTiming(path, AlignmentTimeline(timed, holdInGaps = !strictIntervals), tokens.associateBy { it.id })
    }

    /** Libera el modelo de alineación (al salir del lector); se recarga al volver a alinear. */
    fun releaseAligner() = aligner?.release()

    private companion object {
        const val MIN_CONFIDENCE = 0.3f
    }
}
