package com.linguareader.core.engine

import com.linguareader.core.content.MediaResult
import com.linguareader.core.model.Book
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.Passage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

// ---------------------------------------------------------------------------------------------
// Contenido
// ---------------------------------------------------------------------------------------------

interface BookRepository {
    fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>>
    suspend fun loadBook(bookId: String): Book
    suspend fun loadPassage(bookId: String, passageId: String): Passage
    /** Lee y verifica (sha256) un recurso multimedia del libro. */
    suspend fun loadMedia(bookId: String, media: MediaRef): MediaResult
}

// ---------------------------------------------------------------------------------------------
// TTS
// ---------------------------------------------------------------------------------------------

data class VoiceInfo(
    val id: String,
    val languageTag: String,
    val displayName: String,
    val requiresNetwork: Boolean,
    val quality: Int?,
)

data class SpeechRequest(
    val text: String,
    val languageTag: String,
    val voiceId: String?,
    val speed: Float,
    /** Parámetros adicionales del motor (forman parte de la clave de caché). */
    val parameters: Map<String, String> = emptyMap(),
)

/** Audio sintetizado en disco. Su hash identifica el audio exacto para persistir alineaciones. */
data class AudioAsset(
    val path: String,
    val mimeType: String,
    val durationMs: Long?,
    val sha256: String?,
)

sealed interface SpeechResult {
    data class Success(val audio: AudioAsset, val engineVersion: String) : SpeechResult
    data class Failure(val reason: EngineUnavailableReason, val message: String) : SpeechResult
}

enum class EngineUnavailableReason {
    ENGINE_NOT_INSTALLED, LANGUAGE_NOT_SUPPORTED, LANGUAGE_DATA_MISSING, VOICE_NOT_FOUND, INIT_FAILED, SYNTHESIS_ERROR,
    /** Motor con modelo descargable que todavía no está instalado. */
    MODEL_NOT_INSTALLED,
    OUT_OF_MEMORY,
}

/** Descripción de un motor TTS, independiente del proveedor. */
data class TtsEngineInfo(
    val id: String,
    val displayName: String,
    /** true: reproduce él mismo (Android TTS). false: genera audio que reproduce la app. */
    val streaming: Boolean,
    /** true solo si el motor entrega tiempos por palabra fiables (eventos o marcas temporales). */
    val providesWordTimings: Boolean,
    val requiresModelDownload: Boolean,
    val offline: Boolean,
    /** Subetiquetas primarias soportadas; vacío = depende de las voces instaladas en el sistema. */
    val languages: Set<String> = emptySet(),
)

/** Base común de todos los motores TTS (en streaming o renderizados). */
interface TtsEngine {
    val id: String
    val info: TtsEngineInfo
    suspend fun listVoices(languageTag: String): List<VoiceInfo>
    /** Libera recursos pesados (sesiones ONNX, servicios). Puede volver a usarse después. */
    fun release() {}
}

/**
 * Síntesis a fichero (motores offline tipo Supertonic/ONNX). El audio resultante NO trae tiempos
 * por palabra salvo que el motor lo declare en [TtsEngineInfo.providesWordTimings]; la
 * alineación es responsabilidad de un [AlignmentEngine].
 */
interface SpeechSynthesizer : TtsEngine {
    suspend fun synthesize(request: SpeechRequest): SpeechResult
    /** Comprueba (sin sintetizar) si el motor puede hablar este idioma con esta voz. */
    suspend fun availability(languageTag: String, voiceId: String?): EngineReadiness = EngineReadiness.Ready
}

sealed interface EngineReadiness {
    data object Ready : EngineReadiness
    data class Unavailable(val reason: EngineUnavailableReason, val languageTag: String) : EngineReadiness
}

/** Eventos de un motor que reproduce directamente (Android TextToSpeech). Offsets relativos al enunciado. */
sealed interface SpeechEvent {
    val utteranceId: String
    data class Started(override val utteranceId: String) : SpeechEvent
    data class WordRange(override val utteranceId: String, val start: Int, val end: Int) : SpeechEvent
    data class Done(override val utteranceId: String) : SpeechEvent
    data class Stopped(override val utteranceId: String) : SpeechEvent
    data class Error(override val utteranceId: String, val code: Int) : SpeechEvent
}

/**
 * Motor TTS de reproducción directa con cola de enunciados. Puede emitir [SpeechEvent.WordRange]
 * (Android `onRangeStart`); si no los emite, el lector resalta por frase.
 */
interface StreamingSpeechEngine : TtsEngine {
    val events: SharedFlow<SpeechEvent>
    val supportsSpeed: Boolean
    suspend fun prepare(languageTag: String, voiceId: String?): EngineReadiness
    fun enqueue(utteranceId: String, text: String, speed: Float, flush: Boolean)
    /** Encola un silencio (pausa entre frases). Los motores que no lo soporten pueden ignorarlo. */
    fun enqueueSilence(utteranceId: String, durationMs: Long) {}
    fun stop()
}

// ---------------------------------------------------------------------------------------------
// Alineación
// ---------------------------------------------------------------------------------------------

data class TimedToken(
    val tokenId: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float? = null,
    /** true si el tiempo no viene del modelo sino de repartir el hueco entre palabras alineadas ([com.linguareader.core.alignment.GapFiller]). */
    val estimated: Boolean = false,
)

sealed interface AlignmentResult {
    data class Aligned(val engineId: String, val tokens: List<TimedToken>) : AlignmentResult
    /** No hay tiempos fiables: el lector debe resaltar por frase. */
    data class Unavailable(val reason: String) : AlignmentResult
}

interface AlignmentEngine {
    val id: String
    suspend fun align(passage: Passage, audio: AudioAsset, languageTag: String): AlignmentResult
}

/** Alineador nulo: nunca inventa tiempos. Sustituible por forced alignment en Fase 3. */
class UnavailableAlignmentEngine : AlignmentEngine {
    override val id = "none"
    override suspend fun align(passage: Passage, audio: AudioAsset, languageTag: String): AlignmentResult =
        AlignmentResult.Unavailable("No hay alineador configurado para audio sintetizado")
}

// ---------------------------------------------------------------------------------------------
// Traducción
// ---------------------------------------------------------------------------------------------

enum class TranslationUnit { WORD, EXPRESSION, SENTENCE }

/** Pistas opcionales para proveedores que conocen el contenido (glosario/traducciones incluidas). */
data class ContentRef(
    val bookId: String,
    val passageId: String? = null,
    val sentenceId: String? = null,
    val glossaryEntryId: String? = null,
)

data class TranslationRequest(
    val text: String,
    val contextBefore: String,
    val contextAfter: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val unit: TranslationUnit = TranslationUnit.WORD,
    val ref: ContentRef? = null,
)

data class TranslatedExample(val text: String, val translation: String?)

sealed interface TranslationResult {
    data class Translated(
        val text: String,
        val providerId: String,
        val lemma: String? = null,
        val partOfSpeech: String? = null,
        val examples: List<TranslatedExample> = emptyList(),
        /** true si la traducción depende del contexto (no es una entrada de diccionario aislada). */
        val contextual: Boolean = false,
    ) : TranslationResult

    data class Unavailable(val reason: Reason, val providerId: String) : TranslationResult

    enum class Reason { NO_ENTRY, SAME_LANGUAGE, LANGUAGE_PAIR_UNSUPPORTED, OFFLINE, ENGINE_ERROR }
}

interface TranslationEngine {
    val id: String
    suspend fun translate(request: TranslationRequest): TranslationResult
}
