package com.linguareader.ui.catalog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/** Dónde abrir el selector de ficheros. */
enum class PickerStart { ANY, WHATSAPP, TELEGRAM }

/**
 * Selector de documentos del sistema más completo que el predeterminado: muestra el almacenamiento
 * interno (Android lo oculta en algunos móviles) y puede abrirse directamente en la carpeta donde
 * WhatsApp o Telegram guardan los documentos recibidos. Si la carpeta no existe, el sistema abre la
 * vista por defecto; no hace falta ningún permiso de almacenamiento.
 */
class ImportDocumentContract : ActivityResultContracts.OpenDocument() {

    var start: PickerStart = PickerStart.ANY

    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            // Clave no pública pero respetada por el selector de AOSP y de Google ("Mostrar almacenamiento interno").
            putExtra("android.provider.extra.SHOW_ADVANCED", true)
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            initialFolder(start)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    private fun initialFolder(start: PickerStart): Uri? {
        val path = when (start) {
            PickerStart.ANY -> return null
            // Android 11+: WhatsApp guarda en Android/media; antes, en la raíz del almacenamiento.
            PickerStart.WHATSAPP -> if (Build.VERSION.SDK_INT >= 30) "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"
            else "WhatsApp/Media/WhatsApp Documents"
            PickerStart.TELEGRAM -> "Download/Telegram"
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$path")
    }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }
}
