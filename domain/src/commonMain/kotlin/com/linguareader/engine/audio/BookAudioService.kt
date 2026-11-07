package com.linguareader.engine.audio

import com.linguareader.core.audio.FileAudioCache
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.model.Book
import com.linguareader.core.reader.SentenceClipPipeline
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de la generación del audio de un libro entero (solo mientras hay trabajo o error; ver [BookAudioStatus]). */
sealed interface BookAudioState {
    val voice: String?
    data class Preparing(override val voice: String?) : BookAudioState
    /** [done] frases ya en disco (las que estaban en caché cuentan al instante); [waiting] = cediendo la CPU al lector. */
    data class Running(override val voice: String?, val done: Int, val total: Int, val waiting: Boolean = false) : BookAudioState
    data class Failed(override val voice: String?, val reason: EngineUnavailableReason, val detail: String) : BookAudioState
}

/**
 * Cuánto audio del libro hay ya en disco para la voz actual. Se calcula a partir de la caché (la caché es la
 * verdad: si Ajustes la vacía o se borra el audio del libro, aquí baja a cero sin estados que se desincronicen).
 */
data class BookAudioStatus(
    val ready: Int,
    val total: Int,
    /** Bytes del audio fijado del libro (todas las voces generadas). */
    val bytes: Long,
    /** El motor o el alineador dan tiempos por palabra para el idioma del libro. */
    val wordTimings: Boolean,
) {
    val complete: Boolean get() = total > 0 && ready >= total
}

/**
 * Genera en segundo plano el audio (y la alineación por palabra, si hay modelo) de un libro entero con un motor que
 * sintetiza a fichero (Supertonic), frase a frase y por el mismo camino que el lector ([SentenceClipPipeline]): lo
 * generado aquí lo encuentra el lector en la caché, y lo que el lector ya generó se salta al instante.
 *
 * - Los clips se **fijan** en la caché por libro ([FileAudioCache.pin]): no cuentan en el límite de la caché ni los
 *   expulsa el LRU; se borran con [delete] o al borrar el libro.
 * - Cede el paso al lector: mientras [busy] (hay lectura sonando) no sintetiza, para no competir por la CPU con la
 *   frase que suena.
 * - Cancelar ([cancel]) deja lo hecho; «continuar» es volver a [start]: las frases en caché pasan sin coste.
 * - **Un libro a la vez**: el motor tiene un solo hilo y dos libros se estorbarían; [start] detiene cualquier otra
 *   generación en marcha (la UI avisa antes). Lo generado del otro libro queda fijado y se continúa después.
 */
