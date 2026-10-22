package com.linguareader.engine.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Comprueba si un motor de texto a voz del sistema entrega tiempos por palabra (`onRangeStart`) al generar a
 * fichero: de eso depende que el lector resalte palabra a palabra con la voz del sistema. Usa una instancia propia
 * del motor pedido, sin tocar la que lee, y la cierra al terminar.
 */
class SystemTtsProbe(private val context: Context) {

    sealed interface Result {
        /** El motor sintetizó la frase; [marks] marcas de palabra recibidas (0 = resaltará por frase). */
        data class Ok(val marks: Int, val words: Int) : Result
        data object LanguageUnavailable : Result
        data object Failed : Result
    }

    suspend fun wordMarks(enginePackage: String, languageTag: String): Result {
        val tts = withContext(Dispatchers.Main) { create(enginePackage) } ?: return Result.Failed
        try {
            val text = SAMPLE[languageTag.substringBefore('-')] ?: SAMPLE.getValue("en")
            val done = CompletableDeferred<Boolean>()
            var marks = 0
            val id = "probe|${System.nanoTime()}"
            withContext(Dispatchers.Main) {
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {}
                    override fun onDone(utteranceId: String) { done.complete(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) { done.complete(false) }
                    override fun onError(utteranceId: String, errorCode: Int) { done.complete(false) }
                    override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) { marks++ }
                })
                val locale = Locale.forLanguageTag(languageTag)
                val lang = tts.setLanguage(locale)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return@withContext Result.LanguageUnavailable
                val out = File(context.cacheDir, "tts-probe.wav")
                val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
                if (tts.synthesizeToFile(text, params, out, id) != TextToSpeech.SUCCESS) return@withContext Result.Failed
                null
            }?.let { return it }
            val ok = withTimeoutOrNull(TIMEOUT_MS) { done.await() } ?: false
            return if (ok) Result.Ok(marks, text.split(' ').size) else Result.Failed
        } finally {
            withContext(Dispatchers.Main) { runCatching { tts.stop(); tts.shutdown() } }
        }
    }

    private suspend fun create(enginePackage: String): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var created: TextToSpeech? = null
        created = TextToSpeech(context, { status ->
            val instance = created
            if (status == TextToSpeech.SUCCESS && instance != null) {
                if (cont.isActive) cont.resume(instance)
            } else {
                instance?.shutdown()
                if (cont.isActive) cont.resume(null)
            }
        }, enginePackage)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        // Frases de prueba en el idioma de la voz, no en el de la interfaz.
        val SAMPLE = mapOf(
            "en" to "The little fox runs through the quiet forest every morning.", // i18n-ignore
            "es" to "El pequeño zorro corre por el bosque tranquilo cada mañana.", // i18n-ignore
            "fr" to "Le petit renard court dans la forêt tranquille chaque matin.", // i18n-ignore
            "de" to "Der kleine Fuchs läuft jeden Morgen durch den stillen Wald.", // i18n-ignore
            "it" to "La piccola volpe corre nel bosco tranquillo ogni mattina.", // i18n-ignore
            "pt" to "A pequena raposa corre pela floresta tranquila todas as manhãs.", // i18n-ignore
        )
    }
}
