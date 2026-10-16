package com.linguareader.core.alignment

/** Intervalo de una palabra dentro de un clip de audio, en milisegundos desde el inicio del clip. */
data class WordSpanMs(val wordIndex: Int, val startMs: Long, val endMs: Long, val confidence: Float)

/**
 * Puerto de alineación forzada para clips ya sintetizados (un clip = una frase). Reemplazable por
 * otro modelo sin tocar el lector. Si no hay modelo para el idioma devuelve null: el lector se
 * queda en resaltado por frase y no inventa tiempos.
 */
interface ClipAligner {
    val id: String
    /** true si hay un modelo instalado y utilizable para [languageTag]. */
    fun isAvailable(languageTag: String): Boolean
    /** Alinea [words] (en orden) con el audio WAV de [clipPath]; el resultado omite las palabras sin letras alineables. */
    suspend fun align(clipPath: String, words: List<String>, languageTag: String): List<WordSpanMs>?
    /** Libera el modelo de memoria (se recarga bajo demanda). */
    fun release()
}
