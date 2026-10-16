package com.linguareader.ui.settings

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.core.audio.FileAudioCache
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.audio.Media3ClipPlayer
import com.linguareader.engine.packs.VoiceClip
import com.linguareader.engine.packs.VoicePackManager
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.engine.supertonic.SupertonicModelManager
import com.linguareader.engine.supertonic.SupertonicStats
import com.linguareader.engine.supertonic.SynthesisMetrics
import com.linguareader.ui.common.Languages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject

sealed interface VoiceTestState {
    data object Idle : VoiceTestState
    data class Running(val language: String) : VoiceTestState
    data class Done(val metrics: SynthesisMetrics) : VoiceTestState
    /** Se reprodujo la muestra pregenerada del paquete de voces (sin cargar el modelo). */
    data class Played(val language: String) : VoiceTestState
    data class Failed(val message: String) : VoiceTestState
}

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val repo: UserPreferencesRepository,
    private val manager: SupertonicModelManager,
    private val engine: SupertonicEngine,
    private val cache: FileAudioCache,
    private val voicePacks: VoicePackManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = repo.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())
    val modelState: StateFlow<ModelState> = manager.state
    val voicePackState: StateFlow<ModelState> = voicePacks.state
    val stats: StateFlow<SupertonicStats> = engine.stats
    val cacheBytes = MutableStateFlow(0L)
    val test = MutableStateFlow<VoiceTestState>(VoiceTestState.Idle)
    private var player: Media3ClipPlayer? = null

    init {
        manager.refresh()
        refreshCache()
    }

    private fun update(t: (UserPreferences) -> UserPreferences) = viewModelScope.launch { repo.update(t) }

    fun selectEngine(id: String) = update { it.copy(ttsEngineId = id) }
    fun setVoice(voice: String) = update { it.copy(supertonicVoice = voice) }
    fun setSteps(steps: Int) = update { it.copy(supertonicSteps = steps) }
    fun setSpeed(rate: Float) = update { it.copy(speechRate = rate) }

    fun acceptLicenseAndDownload() = viewModelScope.launch {
        repo.update { it.copy(supertonicLicenseAccepted = true) }
        manager.download()
    }

    fun download() = manager.download()
    fun cancel() = manager.cancel()
    fun verify() = manager.verify()
    fun delete() = viewModelScope.launch {
        manager.delete()
        if (preferences.value.ttsEngineId == SupertonicModel.ENGINE_ID) repo.update { it.copy(ttsEngineId = UserPreferences.ANDROID_TTS_ENGINE) }
    }

    fun clearCache() = viewModelScope.launch(Dispatchers.IO) {
        cache.clear()
        refreshCache()
    }

    private fun refreshCache() = viewModelScope.launch(Dispatchers.IO) { cacheBytes.value = cache.sizeBytes() }

    /** Prueba funcional: sintetiza una frase corta, la reproduce y muestra tiempos, RTF y memoria. */
    fun runTest(language: String) = viewModelScope.launch {
        val p = preferences.value
        // Muestra pregenerada: suena al instante, sin cargar el modelo.
        voicePacks.clip(language, p.supertonicVoice, VoiceClip.SAMPLE)?.takeIf { p.speechRate == 1.0f }?.let { clip ->
            val pl = player ?: Media3ClipPlayer(context, handleAudioFocus = true).also { player = it }
            pl.clear(); pl.enqueue(0, clip.path); pl.play()
            test.value = VoiceTestState.Played(language)
            return@launch
        }
        test.value = VoiceTestState.Running(language)
        val out = File(context.cacheDir, "supertonic-test-$language.wav")
        val result = engine.synthesizeTo(
            SpeechRequest(TEST_TEXT.getValue(language), language, p.supertonicVoice, p.speechRate, mapOf("steps" to "${p.supertonicSteps}")),
            out,
        )
        when (result) {
            is SynthesisOutcome.Written -> {
                test.value = VoiceTestState.Done(engine.stats.value.lastSynthesis!!)
                val pl = player ?: Media3ClipPlayer(context, handleAudioFocus = true).also { player = it }
                pl.clear()
                pl.enqueue(0, out.path)
                pl.play()
            }
            is SynthesisOutcome.Failed -> test.value = VoiceTestState.Failed(result.failure.message)
        }
    }

    override fun onCleared() {
        player?.release()
    }

    companion object {
        val TEST_TEXT = mapOf(
            "en" to "Hello! This is a short test of the Supertonic voice, running offline on this phone.",
            "fr" to "Bonjour ! Ceci est un court essai de la voix Supertonic, sans connexion.",
            "es" to "¡Hola! Esta es una breve prueba de la voz Supertonic, sin conexión.",
        )
    }
}

