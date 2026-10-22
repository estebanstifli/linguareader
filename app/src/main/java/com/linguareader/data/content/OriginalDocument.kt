package com.linguareader.data.content

/**
 * Referencia al fichero del que se importó un documento, para verlo con su propio formato.
 * No se guarda copia (ocuparía el doble): si el fichero se mueve o se pierde el permiso, el
 * visor lo dice y deja volver a localizarlo.
 */
data class OriginalDocument(
    /** `content://` (con permiso persistente si el proveedor lo concede) o `file://`. */
    val uri: String,
    /** Formato del extractor ("pdf"). */
    val format: String,
    val pageCount: Int? = null,
) {
    val viewable: Boolean get() = format in VIEWABLE_FORMATS

    companion object {
        /** Formatos con visor de su formato original. */
        val VIEWABLE_FORMATS = setOf("pdf")
    }
}
