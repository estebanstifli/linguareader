package com.linguareader.data.content

import android.content.Context
import android.net.Uri
import android.util.Log
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.Book
import com.linguareader.engine.importing.PdfWordExtractor
import com.linguareader.importer.layout.TokenBox
import com.linguareader.importer.layout.WordBoxAligner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Posición de cada token del libro sobre las páginas del PDF original. */
class PdfTextLayout(
    /** Cajas por id de token, en orden de lectura. */
    val tokens: Map<String, List<TokenBox>>,
) {
    /** Tokens de cada página, en orden de lectura (para tocar y para saber dónde empieza la página). */
    val byPage: Map<Int, List<Pair<String, TokenBox>>> =
        tokens.entries.flatMap { (id, boxes) -> boxes.map { it.page to (id to it) } }.groupBy({ it.first }, { it.second })

    /** Token bajo el punto ([x], [y] en fracciones de la página), con un pequeño margen para dedos. */
    fun hit(page: Int, x: Float, y: Float, slopX: Float = 0.01f, slopY: Float = 0.004f): String? {
        val boxes = byPage[page] ?: return null
        boxes.firstOrNull { (_, b) -> x in b.left..b.right && y in b.top..b.bottom }?.let { return it.first }
        return boxes
            .filter { (_, b) -> x in (b.left - slopX)..(b.right + slopX) && y in (b.top - slopY)..(b.bottom + slopY) }
            .minByOrNull { (_, b) -> kotlin.math.abs((b.left + b.right) / 2 - x) }?.first
    }

    fun firstTokenOn(page: Int): String? = byPage[page]?.firstOrNull()?.first

    /** Fracción de tokens del libro con caja (calidad de la correspondencia). */
    fun coverage(book: Book): Float = tokens.size.toFloat() / book.passages.sumOf { it.tokens.size }.coerceAtLeast(1)
}

sealed interface PdfLayoutState {
    data object Idle : PdfLayoutState
    /** [progress] 0..1 (páginas analizadas). */
    data class Computing(val progress: Float) : PdfLayoutState
    data class Ready(val layout: PdfTextLayout) : PdfLayoutState
    data class Failed(val message: String) : PdfLayoutState
}

/**
 * Calcula, guarda y sirve el [PdfTextLayout] de los PDF importados. El cálculo (PdfBox sobre el
 * original y alineación con los tokens del libro) se hace una vez, en segundo plano —justo tras
 * importar, o al abrir el original si aún no existe— y se guarda en la carpeta del libro, que se
 * reemplaza al reimportar (así nunca queda desfasado).
 */
class PdfTextLayouts(
    private val context: Context,
    private val imported: ImportedBookRepository,
    private val books: BookRepository,
    private val scope: CoroutineScope,
) {
    private val states = MutableStateFlow<Map<String, PdfLayoutState>>(emptyMap())
    private val jobs = HashMap<String, Job>()

    fun state(bookId: String): Flow<PdfLayoutState> = states.map { it[bookId] ?: PdfLayoutState.Idle }

    /** El libro se reimportó: lo calculado antes ya no vale. */
    @Synchronized
    fun invalidate(bookId: String) {
        jobs.remove(bookId)?.cancel()
        states.update { it - bookId }
    }

    /** Carga el diseño guardado o empieza a calcularlo (si no está ya en curso). */
    @Synchronized
    fun ensure(bookId: String) {
        val current = states.value[bookId]
        if (current is PdfLayoutState.Ready || jobs[bookId]?.isActive == true) return
        jobs[bookId] = scope.launch(Dispatchers.Default) {
            val result = runCatching { load(bookId) ?: compute(bookId) }
                .onFailure { Log.w(TAG, "Sin posiciones de palabras para $bookId: ${it.message}") }
            states.update { all ->
                all + (bookId to result.fold({ PdfLayoutState.Ready(it) }, { PdfLayoutState.Failed(it.message ?: "Error") }))
            }
        }
    }

    private suspend fun load(bookId: String): PdfTextLayout? {
        val file = imported.bookFile(bookId, FILE).toFile().takeIf { it.isFile } ?: return null
        val book = books.loadBook(bookId)
        return runCatching { read(file, signature(book)) }.getOrNull()
    }

    private suspend fun compute(bookId: String): PdfTextLayout {
        val original = imported.original(bookId) ?: error("El libro no conserva el PDF original")
        val book = books.loadBook(bookId)
        states.update { it + (bookId to PdfLayoutState.Computing(0f)) }
        val words = context.contentResolver.openInputStream(Uri.parse(original.uri))?.use { input ->
            PdfWordExtractor(context).extract(input) { done, total ->
                states.update { it + (bookId to PdfLayoutState.Computing(done.toFloat() / total.coerceAtLeast(1))) }
            }
        } ?: error("No se puede leer el PDF original")
        val layout = PdfTextLayout(WordBoxAligner.align(book, words))
        Log.i(TAG, "$bookId: ${words.size} palabras en el PDF, ${(layout.coverage(book) * 100).toInt()} % de los tokens con caja")
        runCatching { write(imported.bookFile(bookId, FILE).toFile(), signature(book), layout) }
        return layout
    }

    /** Cambia si cambia el texto del libro: un diseño guardado de otra versión no se usa. */
    private fun signature(book: Book): Long =
        book.passages.fold(book.passages.size.toLong()) { acc, p -> acc * 31 + p.text.hashCode() }

    private fun write(file: File, signature: Long, layout: PdfTextLayout) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(VERSION)
            out.writeLong(signature)
            out.writeInt(layout.tokens.size)
            for ((id, boxes) in layout.tokens) {
                out.writeUTF(id)
                out.writeByte(boxes.size)
                for (b in boxes) {
                    out.writeInt(b.page)
                    out.writeFloat(b.left); out.writeFloat(b.top); out.writeFloat(b.right); out.writeFloat(b.bottom)
                }
            }
        }
        tmp.renameTo(file)
    }

    private fun read(file: File, signature: Long): PdfTextLayout? = DataInputStream(file.inputStream().buffered()).use { input ->
        if (input.readInt() != VERSION || input.readLong() != signature) return null
        val count = input.readInt()
        val tokens = LinkedHashMap<String, List<TokenBox>>(count)
        repeat(count) {
            val id = input.readUTF()
            tokens[id] = List(input.readUnsignedByte()) {
                TokenBox(input.readInt(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
            }
        }
        PdfTextLayout(tokens)
    }

    private companion object {
        const val FILE = "pdf-words.bin"
        const val VERSION = 1
        const val TAG = "PdfTextLayouts"
    }
}