private fun mb(bytes: Long) = "%d MB".format(Locale.ROOT, bytes / 1_000_000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsEngineSection(vm: TtsSettingsViewModel = hiltViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val model by vm.modelState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    var showLicense by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Motor de voz", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        EngineOption(
            selected = prefs.ttsEngineId == UserPreferences.ANDROID_TTS_ENGINE,
            title = "Voz del sistema (Android TTS)",
            subtitle = "Sin descargas. Resaltado por palabra si el motor instalado envía eventos.",
            onClick = { vm.selectEngine(UserPreferences.ANDROID_TTS_ENGINE) },
        )
        EngineOption(
            selected = prefs.ttsEngineId == SupertonicModel.ENGINE_ID,
            title = SupertonicModel.DISPLAY_NAME,
            subtitle = "Voz neuronal sin conexión en la CPU del móvil. Descarga única de ${mb(SupertonicModel.TOTAL_BYTES)}. " +
                "Resaltado por frase; por palabra con un modelo de alineación opcional (más abajo).",
            onClick = { vm.selectEngine(SupertonicModel.ENGINE_ID) },
        )

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modelo Supertonic 3", style = MaterialTheme.typography.titleSmall)
                when (val m = model) {
                    is ModelState.NotInstalled -> {
                        Text(
                            "No instalado · ${mb(m.totalBytes)}" + if (m.partialBytes > 0) " (descargados ${mb(m.partialBytes)}, se reanudará)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Se recomienda Wi‑Fi. Se guarda en el almacenamiento de la app y se borra al desinstalarla.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (prefs.supertonicLicenseAccepted) vm.download() else showLicense = true }) {
                            Text("Descargar (${mb(m.totalBytes - m.partialBytes)})")
                        }
                    }
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { m.downloadedBytes.toFloat() / m.totalBytes }, modifier = Modifier.fillMaxWidth())
                        Text("${mb(m.downloadedBytes)} de ${mb(m.totalBytes)} · ${m.currentFile.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = vm::cancel) { Text("Cancelar") }
                    }
                    is ModelState.Verifying -> {
                        LinearProgressIndicator(progress = { m.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("Verificando integridad (sha256)…", style = MaterialTheme.typography.bodySmall)
                    }
                    is ModelState.Installed -> {
                        Text("Instalado · ${mb(m.bytesOnDisk)} en disco" + if (stats.loaded) " · cargado en memoria" else "", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::verify) { Text("Verificar") }
                            OutlinedButton(onClick = { scope.launch { vm.delete() } }) { Text("Eliminar") }
                        }
                    }
                    is ModelState.Failed -> {
                        Text(m.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { if (prefs.supertonicLicenseAccepted) vm.download() else showLicense = true }) { Text("Reintentar") }
                    }
                }

                Text("Voz", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SupertonicModel.VOICES.forEach { v ->
                        FilterChip(selected = prefs.supertonicVoice == v, onClick = { vm.setVoice(v) }, label = { Text(v) })
                    }
                }
                Text("F = voces femeninas, M = masculinas. Todas son multilingües (31 idiomas, incluidos inglés, francés y español).", style = MaterialTheme.typography.bodySmall)

                Text("Calidad (pasos de síntesis)", style = MaterialTheme.typography.labelLarge)
                val stepOptions = listOf(3 to "Rápida", 5 to "Normal", 8 to "Alta")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    stepOptions.forEachIndexed { i, (steps, label) ->
                        SegmentedButton(
                            selected = prefs.supertonicSteps == steps,
                            onClick = { vm.setSteps(steps) },
                            shape = SegmentedButtonDefaults.itemShape(i, stepOptions.size),
                        ) { Text("$label ($steps)") }
                    }
                }

                Text("Velocidad (ambos motores)", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.75f, 0.9f, 1.0f, 1.25f, 1.5f).forEach { r ->
                        FilterChip(selected = prefs.speechRate == r, onClick = { vm.setSpeed(r) }, label = { Text("${r}×") })
                    }
                }

                Text("Probar voz (idioma)", style = MaterialTheme.typography.labelLarge)
                val installed = model is ModelState.Installed
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en", "fr", "es").forEach { lang ->
                        OutlinedButton(onClick = { vm.runTest(lang) }, enabled = installed && test !is VoiceTestState.Running) {
                            Text(lang.uppercase())
                        }
                    }
                }
                when (val t = test) {
                    VoiceTestState.Idle -> if (!installed) Text("Instala el modelo para probarlo.", style = MaterialTheme.typography.bodySmall)
                    is VoiceTestState.Running -> Text("Sintetizando (${Languages.name(t.language)})… la primera vez carga el modelo.", style = MaterialTheme.typography.bodySmall)
                    is VoiceTestState.Failed -> Text(t.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is VoiceTestState.Done -> MetricsText(t.metrics, stats)
                    is VoiceTestState.Played -> Text("Muestra pregenerada de la voz (paquete de voces, sin cargar el modelo). Con otra velocidad se sintetiza en el momento.", style = MaterialTheme.typography.bodySmall)
                }

                Text("Caché de audio generado: ${mb(cacheBytes)} (máx. 200 MB)", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::clearCache) { Text("Vaciar caché") }
                Text(
                    "Código: MIT. Pesos: ${SupertonicModel.LICENSE_NAME} (restricciones de uso). Proyecto archivado por Supertone, sin soporte. " +
                        "El audio generado es voz sintética.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("Licencia del modelo") },
            text = {
                Text(
                    "Los pesos de Supertonic 3 se distribuyen bajo la licencia ${SupertonicModel.LICENSE_NAME}. " +
                        "Al descargarlos aceptas sus restricciones de uso (Anexo A), entre ellas: no usarlo para fines ilegales, " +
                        "para dañar a menores, para suplantar a personas sin su consentimiento, para difundir desinformación dañina " +
                        "ni para acosar; y no presentar el audio generado como si no fuera generado por máquina.\n\n" +
                        "Origen: huggingface.co/${SupertonicModel.REPO} (revisión ${SupertonicModel.REVISION.take(7)}). " +
                        "Tamaño: ${mb(SupertonicModel.TOTAL_BYTES)}. Texto completo: ${SupertonicModel.LICENSE_URL}",
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false; vm.acceptLicenseAndDownload() }) { Text("Acepto y descargar") }
            },
            dismissButton = { TextButton(onClick = { showLicense = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MetricsText(m: SynthesisMetrics, stats: SupertonicStats) {
    val load = stats.lastLoad
    val avgRtf = stats.history.map { it.rtf }.average()
    Text(
        buildString {
            if (m.loadMs != null && load != null) append("Carga del modelo: ${load.loadMs} ms (verificación ${load.verifyMs} ms, ${load.threads} hilos)\n")
            append("Síntesis: ${m.synthMs} ms · Audio: ${"%.1f".format(Locale.ROOT, m.audioMs / 1000.0)} s · RTF ${"%.2f".format(Locale.ROOT, m.rtf)}\n")
            append("Pasos: ${m.steps} · Voz ${m.voice} · Memoria del proceso (PSS): ${m.memoryMb ?: "?"} MB\n")
            append("Media de RTF en ${stats.history.size} síntesis: ${"%.2f".format(Locale.ROOT, avgRtf)}")
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun EngineOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
