package com.linguareader.shared

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLog

/**
 * Registro de diagnóstico que la app muestra en pantalla (Ajustes › Acerca de › Diagnostics, y desde los ajustes de
 * lectura): las mismas líneas que van a `NSLog` (motores de voz, caché de audio, descargas, estado de reproducción),
 * legibles en una sesión de Device Farm sin descargar ni filtrar el log del dispositivo. Guarda las últimas
 * [MAX_LINES] líneas; cualquier hilo puede escribir.
 */
object Diagnostics {
    private const val MAX_LINES = 400
    private val lock = SynchronizedObject()
    private val lines = ArrayDeque<String>()
    private val formatter = NSDateFormatter().apply { dateFormat = "HH:mm:ss.SSS" }
    private val _text = MutableStateFlow("")

    /** Todas las líneas guardadas, separadas por salto de línea (vacío si no hay ninguna). */
    val text: StateFlow<String> = _text

    fun log(tag: String, message: String) {
        // Sin argumentos variádicos: Kotlin/Native pasa las cadenas Kotlin tal cual a las funciones variádicas de C
        // (no las convierte a NSString) y `NSLog("%@", kotlinString)` cierra la app con SIGSEGV.
        NSLog("$tag: $message".replace("%", "%%"))
        val line = "${formatter.stringFromDate(NSDate())} $tag: $message"
        _text.value = synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            lines.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        _text.value = ""
    }
}
