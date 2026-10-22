package com.linguareader.data.narration

import android.content.Context
import android.net.Uri
import android.util.Log
import com.linguareader.core.model.Book
import com.linguareader.core.model.NarrationRef
import com.linguareader.core.model.RemoteFile
import com.linguareader.core.narration.AssKaraoke
import com.linguareader.core.narration.Narration
import com.linguareader.core.narration.NarrationAligner
import com.linguareader.di.ApplicationScope
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.supertonic.DownloadResult
import com.linguareader.engine.supertonic.ModelDownloader
import com.linguareader.engine.supertonic.ModelFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narraciones pregrabadas (audio + ASS de karaoke) que declaran los libros de la biblioteca ([Book.narration]).
 *
 * - Se descargan en segundo plano al abrir el libro ([download]) a `filesDir/narrations/<bookId>/`, con reanudación y
 *   verificación de tamaño y sha256. Los nombres llevan el sha256, así que una versión nueva no se confunde con la
 *   anterior (que se borra al terminar la nueva).
 * - Cualquier fallo (sin red, URL caída, sha distinto, ASS que no casa con el texto) solo se anota en el log: el
 *   libro se sigue leyendo con el motor TTS, sin avisos. Un libro se intenta descargar como mucho una vez por sesión.
 * - Solo libros de la biblioteca: un EPUB importado por el usuario no hace que la app descargue de URL arbitrarias.
 */
@Singleton
class NarrationLibrary @Inject constructor(
    @param:ApplicationContext context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val root = File(context.filesDir, "narrations")
    private val mutex = Mutex()
    /** Narración ya alineada por libro y versión (null = no utilizable). */
    private val cache = HashMap<String, Narration?>()
    private val attempted = HashSet<String>()

    private val _ready = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Id del libro cuya narración acaba de quedar descargada. */
    val ready: SharedFlow<String> = _ready

    /** La narración del libro, si ya está descargada y casa con su texto; null en otro caso (se lee con el TTS). */
    suspend fun cached(book: Book): Narration? {
        val ref = usable(book) ?: return null
        val audio = target(book.id, "audio", ref.audio, "mp3")
        val timings = target(book.id, "timings", ref.timings, "ass")
        if (!isComplete(audio, ref.audio) || !isComplete(timings, ref.timings)) return null
        val key = "${book.id}|${audio.name}|${timings.name}"
        return mutex.withLock {
            if (key in cache) return@withLock cache[key]
            val narration = withContext(Dispatchers.Default) {
                runCatching {
                    val words = AssKaraoke.parse(timings.readText())
                    val timeline = NarrationAligner.align(book, words)
                    Log.i(
                        TAG, "Narración de ${book.id}: ${words.size} palabras, ${timeline.matchedTokens}/${timeline.totalTokens} " +
                            "tokens casados (${"%.1f".format(timeline.coverage * 100)} %)",
                    )
                    if (timeline.coverage < MIN_COVERAGE) null
                    else Narration(book.id, Uri.fromFile(audio).toString(), timeline)
                }.onFailure { Log.w(TAG, "Narración de ${book.id} no válida", it) }.getOrNull()
            }
            cache[key] = narration
            narration
        }
    }

    /** El libro declara una narración descargable que aún no está en el dispositivo. */
    fun needsDownload(book: Book): Boolean {
        val ref = usable(book) ?: return false
        return !isComplete(target(book.id, "audio", ref.audio, "mp3"), ref.audio) ||
            !isComplete(target(book.id, "timings", ref.timings, "ass"), ref.timings)
    }

    /** Descarga en segundo plano la narración de [book]; al terminar bien emite su id en [ready]. */
    fun download(book: Book) {
        val ref = usable(book) ?: return
        val bookId = book.id
        synchronized(attempted) { if (!attempted.add("$bookId|${ref.audio.sha256}|${ref.timings.sha256}")) return }
        appScope.launch(Dispatchers.IO) {
            val dir = File(root, bookId)
            val staging = File(dir, ".download")
            try {
                val urls = mapOf("audio" to ref.audio.url, "timings" to ref.timings.url)
                val files = listOf(ref.audio.toModelFile("audio"), ref.timings.toModelFile("timings"))
                Log.i(TAG, "Descargando la narración de $bookId (${files.sumOf { it.sizeBytes } / 1_000_000} MB)")
                when (val result = ModelDownloader("", files, urlFor = { urls.getValue(it.path) }).download(staging) {}) {
                    is DownloadResult.Failed -> {
                        // Los .part se conservan: el siguiente intento (otra sesión) reanuda.
                        Log.i(TAG, "Narración de $bookId no descargada: ${result.message}")
                        return@launch
                    }
                    DownloadResult.Completed -> Unit
                }
                val audio = target(bookId, "audio", ref.audio, "mp3")
                val timings = target(bookId, "timings", ref.timings, "ass")
                check(File(staging, "audio").renameTo(audio) && File(staging, "timings").renameTo(timings)) { "no se pudo mover" }
                staging.deleteRecursively()
                // Versiones anteriores de la narración de este libro.
                dir.listFiles()?.filter { it != audio && it != timings }?.forEach { it.deleteRecursively() }
                Log.i(TAG, "Narración de $bookId descargada")
                _ready.emit(bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Narración de $bookId no descargada", e)
            }
        }
    }

    /** Borra la narración descargada de un libro (al borrar el libro). */
    suspend fun delete(bookId: String) = withContext(Dispatchers.IO) {
        File(root, bookId).deleteRecursively()
        mutex.withLock { cache.keys.removeAll { it.startsWith("$bookId|") } }
        Unit
    }

    /** Narración del libro que se puede descargar y verificar, o null. */
    private fun usable(book: Book): NarrationRef? = book.narration?.takeIf { ref ->
        LibraryPackManager.isLibraryBook(book.id) && listOf(ref.audio, ref.timings).all { it.sha256 != null && it.sizeBytes != null }
    }

    private fun target(bookId: String, kind: String, file: RemoteFile, ext: String) =
        File(File(root, bookId), "$kind-${file.sha256!!.take(16)}.$ext")

    private fun isComplete(file: File, remote: RemoteFile) = file.isFile && file.length() == remote.sizeBytes

    private fun RemoteFile.toModelFile(path: String) = ModelFile(path, sizeBytes!!, sha256!!)

    private companion object {
        const val TAG = "Narration"

        /** Con menos palabras del libro casadas con la narración, el resaltado no sería fiable: se usa el TTS. */
        const val MIN_COVERAGE = 0.9
    }
}
