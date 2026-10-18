package com.linguareader.engine.translation

import android.util.Log
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.translation.WordLookupService
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BookTranslationState {
    val target: String
    data class Preparing(override val target: String) : BookTranslationState
    data class Running(override val target: String, val done: Int, val total: Int) : BookTranslationState
    data class Failed(override val target: String, val message: String) : BookTranslationState
}

/**
 * Pretraduce un libro importado entero para leerlo después sin esperas ni red. Usa el mismo
 * camino que el lector ([WordLookupService.translateSentence]), así que lo traducido aquí queda en
 * la caché compartida. Es reanudable: el idioma pendiente se guarda en el libro y, al volver a
 * abrir la app, se continúa (las frases ya traducidas salen de la caché al instante).
 */
@Singleton
class BookTranslationService @Inject constructor(
    private val books: ImportedBookRepository,
    private val lookup: WordLookupService,
    private val models: TranslationModelManager,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<String, BookTranslationState>>(emptyMap())
    /** Libros en traducción (o con error). Los terminados desaparecen y quedan marcados en el libro. */
    val states: StateFlow<Map<String, BookTranslationState>> = _states
    private val jobs = HashMap<String, Job>()

    init {
        scope.launch {
            books.translationStatus.first().forEach { (id, status) -> status.pending?.let { start(id, it) } }
        }
    }

    @Synchronized
    fun start(bookId: String, target: String) {
        if (jobs[bookId]?.isActive == true) return
        _states.update { it + (bookId to BookTranslationState.Preparing(target)) }
        jobs[bookId] = scope.launch {
            books.setPendingTranslation(bookId, target)
            runCatching { translate(bookId, target) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Traducción de $bookId fallida", e)
                fail(bookId, target, e.message ?: "Error al traducir")
            }
        }
    }

    /**
     * Cancela y guarda hasta dónde llegó. Lo ya traducido queda en la caché, así que al continuar
     * solo se traduce lo que faltaba.
     */
    @Synchronized
    fun cancel(bookId: String) {
        val state = _states.value[bookId]
        jobs.remove(bookId)?.cancel()
        _states.update { it - bookId }
        scope.launch {
            when (state) {
                is BookTranslationState.Running -> books.pauseTranslation(bookId, state.target, state.done, state.total)
                is BookTranslationState.Preparing -> books.pauseTranslation(bookId, state.target, 0, 0)
                else -> books.setPendingTranslation(bookId, null)
            }
        }
    }

    private suspend fun translate(bookId: String, target: String) {
        val book = books.loadBook(bookId)
        val source = models.code(book.learningLanguage)
        val destination = models.code(target)
        if (source == null || destination == null) {
            return fail(bookId, target, "El traductor del dispositivo no admite este par de idiomas.")
        }
        for (code in setOf(source, destination)) {
            if (!models.awaitDownload(code)) {
                return fail(bookId, target, "No se pudo descargar el traductor (${code.uppercase()}). Revisa la conexión o la opción «solo Wi-Fi».")
            }
        }

        val sentences = book.passages.flatMap { passage -> passage.sentences.map { passage to it } }
        var done = 0
        var failures = 0
        _states.update { it + (bookId to BookTranslationState.Running(target, 0, sentences.size)) }
        for ((passage, sentence) in sentences) {
            val result = lookup.translateSentence(book, passage, sentence, target)
            currentCoroutineContext().ensureActive() // si se canceló, no volver a pintar el progreso
            if (result !is TranslationResult.Translated) failures++
            done++
            if (done % PROGRESS_STEP == 0 || done == sentences.size) {
                _states.update { it + (bookId to BookTranslationState.Running(target, done, sentences.size)) }
            }
        }
        if (failures * 10 > sentences.size) {
            return fail(bookId, target, "No se pudieron traducir $failures de ${sentences.size} frases. Se reintentarán al leer.")
        }
        books.markTranslated(bookId, target)
        synchronized(this) { jobs.remove(bookId) }
        _states.update { it - bookId }
    }

    private suspend fun fail(bookId: String, target: String, message: String) {
        books.setPendingTranslation(bookId, null)
        synchronized(this) { jobs.remove(bookId) }
        _states.update { it + (bookId to BookTranslationState.Failed(target, message)) }
    }

    fun dismissError(bookId: String) = _states.update { if (it[bookId] is BookTranslationState.Failed) it - bookId else it }

    private companion object {
        const val TAG = "BookTranslation"
        const val PROGRESS_STEP = 10
    }
}
