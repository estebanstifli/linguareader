package com.linguareader.shared

import com.linguareader.core.content.Hashing
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.engine.TranslationRequest
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.TranslationResult.Reason
import com.linguareader.core.engine.TranslationUnit
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.platform.epochMillis
import com.linguareader.core.text.Tokenizer
import com.linguareader.data.db.MachineTranslationDao
import com.linguareader.data.db.MachineTranslationEntity
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.translation.TranslationModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Puente con el traductor automático escrito en Swift (ML Kit, `ios/LinguaReader/Translation/MlKitTranslator.swift`).
 * Swift no puede implementar interfaces Kotlin con `suspend`, así que todo va por callbacks; las envolturas
 * `suspend` están en [IosTranslationModels] y [IosTranslationEngine]. Los idiomas se identifican con el código de
 * ML Kit (subetiqueta primaria BCP-47: "es", "fr"), igual que en Android.
 */
interface IosTranslator {
    /** Códigos que el traductor conoce. */
    fun supportedCodes(): List<String>

    /** Código para una etiqueta BCP-47, o null si el traductor no conoce ese idioma. */
    fun code(languageTag: String): String?

    /** Modelos ya descargados en el dispositivo. */
    fun downloadedCodes(completion: (List<String>) -> Unit)

    /** Descarga el modelo (si [wifiOnly], solo por Wi-Fi) y avisa con null si quedó disponible o con el motivo del fallo. */
    fun download(code: String, wifiOnly: Boolean, completion: (String?) -> Unit)

    /** Borra el modelo; null si se borró o con el motivo del fallo. */
    fun delete(code: String, completion: (String?) -> Unit)

    /** Traduce [text] entre dos códigos cuyos modelos ya están descargados: (traducción, error). */
    fun translate(text: String, source: String, target: String, completion: (String?, String?) -> Unit)
}

sealed interface TranslationModelDownload {
    data object Running : TranslationModelDownload
    data class Failed(val detail: String, val wifiOnly: Boolean) : TranslationModelDownload
}

/** Lo que ve la pantalla de modelos: idiomas soportados, descargados y descargas en curso o fallidas. */
data class TranslationModelsState(
    val supported: List<String>,
    val downloaded: Set<String>,
    val downloads: Map<String, TranslationModelDownload>,
)

/**
 * Modelos de traducción (~30 MB por idioma, descarga bajo demanda): el `TranslationModelManager` de Android sobre
 * el puente [IosTranslator]. Respeta la preferencia «solo Wi-Fi» y nunca descarga sin que lo pida el usuario, el
 * onboarding o la pretraducción.
 */
class IosTranslationModels(
    private val translator: IosTranslator,
    private val preferences: UserPreferencesRepository,
    private val scope: CoroutineScope,
) : TranslationModels {

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded

    private val _downloads = MutableStateFlow<Map<String, TranslationModelDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, TranslationModelDownload>> = _downloads

    val supportedLanguages: List<String> by lazy { translator.supportedCodes().sorted() }

    val state: Flow<TranslationModelsState> =
        combine(_downloaded, _downloads) { d, r -> TranslationModelsState(supportedLanguages, d, r) }

    init { scope.launch { refresh() } }

    override fun code(languageTag: String): String? = translator.code(LanguageTags.primary(languageTag))

    suspend fun refresh() {
        val codes = suspendCancellableCoroutine { cont -> translator.downloadedCodes { cont.resume(it) } }
        _downloaded.value = codes.toSet()
    }

    suspend fun isDownloaded(code: String): Boolean {
        if (code in _downloaded.value) return true
        refresh()
        return code in _downloaded.value
    }

    /** Descarga en segundo plano (sigue aunque se cierre la pantalla). */
    fun download(code: String) {
        if (_downloads.value[code] == TranslationModelDownload.Running) return
        scope.launch { awaitDownload(code) }
    }

    /** Descarga y espera; true si el modelo queda disponible. */
    override suspend fun awaitDownload(code: String): Boolean {
        if (isDownloaded(code)) return true
        _downloads.update { it + (code to TranslationModelDownload.Running) }
        val wifiOnly = preferences.preferences.first().translationWifiOnly
        val error = suspendCancellableCoroutine { cont -> translator.download(code, wifiOnly) { cont.resume(it) } }
        return if (error == null) {
            _downloads.update { it - code }
            _downloaded.update { it + code }
            Diagnostics.log(TAG, "model $code downloaded")
            true
        } else {
            _downloads.update { it + (code to TranslationModelDownload.Failed(error, wifiOnly)) }
            Diagnostics.log(TAG, "model $code failed: $error (wifiOnly=$wifiOnly)")
            false
        }
    }

    suspend fun delete(code: String) {
        val error = suspendCancellableCoroutine { cont -> translator.delete(code) { cont.resume(it) } }
        if (error != null) Diagnostics.log(TAG, "cannot delete $code: $error")
        _downloads.update { it - code }
        refresh()
    }

    /** Códigos que faltan para traducir de [sourceLanguage] a [targetLanguage] (vacío si no hace falta nada). */
    suspend fun missingFor(sourceLanguage: String, targetLanguage: String): List<String> =
        listOfNotNull(code(sourceLanguage), code(targetLanguage)).distinct().filterNot { isDownloaded(it) }

    private companion object {
        const val TAG = "TranslationModels"
    }
}

/**
 * Traductor automático del dispositivo detrás de las traducciones incluidas en los libros, como `MlKitTranslationEngine`
 * en Android: si falta un modelo responde [Reason.MODEL_MISSING] (la ficha ofrece descargarlo) y todo resultado se
 * guarda en la caché compartida de Room.
 */
class IosTranslationEngine(
    private val translator: IosTranslator,
    private val models: IosTranslationModels,
    private val cache: MachineTranslationDao,
) : TranslationEngine {

    override val id: String = ID

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
        val (translated, error) = suspendCancellableCoroutine { cont ->
            translator.translate(text, source, target) { t, e -> cont.resume(t to e) }
        }
        val clean = translated?.trim()
        if (clean.isNullOrEmpty()) {
            Diagnostics.log(TAG, "$source→$target failed: ${error ?: "empty result"}")
            return TranslationResult.Unavailable(Reason.ENGINE_ERROR, id)
        }
        cache.put(MachineTranslationEntity(id, source, target, hash, clean, epochMillis()))
        return TranslationResult.Translated(clean, id, contextual = contextual)
    }

    companion object {
        /** Mismo id que en Android: la caché de traducciones es compatible entre plataformas. */
        const val ID = "mlkit"
        private const val TAG = "Translation"
    }
}
