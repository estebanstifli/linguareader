package com.linguareader.data.narration

import com.linguareader.core.model.Book
import com.linguareader.core.model.NarrationRef
import com.linguareader.core.model.RemoteFile
import com.linguareader.core.narration.AssKaraoke
import com.linguareader.core.narration.Narration
import com.linguareader.core.narration.NarrationAligner
import com.linguareader.core.platform.formatFixed
import com.linguareader.core.platform.ioDispatcher
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.supertonic.DownloadResult
import com.linguareader.engine.supertonic.HttpTransport
import com.linguareader.engine.supertonic.ModelDownloader
import com.linguareader.engine.supertonic.ModelFile
import com.linguareader.engine.supertonic.platformHttpTransport
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Narraciones pregrabadas (audio + ASS de karaoke) que declaran los libros de la biblioteca ([Book.narration]).
 *
 * - Se descargan en segundo plano al abrir el libro ([download]) a `<filesDir>/narrations/<bookId>/`, con
 *   reanudación y verificación de tamaño y sha256. Los nombres llevan el sha256, así que una versión nueva no se
 *   confunde con la anterior (que se borra al terminar la nueva).
 * - Cualquier fallo (sin red, URL caída, sha distinto, ASS que no casa con el texto) solo se anota en el log: el
 *   libro se sigue leyendo con el motor TTS, sin avisos. Un libro se intenta descargar como mucho una vez por sesión.
 * - Solo libros de la biblioteca: un EPUB importado por el usuario no hace que la app descargue de URL arbitrarias.
 */
class NarrationLibrary(
    private val fileSystem: FileSystem,
    filesDir: Path,
    private val appScope: CoroutineScope,
    private val transport: HttpTransport = platformHttpTransport(),
    private val log: (String) -> Unit = {},
) {
    private val root = filesDir / "narrations"
    private val mutex = Mutex()
    /** Narración ya alineada por libro y versión (null = no utilizable). */
    private val cache = HashMap<String, Narration?>()
    private val attemptedLock = SynchronizedObject()
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
                    val words = AssKaraoke.parse(fileSystem.read(timings) { readUtf8() })
                    val timeline = NarrationAligner.align(book, words)
                    log(
                        "Narración de ${book.id}: ${words.size} palabras, ${timeline.matchedTokens}/${timeline.totalTokens} " +
                            "tokens casados (${formatFixed(timeline.coverage * 100.0, 1)} %)",
                    )
                    if (timeline.coverage < MIN_COVERAGE) null
                    else Narration(book.id, "file://$audio", timeline)
                }.onFailure { log("Narración de ${book.id} no válida: ${it.message}") }.getOrNull()
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
        val attempt = "$bookId|${ref.audio.sha256}|${ref.timings.sha256}"
        if (!synchronized(attemptedLock) { attempted.add(attempt) }) return
        appScope.launch(ioDispatcher) {
            val dir = root / bookId
            val staging = dir / ".download"
            try {
                val urls = mapOf("audio" to ref.audio.url, "timings" to ref.timings.url)
                val files = listOf(ref.audio.toModelFile("audio"), ref.timings.toModelFile("timings"))
                log("Descargando la narración de $bookId (${files.sumOf { it.sizeBytes } / 1_000_000} MB)")
                val downloader = ModelDownloader("", files, transport, urlFor = { urls.getValue(it.path) }, fileSystem = fileSystem)
                when (val result = downloader.download(staging) {}) {
                    is DownloadResult.Failed -> {
                        // Los .part se conservan: el siguiente intento (otra sesión) reanuda.
                        log("Narración de $bookId no descargada: ${result.message}")
                        return@launch
                    }
                    DownloadResult.Completed -> Unit
                }
                val audio = target(bookId, "audio", ref.audio, "mp3")
                val timings = target(bookId, "timings", ref.timings, "ass")
                fileSystem.atomicMove(staging / "audio", audio)
                fileSystem.atomicMove(staging / "timings", timings)
                fileSystem.deleteRecursively(staging, mustExist = false)
                // Versiones anteriores de la narración de este libro.
                fileSystem.listOrNull(dir).orEmpty().filter { it != audio && it != timings }.forEach { fileSystem.deleteRecursively(it) }
                log("Narración de $bookId descargada")
                _ready.emit(bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Narración de $bookId no descargada: ${e.message}")
            }
        }
    }

    /** Borra la narración descargada de un libro (al borrar el libro). */
    suspend fun delete(bookId: String) = withContext(ioDispatcher) {
        fileSystem.deleteRecursively(root / bookId, mustExist = false)
        mutex.withLock { cache.keys.removeAll { it.startsWith("$bookId|") } }
        Unit
    }

    /** Narración del libro que se puede descargar y verificar, o null. */
    private fun usable(book: Book): NarrationRef? = book.narration?.takeIf { ref ->
        LibraryPackManager.isLibraryBook(book.id) && listOf(ref.audio, ref.timings).all { it.sha256 != null && it.sizeBytes != null }
    }

    private fun target(bookId: String, kind: String, file: RemoteFile, ext: String): Path =
        root / bookId / "$kind-${file.sha256!!.take(16)}.$ext"

    private fun isComplete(file: Path, remote: RemoteFile) =
        fileSystem.metadataOrNull(file)?.takeIf { it.isRegularFile }?.size == remote.sizeBytes

    private fun RemoteFile.toModelFile(path: String) = ModelFile(path, sizeBytes!!, sha256!!)

    private companion object {
        /** Con menos palabras del libro casadas con la narración, el resaltado no sería fiable: se usa el TTS. */
        const val MIN_COVERAGE = 0.9
    }
}
