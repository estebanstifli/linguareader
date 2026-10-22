package com.linguareader.engine.importing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.linguareader.importer.ExtractedImage
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Miniatura de la primera página de un PDF con el `PdfRenderer` del sistema (sin librerías ni peso
 * en la APK). Se hace una sola vez, al importar, mientras la copia temporal existe; el resultado se
 * guarda como portada del libro y no se vuelve a calcular.
 */
object PdfFirstPage {
    /** Ancho de la miniatura: el de la portada más ancha de la cuadrícula (≈ 160 dp a 3x). */
    private const val WIDTH_PX = 480
    /** Páginas desproporcionadas (tiras, pósteres): se recorta a una portada razonable. */
    private const val MAX_ASPECT = 2.0f
    private const val TAG = "PdfFirstPage"

    /** JPEG de la página 1, o null si el sistema no puede renderizar el PDF (cifrado, dañado...). */
    fun render(file: File): ExtractedImage? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val aspect = (page.height.toFloat() / page.width.coerceAtLeast(1)).coerceIn(0.3f, MAX_ASPECT)
                    val bitmap = Bitmap.createBitmap(WIDTH_PX, (WIDTH_PX * aspect).toInt(), Bitmap.Config.ARGB_8888)
                    // Las páginas sin fondo son transparentes: papel blanco, como se ven impresas.
                    bitmap.eraseColor(Color.WHITE)
                    val scale = WIDTH_PX.toFloat() / page.width.coerceAtLeast(1)
                    val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    bitmap.recycle()
                    ExtractedImage(out.toByteArray(), "image/jpeg")
                }
            }
        }
    } catch (e: Exception) {
        // SecurityException en PDF cifrados, IOException en dañados: el libro sigue sin portada.
        Log.w(TAG, "Sin miniatura de la primera página: ${e.message}")
        null
    }
}
