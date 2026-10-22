package com.linguareader.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileNotFoundException

/**
 * Un PDF abierto con el `PdfRenderer` del sistema. PdfRenderer no es seguro entre hilos y solo
 * admite una página abierta a la vez, así que todo pasa por un único hilo; la interfaz nunca
 * espera a un renderizado.
 *
 * Las páginas ya dibujadas se guardan en una caché LRU acotada por memoria: volver a una página
 * cercana es instantáneo y la memoria no crece con la longitud del documento.
 */
class PdfDocumentSession private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Ancho/alto (en puntos) de cada página, leídos una vez al abrir. */
    val pageAspects: List<Float>,
) : Closeable {

    val pageCount: Int get() = pageAspects.size

    private class Rendered(val bitmap: Bitmap, val widthPx: Int)

    private val cache = object : LruCache<Int, Rendered>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Rendered): Int = value.bitmap.byteCount
    }

    @Volatile private var closed = false

    /** La mejor versión ya dibujada de la página, aunque sea de menos resolución (para mostrar algo al instante). */
    fun cached(index: Int): Bitmap? = cache.get(index)?.bitmap

    /**
     * Página [index] a [widthPx] de ancho (limitado a [MAX_PIXELS] en total para no agotar la memoria
     * con zoom fuerte). Si ya hay una versión igual o mayor en caché, se devuelve esa.
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        if (closed || index !in pageAspects.indices) return@withContext null
        val aspect = pageAspects[index]
        val maxWidth = kotlin.math.sqrt(MAX_PIXELS / aspect).toInt()
        val width = widthPx.coerceIn(1, maxWidth)
        cache.get(index)?.takeIf { it.widthPx >= width }?.let { return@withContext it.bitmap }
        val height = (width * aspect).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Papel blanco: muchas páginas no pintan fondo y saldrían transparentes.
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(index).use { page ->
            val matrix = Matrix().apply { setScale(width.toFloat() / page.width, height.toFloat() / page.height) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        cache.put(index, Rendered(bitmap, width))
        bitmap
    }

    override fun close() {
        closed = true
        // En el mismo hilo que los renderizados: no se cierra a mitad de una página.
        dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
            runCatching { renderer.close() }
            runCatching { fd.close() }
            cache.evictAll()
        }
    }

    /** El fichero no se puede leer: movido, borrado o permiso perdido. */
    class Unavailable(cause: Throwable) : Exception(cause.message, cause)

    /** El fichero se lee pero no es un PDF válido o está protegido con contraseña. */
    class Unreadable(cause: Throwable) : Exception(cause.message, cause)

    companion object {
        /** Unos 64 MB de páginas dibujadas como mucho (o 1/4 del heap si es menor). */
        private val CACHE_BYTES: Int = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4).toInt()
        /** Píxeles por página como máximo (≈ 24 MB en ARGB): con más zoom se amplía la imagen ya dibujada. */
        private const val MAX_PIXELS = 6_000_000f

        @OptIn(ExperimentalCoroutinesApi::class)
        private val dispatcher = Dispatchers.IO.limitedParallelism(1)

        suspend fun open(context: Context, uri: Uri): PdfDocumentSession = withContext(dispatcher) {
            val fd = try {
                context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
            } catch (e: FileNotFoundException) {
                throw Unavailable(e)
            } catch (e: SecurityException) {
                throw Unavailable(e)
            } catch (e: IllegalArgumentException) {
                throw Unavailable(e)
            }
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close()
                throw Unreadable(e)
            }
            val aspects = (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { page -> page.height.toFloat() / page.width.coerceAtLeast(1) }
            }
            PdfDocumentSession(fd, renderer, aspects)
        }
    }
}
