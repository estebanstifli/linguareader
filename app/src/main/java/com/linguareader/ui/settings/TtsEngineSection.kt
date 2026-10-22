package com.linguareader.ui.settings

import okio.Path.Companion.toOkioPath
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
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
    fun setCacheLimit(mb: Int) = update { it.copy(audioCacheMb = mb) }.invokeOnCompletion { refreshCache() }

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
        voicePacks.clip(language, p.supertonicVoice, VoiceClip.SAMPLE)?.let { clip ->
            val pl = player ?: Media3ClipPlayer(context, handleAudioFocus = true).also { player = it }
            pl.clear(); pl.enqueue(0, clip.path); pl.play()
            test.value = VoiceTestState.Played(language)
            return@launch
        }
        test.value = VoiceTestState.Running(language)
        val out = File(context.cacheDir, "supertonic-test-$language.wav")
        val result = engine.synthesizeTo(
            SpeechRequest(TEST_TEXT.getValue(language), language, p.supertonicVoice, 1.0f, mapOf("steps" to "${p.supertonicSteps}")),
            out.toOkioPath(),
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
        // Frases de prueba en el idioma de la voz, no en el de la interfaz.
        val TEST_TEXT = mapOf(
            "en" to "Hello! This is a short test of the Supertonic voice, running offline on this phone.", // i18n-ignore
            "fr" to "Bonjour ! Ceci est un court essai de la voix Supertonic, sans connexion.", // i18n-ignore
            "es" to "¡Hola! Esta es una breve prueba de la voz Supertonic, sin conexión.", // i18n-ignore
        )
    }
}

private fun mb(bytes: Long) = "%d MB".format(Locale.ROOT, bytes / 1_000_000)

