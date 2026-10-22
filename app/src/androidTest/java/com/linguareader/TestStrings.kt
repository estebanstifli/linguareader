package com.linguareader

import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Textos de la interfaz resueltos con los recursos de la app en el idioma del dispositivo: las pruebas no fijan un
 * idioma (la app sigue el del sistema; inglés base, español).
 */
fun str(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

/** Etiqueta «Página n de m» de la barra del lector (descripción accesible); sin [total], cualquier m. */
fun pageLabel(n: Int, total: Int? = null): SemanticsMatcher =
    if (total != null) {
        hasContentDescription(str(R.string.reader_page_of, n, total))
    } else {
        hasContentDescription(str(R.string.reader_page_of, n, 999_999).substringBefore("999999"), substring = true)
    }