class BookAudioService(
    private val books: BookRepository,
    private val pipeline: SentenceClipPipeline,
    private val cache: FileAudioCache,
    private val scope: CoroutineScope,
    /** true mientras el lector reproduce (la generación espera). */
    private val busy: () -> Flow<Boolean> = { flowOf(false) },
    private val log: (String) -> Unit = {},
) {
    private val _states = MutableStateFlow<Map<String, BookAudioState>>(emptyMap())
    /** Libros en generación (o con error). Los terminados desaparecen: su estado se consulta con [status]. */
    val states: StateFlow<Map<String, BookAudioState>> = _states
    private val lock = SynchronizedObject()
    private val jobs = HashMap<String, Job>()

    /** Cambia cada vez que termina, se cancela o se borra algo: la UI vuelve a pedir [status]. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    fun isRunning(bookId: String): Boolean = synchronized(lock) { jobs[bookId]?.isActive == true }

    /** Libro cuyo audio se está generando ahora (preparando o en marcha), si hay alguno. */
    fun running(): String? = synchronized(lock) { jobs.entries.firstOrNull { it.value.isActive }?.key }

    /** Empieza (o continúa) la generación de [bookId], deteniendo la de cualquier otro libro. */
    fun start(bookId: String, voice: String?) = synchronized(lock) {
        if (jobs[bookId]?.isActive == true) return@synchronized
        val others = jobs.keys.filter { it != bookId }
        others.forEach { other -> jobs.remove(other)?.cancel() }
        _states.update { it - others.toSet() + (bookId to BookAudioState.Preparing(voice)) }
        jobs[bookId] = scope.launch {
            runCatching { generate(bookId, voice) }.onFailure { e ->
                if (e is CancellationException) throw e
                log("Audio de $bookId fallido: ${e.message}")
                fail(bookId, voice, EngineUnavailableReason.SYNTHESIS_ERROR, e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** Detiene la generación; lo ya generado queda fijado y se continúa con [start]. */
    fun cancel(bookId: String) {
        synchronized(lock) { jobs.remove(bookId)?.cancel() }
        _states.update { it - bookId }
        _revision.update { it + 1 }
    }

    fun dismissError(bookId: String) = _states.update { if (it[bookId] is BookAudioState.Failed) it - bookId else it }

    /** Cancela y borra del disco el audio generado del libro (todas las voces). Devuelve los bytes liberados. */
    fun delete(bookId: String): Long {
        cancel(bookId)
        return cache.deleteGroup(groupOf(bookId)).also { _revision.update { it + 1 } }
    }

    /** Cuánto hay en disco para [voice]; null si el libro no se puede cargar. */
    suspend fun status(bookId: String, voice: String?): BookAudioStatus? {
        val book = runCatching { books.loadBook(bookId) }.getOrNull() ?: return null
        return status(book, voice)
    }

    fun status(book: Book, voice: String?): BookAudioStatus {
        val lang = book.learningLanguage
        var total = 0
        var ready = 0
        for (p in book.passages) for (s in p.sentences) {
            total++
            val key = pipeline.cacheKeyFor(p, s, lang, voice) ?: continue
            if (cache.has(key)) ready++
        }
        return BookAudioStatus(ready, total, cache.pinnedBytes(groupOf(book.id)), pipeline.providesWordTimings(lang))
    }

    private suspend fun generate(bookId: String, voice: String?) {
        val book = books.loadBook(bookId)
        val lang = book.learningLanguage
        val group = groupOf(bookId)
        val sentences = book.passages.flatMap { p -> p.sentences.map { p to it } }
        var done = 0
        _states.update { it + (bookId to BookAudioState.Running(voice, 0, sentences.size)) }
        val pending = ArrayList<String>()
        fun flushPins() {
            if (pending.isNotEmpty()) {
                cache.pin(group, pending)
                pending.clear()
            }
        }
        try {
            for ((passage, sentence) in sentences) {
                // El lector tiene prioridad: con una lectura sonando no se le quita el hilo del motor.
                val busyFlow = busy()
                if (busyFlow.first()) {
                    _states.update { it + (bookId to BookAudioState.Running(voice, done, sentences.size, waiting = true)) }
                    busyFlow.first { !it }
                    _states.update { it + (bookId to BookAudioState.Running(voice, done, sentences.size)) }
                }
                when (val result = pipeline.synthesize(passage, sentence, lang, voice)) {
                    is SpeechResult.Failure -> {
                        flushPins()
                        return fail(bookId, voice, result.reason, result.message)
                    }
                    is SpeechResult.Success -> {
                        pipeline.align(passage, sentence.index, result.audio.path, lang)
                        pipeline.cacheKeyFor(passage, sentence, lang, voice)?.let(pending::add)
                    }
                }
                currentCoroutineContext().ensureActive() // si se canceló, no volver a pintar el progreso
                done++
                if (pending.size >= PIN_BATCH) flushPins()
                if (done % PROGRESS_STEP == 0 || done == sentences.size) {
                    _states.update { it + (bookId to BookAudioState.Running(voice, done, sentences.size)) }
                }
            }
        } finally {
            flushPins() // también al cancelar: lo generado queda protegido
        }
        synchronized(lock) { jobs.remove(bookId) }
        _states.update { it - bookId }
        _revision.update { it + 1 }
        log("Audio de $bookId completo: ${sentences.size} frases, ${cache.pinnedBytes(group) / (1024 * 1024)} MB")
    }

    private fun fail(bookId: String, voice: String?, reason: EngineUnavailableReason, detail: String) {
        synchronized(lock) { jobs.remove(bookId) }
        _states.update { it + (bookId to BookAudioState.Failed(voice, reason, detail)) }
        _revision.update { it + 1 }
    }

    companion object {
        fun groupOf(bookId: String) = "audiobook:$bookId"
        private const val PROGRESS_STEP = 5
        private const val PIN_BATCH = 20
    }
}
