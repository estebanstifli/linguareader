package com.linguareader.data.content

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportRequest {
    data class Document(val uri: Uri) : ImportRequest
    data class WebPage(val url: String) : ImportRequest
    data class Text(val text: String, val title: String?) : ImportRequest
}

/**
 * Peticiones de importación que llegan desde fuera ("Abrir con…", "Compartir" desde el navegador
 * u otra app). La Activity las deposita y la pantalla de biblioteca las recoge.
 */
@Singleton
class PendingImports @Inject constructor() {
    private val _request = MutableStateFlow<ImportRequest?>(null)
    val request: StateFlow<ImportRequest?> = _request

    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Aviso para volver a la biblioteca aunque el usuario esté leyendo. */
    val arrivals: SharedFlow<Unit> = _arrivals

    fun offer(request: ImportRequest) {
        _request.value = request
        _arrivals.tryEmit(Unit)
    }

    fun consume(): ImportRequest? = _request.getAndUpdate { null }

    /** true si el intent era una importación (y se ha encolado). */
    fun offer(intent: Intent?): Boolean {
        val request = fromIntent(intent ?: return false) ?: return false
        offer(request)
        return true
    }

    companion object {
        private val URL = Regex("https?://\\S+")

        fun fromIntent(intent: Intent): ImportRequest? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") ImportRequest.WebPage(uri.toString()) else ImportRequest.Document(uri)
            }
            Intent.ACTION_SEND -> {
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                when {
                    stream != null -> ImportRequest.Document(stream)
                    text.isNullOrEmpty() -> null
                    // Un enlace compartido desde el navegador (a veces precedido del título de la página).
                    text.length < 500 && URL.containsMatchIn(text) -> ImportRequest.WebPage(URL.find(text)!!.value.trimEnd('.', ',', ')'))
                    else -> ImportRequest.Text(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
                }
            }
            else -> null
        }
    }
}
