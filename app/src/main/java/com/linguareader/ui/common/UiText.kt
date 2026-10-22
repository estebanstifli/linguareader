package com.linguareader.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext

/**
 * Texto para la interfaz que se produce fuera de Compose (ViewModels, servicios): una referencia a
 * `strings.xml` que se resuelve al mostrarla, en el idioma actual de la app. [Raw] es solo para texto
 * que no se traduce (títulos de libros, detalles técnicos de un error).
 */
@Immutable
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    data class Raw(val text: String) : UiText

    fun asString(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.resolve(context))
        is Plural -> context.resources.getQuantityString(id, count, *args.resolve(context))
        is Raw -> text
    }

    @Composable
    fun asString(): String = asString(LocalContext.current)

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

/** Los argumentos que son [UiText] se resuelven también (p. ej. un motivo dentro de una frase). */
private fun List<Any>.resolve(context: Context): Array<Any> =
    map { if (it is UiText) it.asString(context) else it }.toTypedArray()