/** «No instalado · 400 MB (descargados 120 MB, se reanudará)». */
@Composable
internal fun modelNotInstalled(m: ModelState.NotInstalled): String =
    if (m.partialBytes > 0) stringResource(R.string.model_not_installed_partial, mb(m.totalBytes), mb(m.partialBytes))
    else stringResource(R.string.model_not_installed, mb(m.totalBytes))

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
        Text(stringResource(R.string.tts_engine_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        EngineOption(
            selected = prefs.ttsEngineId == UserPreferences.ANDROID_TTS_ENGINE,
            title = stringResource(R.string.tts_engine_system),
            subtitle = stringResource(R.string.tts_engine_system_detail),
            onClick = { vm.selectEngine(UserPreferences.ANDROID_TTS_ENGINE) },
        )
        EngineOption(
            selected = prefs.ttsEngineId == SupertonicModel.ENGINE_ID,
            title = SupertonicModel.DISPLAY_NAME,
            subtitle = stringResource(R.string.tts_engine_supertonic_detail, mb(SupertonicModel.TOTAL_BYTES)),
            onClick = { vm.selectEngine(SupertonicModel.ENGINE_ID) },
        )

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tts_model_title), style = MaterialTheme.typography.titleSmall)
                when (val m = model) {
                    is ModelState.NotInstalled -> {
                        Text(modelNotInstalled(m), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.tts_model_wifi_hint), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (prefs.supertonicLicenseAccepted) vm.download() else showLicense = true }) {
                            Text(stringResource(R.string.model_download_size, mb(m.totalBytes - m.partialBytes)))
                        }
                    }
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { m.downloadedBytes.toFloat() / m.totalBytes }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.model_progress, mb(m.downloadedBytes), mb(m.totalBytes)) + " · ${m.currentFile.substringAfterLast('/')}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = vm::cancel) { Text(stringResource(R.string.common_cancel)) }
                    }
                    is ModelState.Verifying -> {
                        LinearProgressIndicator(progress = { m.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.tts_model_verifying), style = MaterialTheme.typography.bodySmall)
                    }
                    is ModelState.Installed -> {
                        Text(
                            stringResource(if (stats.loaded) R.string.tts_model_installed_loaded else R.string.model_installed_on_disk, mb(m.bytesOnDisk)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::verify) { Text(stringResource(R.string.tts_model_verify)) }
                            OutlinedButton(onClick = { scope.launch { vm.delete() } }) { Text(stringResource(R.string.common_remove)) }
                        }
                    }
                    is ModelState.Failed -> {
                        Text(m.message.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { if (prefs.supertonicLicenseAccepted) vm.download() else showLicense = true }) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }

                Text(stringResource(R.string.player_voice), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SupertonicModel.VOICES.forEach { v ->
                        FilterChip(selected = prefs.supertonicVoice == v, onClick = { vm.setVoice(v) }, label = { Text(v) })
                    }
                }
                Text(stringResource(R.string.tts_voices_hint), style = MaterialTheme.typography.bodySmall)

                Text(stringResource(R.string.tts_quality), style = MaterialTheme.typography.labelLarge)
                val stepOptions = listOf(5 to R.string.tts_quality_normal, 8 to R.string.tts_quality_high)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    stepOptions.forEachIndexed { i, (steps, label) ->
                        SegmentedButton(
                            selected = prefs.supertonicSteps == steps,
                            onClick = { vm.setSteps(steps) },
                            shape = SegmentedButtonDefaults.itemShape(i, stepOptions.size),
                        ) { Text("${stringResource(label)} ($steps)") }
                    }
                }

                Text(stringResource(R.string.tts_test_voice), style = MaterialTheme.typography.labelLarge)
                val installed = model is ModelState.Installed
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en", "fr", "es").forEach { lang ->
                        OutlinedButton(onClick = { vm.runTest(lang) }, enabled = installed && test !is VoiceTestState.Running) {
                            Text(lang.uppercase())
                        }
                    }
                }
                when (val t = test) {
                    VoiceTestState.Idle -> if (!installed) Text(stringResource(R.string.tts_test_install_first), style = MaterialTheme.typography.bodySmall)
                    is VoiceTestState.Running -> Text(
                        stringResource(R.string.tts_test_running, Languages.name(t.language)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is VoiceTestState.Failed -> Text(t.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is VoiceTestState.Done -> MetricsText(t.metrics, stats)
                    is VoiceTestState.Played -> Text(stringResource(R.string.tts_test_sample), style = MaterialTheme.typography.bodySmall)
                }

                Text(stringResource(R.string.tts_cache_usage, mb(cacheBytes), "${prefs.audioCacheMb} MB"), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.tts_cache_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(128, 256, 512, 1024).forEach { limit ->
                        FilterChip(selected = prefs.audioCacheMb == limit, onClick = { vm.setCacheLimit(limit) }, label = { Text("$limit MB") }) // i18n-ignore: unidad
                    }
                }
                TextButton(onClick = vm::clearCache) { Text(stringResource(R.string.tts_cache_clear)) }
                Text(
                    stringResource(R.string.tts_license_note, SupertonicModel.LICENSE_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.tts_license_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.tts_license_text,
                        SupertonicModel.LICENSE_NAME,
                        SupertonicModel.REPO,
                        SupertonicModel.REVISION.take(7),
                        mb(SupertonicModel.TOTAL_BYTES),
                        SupertonicModel.LICENSE_URL,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false; vm.acceptLicenseAndDownload() }) { Text(stringResource(R.string.tts_license_accept)) }
            },
            dismissButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun MetricsText(m: SynthesisMetrics, stats: SupertonicStats) {
    val load = stats.lastLoad
    val avgRtf = stats.history.map { it.rtf }.average()
    val lines = listOfNotNull(
        if (m.loadMs != null && load != null) stringResource(R.string.tts_metrics_load, load.loadMs, load.verifyMs, load.threads) else null,
        stringResource(R.string.tts_metrics_synthesis, m.synthMs, "%.1f".format(Locale.ROOT, m.audioMs / 1000.0), "%.2f".format(Locale.ROOT, m.rtf)),
        stringResource(R.string.tts_metrics_steps, m.steps, m.voice, m.memoryMb?.toString() ?: "?"),
        stringResource(R.string.tts_metrics_average, stats.history.size, "%.2f".format(Locale.ROOT, avgRtf)),
    )
    Text(
        lines.joinToString("\n"),
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
