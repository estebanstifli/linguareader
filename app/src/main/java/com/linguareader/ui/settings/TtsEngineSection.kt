package com.linguareader.ui.settings

import com.linguareader.ui.common.toUiText
import okio.Path.Companion.toOkioPath
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.core.audio.FileAudioCache
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.LanguageTags
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
import com.linguareader.engine.tts.AndroidTtsEngine
import com.linguareader.engine.tts.SystemTtsEngine
import com.linguareader.engine.tts.SystemTtsProbe
import com.linguareader.ui.common.Languages
import com.linguareader.ui.common.voiceDetail
import com.linguareader.ui.common.voiceTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed interface VoiceTestState {
    data object Idle : VoiceTestState
    data class Running(val language: String) : VoiceTestState
    data class Done(val metrics: SynthesisMetrics) : VoiceTestState
    /** Se reprodujo la muestra pregenerada del paquete de voces (sin cargar el modelo). */
    data class Played(val language: String) : VoiceTestState
    data class Failed(val message: String) : VoiceTestState
}

/** Resultado de comprobar si un motor del sistema da tiempos por palabra (por motor y idioma). */
sealed interface WordTimingState {
    data object Checking : WordTimingState
    data class Yes(val marks: Int, val words: Int) : WordTimingState
    data object No : WordTimingState
    data object LanguageUnavailable : WordTimingState
    data object Failed : WordTimingState
}

class TtsSettingsViewModel(
    private val repo: UserPreferencesRepository,
    private val manager: SupertonicModelManager,
    private val engine: SupertonicEngine,
    private val cache: FileAudioCache,
    private val voicePacks: VoicePackManager,
    streaming: StreamingSpeechEngine,
    private val context: Context,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = repo.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())
    val modelState: StateFlow<ModelState> = manager.state
    val voicePackState: StateFlow<ModelState> = voicePacks.state
    val stats: StateFlow<SupertonicStats> = engine.stats
    val cacheBytes = MutableStateFlow(0L)
    val test = MutableStateFlow<VoiceTestState>(VoiceTestState.Idle)
    private var player: Media3ClipPlayer? = null

    // ---- Voz del sistema (Android TTS) ----
    private val systemTts = streaming as? AndroidTtsEngine
    private val probe = SystemTtsProbe(context)
    val engines = MutableStateFlow<List<SystemTtsEngine>>(emptyList())
    /** Idioma cuyas voces se muestran (el de lectura por defecto). */
    val voiceLanguage = MutableStateFlow("en")
    val systemVoices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    /** Tiempos por palabra por motor (clave: paquete) para [voiceLanguage]. */
    val wordTiming = MutableStateFlow<Map<String, WordTimingState>>(emptyMap())
    private var probedLanguage: String? = null

    init {
        manager.refresh()
        refreshCache()
        viewModelScope.launch {
            val lang = repo.preferences.map { it.readingLanguage }.distinctUntilChanged().first()
            if (lang != null) voiceLanguage.value = LanguageTags.primary(lang)
        }
        viewModelScope.launch { engines.value = systemTts?.engines().orEmpty() }
        viewModelScope.launch {
            combine(repo.preferences.map { it.systemTtsEngine }.distinctUntilChanged(), voiceLanguage) { pkg, lang -> pkg to lang }
                .collect { (_, lang) ->
                    systemVoices.value = systemTts?.listVoices(lang).orEmpty()
                    if (probedLanguage != lang) { probedLanguage = lang; probeAllEngines() }
                }
        }
    }

    private fun update(t: (UserPreferences) -> UserPreferences) = viewModelScope.launch { repo.update(t) }

    fun selectEngine(id: String) = update { it.copy(ttsEngineId = id) }
    fun setVoice(voice: String) = update { it.copy(supertonicVoice = voice) }
    fun setSteps(steps: Int) = update { it.copy(supertonicSteps = steps) }
    fun setCacheLimit(mb: Int) = update { it.copy(audioCacheMb = mb) }.invokeOnCompletion { refreshCache() }

    fun setSystemEngine(packageName: String?) = update { it.copy(systemTtsEngine = packageName) }
    fun setPitch(pitch: Float) = update { it.copy(ttsPitch = (pitch * 20).toInt() / 20f) }
    fun setSpeechRate(rate: Float) = update { it.copy(speechRate = (rate * 20).toInt() / 20f) }
    fun setVoiceLanguage(lang: String) { voiceLanguage.value = lang }

    /** Voz del sistema para [voiceLanguage]; null = automática. */
    fun setSystemVoice(voiceId: String?) {
        val key = voiceLanguage.value
        update { p -> p.copy(voiceByLanguage = if (voiceId == null) p.voiceByLanguage - key else p.voiceByLanguage + (key to voiceId)) }
    }

    /** Reproduce una frase de muestra con [voiceId] (o la automática) en el idioma elegido, con la velocidad y el tono actuales. */
    fun previewVoice(voiceId: String?) = viewModelScope.launch {
        val p = preferences.value
        val lang = voiceLanguage.value
        systemTts?.preview(lang, voiceId, SystemTtsProbe.SAMPLE[lang] ?: SystemTtsProbe.SAMPLE.getValue("en"), p.speechRate, p.ttsPitch)
    }

    /** Comprueba en cada motor instalado si da tiempos por palabra en el idioma elegido (una síntesis corta por motor). */
    fun probeAllEngines() = viewModelScope.launch {
        val lang = voiceLanguage.value
        val list = engines.value.ifEmpty { systemTts?.engines().orEmpty().also { engines.value = it } }
        for (e in list) {
            wordTiming.update { it + (e.packageName to WordTimingState.Checking) }
            val result = withContext(Dispatchers.IO) { probe.wordMarks(e.packageName, lang) }
            wordTiming.update {
                it + (e.packageName to when (result) {
                    is SystemTtsProbe.Result.Ok -> if (result.marks > 0) WordTimingState.Yes(result.marks, result.words) else WordTimingState.No
                    SystemTtsProbe.Result.LanguageUnavailable -> WordTimingState.LanguageUnavailable
                    SystemTtsProbe.Result.Failed -> WordTimingState.Failed
                })
            }
        }
    }

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
            pl.clear(); pl.enqueue(0, clip.toString()); pl.play()
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
        /** Idiomas con voces que se pueden elegir en los ajustes (los de las bibliotecas). */
        val VOICE_LANGUAGES = listOf("en", "es", "fr")
    }
}

