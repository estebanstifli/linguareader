package com.linguareader.data.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import com.linguareader.core.content.Hashing
import com.linguareader.core.content.MediaResult
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.BookSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Miniaturas de portada del catálogo, calculadas una vez y guardadas en disco (`cacheDir/covers/<id>/`).
 *
 * - Con portada: se decodifica reducida y se guarda como JPEG pequeño. En los arranques siguientes
 *   se lee ese fichero (unos 30 KB) en lugar de leer, verificar y muestrear la imagen original.
 * - Documento del usuario sin portada (TXT, DOCX, RTF...): se dibuja una «primera página» con el
 *   título y el comienzo del texto, sin necesitar el fichero original.
 *
 * La clave incluye el hash de la portada (o el título y tamaño del texto), así que un cambio
 * genera una miniatura nueva sin invalidar nada a mano. Si el sistema vacía la caché, se regenera.
 */
class CoverThumbnails(
    private val context: Context,
    private val books: BookRepository,
) {
    private val dir: File get() = File(context.cacheDir, DIR)

    /** Pocas a la vez: es trabajo de fondo y no debe competir con la primera composición. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(2)

    /**
     * Miniatura de [summary] a [widthPx] de ancho como mucho, o null si no tiene portada y no
     * procede dibujar una página de texto ([textPage] false: libros de las bibliotecas graduadas).
     */
    suspend fun load(summary: BookSummary, widthPx: Int, textPage: Boolean): Bitmap? = withContext(dispatcher) {
        val cover = summary.cover
        val key = when {
            cover != null -> "c-" + (cover.sha256 ?: Hashing.sha256Hex(cover.path)).take(16)
            textPage -> "t-" + Hashing.sha256Hex("${summary.title}|${summary.wordCount}|$TEXT_PAGE_VERSION").take(16)
            else -> return@withContext null
        }
        val file = File(File(dir, summary.id), "$key-$widthPx.jpg")
        if (file.isFile) {
            BitmapFactory.decodeFile(file.path)?.let { return@withContext it }
        }
        val bitmap = runCatching {
            if (cover != null) decode(books.loadMedia(summary.id, cover), widthPx) else drawTextPage(summary, widthPx)
        }.onFailure { Log.w(TAG, "Sin miniatura para ${summary.id}: ${it.message}") }.getOrNull() ?: return@withContext null
        store(file, bitmap)
        bitmap
    }

    /** Borra la miniatura de un libro (al eliminarlo). */
    suspend fun forget(bookId: String) = withContext(Dispatchers.IO) {
        File(dir, bookId).deleteRecursively()
    }

    private fun decode(media: MediaResult, widthPx: Int): Bitmap? {
        val bytes = (media as? MediaResult.Ok)?.bytes ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (decoded.width <= widthPx) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, widthPx, decoded.height * widthPx / decoded.width, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Hoja 2:3 de papel con el título y las primeras líneas, como la primera página impresa del documento. */
    private suspend fun drawTextPage(summary: BookSummary, widthPx: Int): Bitmap {
        val book = books.loadBook(summary.id)
        val body = buildString {
            for (passage in book.passages) {
                if (isNotEmpty()) append("\n")
                append(passage.text)
                if (length >= TEXT_CHARS) break
            }
        }.take(TEXT_CHARS)
        val heightPx = widthPx * 3 / 2
        val margin = widthPx * 0.1f
        val inner = (widthPx - 2 * margin).toInt()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x20, 0x20, 0x20)
            textSize = widthPx * 0.062f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val title = StaticLayout.Builder.obtain(summary.title, 0, summary.title.length, titlePaint, inner)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(margin, margin)
        title.draw(canvas)
        canvas.restore()

        val bodyTop = margin + title.height + widthPx * 0.05f
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x55, 0x55, 0x55)
            textSize = widthPx * 0.036f
            typeface = Typeface.SERIF
        }
        val lineHeight = bodyPaint.fontSpacing * 1.15f
        val maxLines = ((heightPx - bodyTop - margin) / lineHeight).toInt().coerceAtLeast(1)
        val text = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, inner)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
            .build()
        canvas.save()
        canvas.translate(margin, bodyTop)
        text.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun store(file: File, bitmap: Bitmap) = runCatching {
        val folder = file.parentFile!!.apply { mkdirs() }
        // Una sola miniatura por libro: la anterior (portada cambiada, título editado) sobra.
        folder.listFiles { f -> f != file }?.forEach(File::delete)
        val tmp = File(folder, "${file.name}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        tmp.renameTo(file)
    }

    private companion object {
        const val DIR = "covers"
        const val TAG = "CoverThumbnails"
        /** Caracteres de texto suficientes para llenar la hoja. */
        const val TEXT_CHARS = 900
        /** Subir al cambiar el dibujo de la página de texto, para regenerar las ya guardadas. */
        const val TEXT_PAGE_VERSION = 1
    }
}
