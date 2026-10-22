package com.linguareader.engine.packs

import android.content.Context
import android.util.Log
import com.linguareader.R
import com.linguareader.core.model.LanguageTags
import com.linguareader.di.ApplicationScope
import com.linguareader.engine.supertonic.DownloadResult
import com.linguareader.engine.supertonic.ModelFile
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.engine.supertonic.SupertonicModelManager
import com.linguareader.ui.common.UiText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class VoicePackCatalog(val version: Int, val asset: String, val sizeBytes: Long, val sha256: String)

@Serializable
private data class VoicePackManifest(val version: Int, val items: List<VoicePackItem>)

@Serializable
private data class VoicePackItem(val path: String, val sizeBytes: Long, val sha256: String)

/** Tipos de audio pregenerado del paquete de voces. */
enum class VoiceClip(val fileName: String) { SAMPLE("sample.wav"), READY("ready.wav") }

/**
 * Paquete de voces de Supertonic ya sintetizadas (muestra de cada voz e idioma y la frase de
 * «preparando la lectura»). Se baja de la release de paquetes (zip de ~12 MB) automáticamente
 * cuando el modelo Supertonic queda instalado, y se descomprime en `files/voice-pack/`.
 * Permite oír una voz o la frase de preparación sin cargar el modelo (decenas de segundos en móviles lentos).
 */
@Singleton
class VoicePackManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val source: GithubPackSource,
    supertonic: SupertonicModelManager,
) {
    private val catalog: VoicePackCatalog? by lazy {
        runCatching { context.assets.open("packs/voices.json").use { Json { ignoreUnknownKeys = true }.decodeFromString<VoicePackCatalog>(it.reader().readText()) } }
            .getOrElse { Log.e(TAG, "Catálogo del paquete de voces ilegible", it); null }
    }
    private val dir = File(context.filesDir, "voice-pack")
    private val marker get() = File(dir, ".installed")
    private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled(0, 0))
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        refresh()
        // «Al bajar el modelo deberíamos bajar unos wav ya creados».
        appScope.launch {
            supertonic.state.collect { s -> if (s is ModelState.Installed && _state.value is ModelState.NotInstalled) download() }
        }
    }

    private fun installedVersion() = runCatching { marker.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()

    fun refresh() {
        if (job?.isActive == true) return
        val c = catalog
        _state.value = when {
            c == null -> ModelState.Failed(UiText.res(R.string.voice_pack_catalog_unavailable))
            installedVersion() == "${c.version}:${c.sha256}" -> ModelState.Installed(c.sizeBytes, dir.path)
            else -> ModelState.NotInstalled(c.sizeBytes, 0)
        }
    }

    /** WAV pregenerado de la voz en ese idioma, o null si el paquete no está instalado. */
    fun clip(languageTag: String, voice: String, kind: VoiceClip): File? {
        if (_state.value !is ModelState.Installed) return null
        return File(dir, "${LanguageTags.primary(languageTag)}/$voice/${kind.fileName}").takeIf { it.isFile }
    }

    fun download() {
        val c = catalog ?: return
        if (job?.isActive == true) return
        job = appScope.launch(Dispatchers.IO) {
            try {
                if (!source.configured) {
                    _state.value = ModelState.Failed(UiText.res(R.string.model_repo_not_configured))
                    return@launch
                }
                _state.value = ModelState.Downloading(0, c.sizeBytes, c.asset)
                val tmp = File(context.cacheDir, "voice-pack-dl").also { it.mkdirs() }
                val file = ModelFile(c.asset, c.sizeBytes, c.sha256)
                val result = source.downloader(listOf(file)) { it.path }.download(tmp) { p ->
                    _state.value = ModelState.Downloading(p.downloadedBytes, p.totalBytes, c.asset)
                }
                if (result is DownloadResult.Failed) {
                    _state.value = ModelState.Failed(UiText.res(R.string.model_download_interrupted, result.message))
                    return@launch
                }
                _state.value = ModelState.Verifying(0f)
                install(File(tmp, c.asset), c)
                tmp.deleteRecursively()
                _state.value = ModelState.Installed(c.sizeBytes, dir.path)
            } catch (e: CancellationException) {
                refresh()
                throw e
            } catch (e: Exception) {
                if (e is java.io.IOException) Log.w(TAG, "Paquete de voces: ${e.message}") else Log.e(TAG, "Paquete de voces", e)
                _state.value = ModelState.Failed(UiText.res(R.string.model_download_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    suspend fun delete() {
        job?.cancel()
        withContext(Dispatchers.IO) { runCatching { dir.deleteRecursively() } }
        refresh()
    }

    /** Descomprime y verifica cada WAV con el sha256 del manifiesto antes de dar el paquete por instalado. */
    private fun install(zip: File, c: VoicePackCatalog) {
        val staging = File(context.filesDir, "voice-pack.tmp").also { it.deleteRecursively(); it.mkdirs() }
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val target = File(staging, e.name)
                require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Entrada de zip no válida: ${e.name}" }
                target.parentFile?.mkdirs()
                target.outputStream().use { z.copyTo(it) }
            }
        }
        val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<VoicePackManifest>(File(staging, "manifest.json").readText())
        manifest.items.forEachIndexed { i, item ->
            val f = File(staging, item.path)
            check(f.length() == item.sizeBytes && sha256(f) == item.sha256) { "Fichero corrupto en el paquete de voces: ${item.path}" }
            _state.value = ModelState.Verifying((i + 1f) / manifest.items.size)
        }
        dir.deleteRecursively()
        check(staging.renameTo(dir)) { "No se pudo instalar el paquete de voces" }
        marker.writeText("${c.version}:${c.sha256}")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "VoicePack"
    }
}