private fun mb(bytes: Long) = "%d MB".format(Locale.ROOT, bytes / 1_000_000)

/** «No instalado · 400 MB (descargados 120 MB, se reanudará)». */
@Composable
internal fun modelNotInstalled(m: ModelState.NotInstalled): String =
    if (m.partialBytes > 0) stringResource(R.string.model_not_installed_partial, mb(m.totalBytes), mb(m.partialBytes))
    else stringResource(R.string.model_not_installed, mb(m.totalBytes))

/**
 * Motor de voz: la elección (voz del sistema / Supertonic) y, debajo, solo los parámetros del motor elegido.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsEngineSection(vm: TtsSettingsViewModel = koinViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    var showLicense by remember { mutableStateOf(false) }

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

        if (prefs.ttsEngineId == SupertonicModel.ENGINE_ID) {
            SupertonicCard(vm, prefs, onShowLicense = { showLicense = true })
        } else {
            SystemVoiceCard(vm, prefs)
        }
        AudioCacheCard(vm, prefs)
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

/** Motor del sistema: qué motor, qué voz por idioma (con prueba), velocidad y tono. Más que los ajustes de Android. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemVoiceCard(vm: TtsSettingsViewModel, prefs: UserPreferences) {
    val engines by vm.engines.collectAsStateWithLifecycle()
    val language by vm.voiceLanguage.collectAsStateWithLifecycle()
    val voices by vm.systemVoices.collectAsStateWithLifecycle()
    val timing by vm.wordTiming.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selectedPackage = prefs.systemTtsEngine ?: engines.firstOrNull { it.isDefault }?.packageName
    val selectedVoice = prefs.voiceByLanguage[language]

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tts_system_voice_title), style = MaterialTheme.typography.titleSmall)

            // Motor: cada uno con si da tiempos por palabra en el idioma elegido.
            Text(stringResource(R.string.tts_system_engine), style = MaterialTheme.typography.labelLarge)
            if (engines.isEmpty()) {
                Text(stringResource(R.string.tts_system_no_engines), style = MaterialTheme.typography.bodySmall)
            }
            engines.forEach { e ->
                val selected = e.packageName == selectedPackage
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = selected, onClick = { vm.setSystemEngine(if (e.isDefault) null else e.packageName) }, role = Role.RadioButton),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(
                            if (e.isDefault) stringResource(R.string.tts_system_engine_default, e.label) else e.label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        WordTimingLine(timing[e.packageName])
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::probeAllEngines, enabled = timing.values.none { it is WordTimingState.Checking }) {
                    Text(stringResource(R.string.tts_word_timing_check))
                }
            }
            Text(stringResource(R.string.tts_word_timing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Idioma de las voces que se listan.
            Text(stringResource(R.string.tts_voice_language), style = MaterialTheme.typography.labelLarge)
            val languages = (TtsSettingsViewModel.VOICE_LANGUAGES + listOfNotNull(prefs.readingLanguage?.let(LanguageTags::primary))).distinct()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                languages.forEach { lang ->
                    FilterChip(selected = language == lang, onClick = { vm.setVoiceLanguage(lang) }, label = { Text(Languages.name(lang)) })
                }
            }

            // Voces del motor elegido para ese idioma, con botón para oírlas.
            Text(stringResource(R.string.tts_voices_for, Languages.name(language)), style = MaterialTheme.typography.labelLarge)
            if (voices.isEmpty()) {
                Text(stringResource(R.string.tts_no_voices, Languages.name(language)), style = MaterialTheme.typography.bodySmall)
            } else {
                VoiceChoiceRow(
                    title = stringResource(R.string.voice_automatic),
                    detail = stringResource(R.string.voice_automatic_detail),
                    selected = selectedVoice == null,
                    onSelect = { vm.setSystemVoice(null) },
                    onPlay = { vm.previewVoice(null) },
                )
                voices.forEach { v ->
                    VoiceChoiceRow(
                        title = voiceTitle(v),
                        detail = voiceDetail(v),
                        selected = selectedVoice == v.id,
                        onSelect = { vm.setSystemVoice(v.id) },
                        onPlay = { vm.previewVoice(v.id) },
                    )
                }
            }

            // Velocidad y tono (se aplican a la lectura y a las pruebas).
            SettingSlider(
                stringResource(R.string.tts_speed), prefs.speechRate, 0.5f..2.0f,
                "%.2f×".format(Locale.ROOT, prefs.speechRate), // i18n-ignore: valor
            ) { vm.setSpeechRate(it) }
            SettingSlider(
                stringResource(R.string.tts_pitch), prefs.ttsPitch, 0.5f..2.0f,
                "%.2f".format(Locale.ROOT, prefs.ttsPitch), // i18n-ignore: valor
            ) { vm.setPitch(it) }

            Text(stringResource(R.string.settings_system_voices_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text(stringResource(R.string.settings_open_tts_settings)) }
        }
    }
}

@Composable
private fun WordTimingLine(state: WordTimingState?) {
    val (text, color) = when (state) {
        null -> stringResource(R.string.tts_word_timing_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
        WordTimingState.Checking -> stringResource(R.string.tts_word_timing_checking) to MaterialTheme.colorScheme.onSurfaceVariant
        is WordTimingState.Yes -> stringResource(R.string.tts_word_timing_yes, state.marks, state.words) to MaterialTheme.colorScheme.primary
        WordTimingState.No -> stringResource(R.string.tts_word_timing_no) to MaterialTheme.colorScheme.error
        WordTimingState.LanguageUnavailable -> stringResource(R.string.tts_word_timing_language_missing) to MaterialTheme.colorScheme.onSurfaceVariant
        WordTimingState.Failed -> stringResource(R.string.tts_word_timing_failed) to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun VoiceChoiceRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onPlay) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.tts_play_sample))
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(valueLabel) }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range)
    }
}

/** Supertonic: modelo, voz, calidad y prueba. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupertonicCard(vm: TtsSettingsViewModel, prefs: UserPreferences, onShowLicense: () -> Unit) {
    val model by vm.modelState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tts_model_title), style = MaterialTheme.typography.titleSmall)
            when (val m = model) {
                is ModelState.NotInstalled -> {
                    Text(modelNotInstalled(m), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.tts_model_wifi_hint), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { if (prefs.supertonicLicenseAccepted) vm.download() else onShowLicense() }) {
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
                    Text(m.error.toUiText().asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { if (prefs.supertonicLicenseAccepted) vm.download() else onShowLicense() }) {
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
            Text(
                stringResource(R.string.tts_license_note, SupertonicModel.LICENSE_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Caché de audio y alineaciones: común a los dos motores. */
@Composable
private fun AudioCacheCard(vm: TtsSettingsViewModel, prefs: UserPreferences) {
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tts_cache_usage, mb(cacheBytes), "${prefs.audioCacheMb} MB"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.tts_cache_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(128, 256, 512, 1024).forEach { limit ->
                    FilterChip(selected = prefs.audioCacheMb == limit, onClick = { vm.setCacheLimit(limit) }, label = { Text("$limit MB") }) // i18n-ignore: unidad
                }
            }
            TextButton(onClick = vm::clearCache) { Text(stringResource(R.string.tts_cache_clear)) }
        }
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
