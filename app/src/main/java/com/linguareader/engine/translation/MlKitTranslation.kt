package com.linguareader.engine.translation

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.linguareader.R
import com.linguareader.core.content.Hashing
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.engine.TranslationRequest
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.TranslationResult.Reason
import com.linguareader.core.engine.TranslationUnit
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.text.Tokenizer
import com.linguareader.data.db.MachineTranslationDao
import com.linguareader.data.db.MachineTranslationEntity
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.ui.common.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Espera una `Task` de Play Services/ML Kit sin depender de kotlinx-coroutines-play-services. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

sealed interface ModelDownload {
    data object Running : ModelDownload
    data class Failed(val message: UiText) : ModelDownload
}

/**
 * Modelos de traducción de ML Kit (~30 MB por idioma, descarga bajo demanda). Los idiomas se
 * identifican con el código de ML Kit, que coincide con la subetiqueta primaria BCP-47 ("es", "fr", "zh").
 */
class TranslationModelManager(
    private val preferences: UserPreferencesRepository,
    private val scope: CoroutineScope,
) {
    private val models = RemoteModelManager.getInstance()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded

    private val _downloads = MutableStateFlow<Map<String, ModelDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownload>> = _downloads

    /** Códigos soportados por ML Kit, ordenados por su nombre en el idioma de la interfaz. */
    val supportedLanguages: List<String>
        get() {
            val ui = Locale.getDefault()
            return TranslateLanguage.getAllLanguages().sortedBy { Locale.forLanguageTag(it).getDisplayLanguage(ui).lowercase(ui) }
        }

    init { scope.launch { refresh() } }

    /** Código de ML Kit para una etiqueta BCP-47, o null si ML Kit no traduce ese idioma. */
    fun code(languageTag: String): String? = TranslateLanguage.fromLanguageTag(LanguageTags.primary(languageTag))

    /** Idioma del sistema si ML Kit lo soporta; si no, inglés. */
    fun deviceLanguage(): String = code(Locale.getDefault().toLanguageTag()) ?: TranslateLanguage.ENGLISH

    suspend fun refresh() {
        runCatching { models.getDownloadedModels(TranslateRemoteModel::class.java).await() }
            .onSuccess { set -> _downloaded.value = set.map { it.language }.toSet() }
            .onFailure { Log.w(TAG, "No se pudo listar modelos: ${it.message}") }
    }

    suspend fun isDownloaded(code: String): Boolean =
        code in _downloaded.value ||
            runCatching { models.isModelDownloaded(model(code)).await() }.getOrDefault(false).also { ok ->
                if (ok) _downloaded.update { it + code }
            }

    /** Descarga en segundo plano (sigue aunque se cierre la pantalla). */
    fun download(code: String) {
        if (_downloads.value[code] == ModelDownload.Running) return
        scope.launch { awaitDownload(code) }
    }

    /** Descarga y espera; true si el modelo queda disponible. Respeta la preferencia «solo Wi-Fi». */
    suspend fun awaitDownload(code: String): Boolean {
        if (isDownloaded(code)) return true
        _downloads.update { it + (code to ModelDownload.Running) }
        val wifiOnly = preferences.preferences.first().translationWifiOnly
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        return runCatching { models.download(model(code), conditions).await() }
            .fold(
                onSuccess = {
                    _downloads.update { it - code }
                    _downloaded.update { it + code }
                    true
                },
                onFailure = { e ->
                    val detail = e.message ?: e.javaClass.simpleName
                    val message = UiText.res(if (wifiOnly) R.string.translator_download_failed_wifi else R.string.translator_download_failed, detail)
                    _downloads.update { it + (code to ModelDownload.Failed(message)) }
                    false
                },
            )
    }

    suspend fun delete(code: String) {
        runCatching { models.deleteDownloadedModel(model(code)).await() }
            .onFailure { Log.w(TAG, "No se pudo borrar $code: ${it.message}") }
        refresh()
    }

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    private companion object {
        const val TAG = "MlKitModels"
    }
}

/**
 * Traductor automático en el dispositivo (ML Kit), detrás de las traducciones incluidas en los
 * libros. Nunca descarga modelos por su cuenta (gasto de datos sin avisar): si falta alguno,
 * responde [Reason.MODEL_MISSING] y la UI ofrece descargarlo. Todo resultado se guarda en caché.
 */
class MlKitTranslationEngine(
    private val models: TranslationModelManager,
    private val cache: MachineTranslationDao,
) : TranslationEngine {

    override val id: String = ID

    private val clientsLock = Mutex()
    private val clients = LinkedHashMap<String, Translator>()

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        if (LanguageTags.sameLanguage(request.sourceLanguage, request.targetLanguage)) {
            return TranslationResult.Unavailable(Reason.SAME_LANGUAGE, id)
        }
        val source = models.code(request.sourceLanguage) ?: return TranslationResult.Unavailable(Reason.LANGUAGE_PAIR_UNSUPPORTED, id)
        val target = models.code(request.targetLanguage) ?: return TranslationResult.Unavailable(Reason.LANGUAGE_PAIR_UNSUPPORTED, id)
        val text = Tokenizer.normalize(request.text)
        if (text.isEmpty()) return TranslationResult.Unavailable(Reason.NO_ENTRY, id)
        val contextual = request.unit == TranslationUnit.SENTENCE
        val hash = Hashing.sha256Hex(text)

        cache.get(id, source, target, hash)?.let { return TranslationResult.Translated(it, id, contextual = contextual) }
        if (!models.isDownloaded(source) || !models.isDownloaded(target)) {
            return TranslationResult.Unavailable(Reason.MODEL_MISSING, id)
        }
        return runCatching { client(source, target).translate(text).await() }.fold(
            onSuccess = { translated ->
                val clean = translated.trim()
                cache.put(MachineTranslationEntity(id, source, target, hash, clean, System.currentTimeMillis()))
                TranslationResult.Translated(clean, id, contextual = contextual)
            },
            onFailure = { e ->
                Log.w("MlKitTranslation", "Fallo $source→$target: ${e.message}")
                TranslationResult.Unavailable(Reason.ENGINE_ERROR, id)
            },
        )
    }

    /** Un cliente por par de idiomas; se conservan los últimos [MAX_CLIENTS] (cada uno retiene memoria del modelo). */
    private suspend fun client(source: String, target: String): Translator = clientsLock.withLock {
        val key = "$source>$target"
        clients.remove(key)?.let { clients[key] = it; return it }
        val created = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        clients[key] = created
        while (clients.size > MAX_CLIENTS) {
            val eldest = clients.keys.first()
            clients.remove(eldest)?.close()
        }
        created
    }

    companion object {
        const val ID = "mlkit"
        private const val MAX_CLIENTS = 2
    }
}
