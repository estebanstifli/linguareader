package com.linguareader.ui.onboarding

import com.linguareader.ui.common.toUiText
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.LibraryInfo
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryPackState
import com.linguareader.engine.tts.SystemTtsAutoSelect
import com.linguareader.engine.translation.ModelDownload
import com.linguareader.engine.translation.TranslationModelManager
import com.linguareader.ui.common.Languages
import com.linguareader.ui.languages.LanguageInstaller
import com.linguareader.ui.languages.LanguagesOnboardingContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Estado de la voz del sistema para el idioma que se aprende (resumen final). */
sealed interface VoiceCheck {
    data object Checking : VoiceCheck
    data object Ready : VoiceCheck
    /** Falta descargar la voz de ese idioma en el motor del sistema: se ofrece instalarla. */
    data class Missing(val enginePackage: String?) : VoiceCheck
    data object NoEngine : VoiceCheck
}

class OnboardingViewModel(
    private val repo: UserPreferencesRepository,
    private val libraries: LibraryPackManager,
    private val models: TranslationModelManager,
    private val installer: LanguageInstaller,
    private val tts: StreamingSpeechEngine,
    private val ttsAutoSelect: SystemTtsAutoSelect,
) : ViewModel() {
    val preferences: StateFlow<UserPreferences?> = repo.preferences.map<UserPreferences, UserPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val libraryCatalog: StateFlow<Map<String, LibraryInfo>> = libraries.catalog
    val libraryStates: StateFlow<Map<String, LibraryPackState>> = libraries.states
    val downloadedModels: StateFlow<Set<String>> = models.downloaded
    val modelDownloads: StateFlow<Map<String, ModelDownload>> = models.downloads
    val voice = MutableStateFlow<VoiceCheck>(VoiceCheck.Checking)

    init { viewModelScope.launch { models.refresh() } }

    private fun update(t: (UserPreferences) -> UserPreferences) = viewModelScope.launch { repo.update(t) }

    /** Último dato: se guarda el nivel y arranca todo lo que se descarga, en segundo plano y de una vez. */
    fun chooseLevel(level: CefrLevel?) = viewModelScope.launch {
        repo.update { it.copy(learnerLevel = level, learnerLevelChosen = true) }
        startDownloads()
    }

    /**
     * Bibliotecas de los idiomas que se aprenden y los modelos de traducción (los suyos y el nativo). Respeta
     * «solo con Wi-Fi» (ML Kit espera a tener Wi-Fi). Se puede volver a llamar: lo ya descargado no se repite.
     */
    fun startDownloads() = viewModelScope.launch { installer.installAll(repo.preferences.first()) }

    /** Códigos ML Kit de los traductores que hacen falta para todos los idiomas que se aprenden. */
    fun neededModels(p: UserPreferences): List<String> =
        p.learningLanguages.flatMap { installer.neededModels(it, p.translationLanguage) }.distinct()

    fun retryLibrary(lang: String) = libraries.download(lang)
    fun retryModel(code: String) = models.download(code)

    /** Primera detección: antes de comprobar la voz se elige motor (Google si está) por si el predeterminado no sirve. */
    fun checkVoice(lang: String) = viewModelScope.launch {
        if (voice.value !is VoiceCheck.Missing) voice.value = VoiceCheck.Checking
        runCatching { ttsAutoSelect.ensureChosen(lang) }
        voice.value = when (val r = runCatching { tts.prepare(lang, null) }.getOrNull()) {
            EngineReadiness.Ready -> VoiceCheck.Ready
            is EngineReadiness.Unavailable -> when (r.reason) {
                EngineUnavailableReason.LANGUAGE_DATA_MISSING, EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED ->
                    VoiceCheck.Missing(repo.preferences.first().systemTtsEngine)
                else -> VoiceCheck.NoEngine
            }
            null -> VoiceCheck.NoEngine
        }
    }

    fun finish() = update { it.copy(onboardingSummaryShown = true) }
}

/**
 * Primera apertura: idiomas (los que se aprenden y el nativo), nivel y resumen de lo que se prepara en segundo plano.
 * Se dibuja encima de [content], que ya se va cargando debajo.
 */
@Composable
fun OnboardingGate(content: @Composable () -> Unit, vm: OnboardingViewModel = koinViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        content()
        val p = prefs ?: return@Box
        when (nextOnboardingStep(p)) {
            OnboardingStep.LANGUAGES -> LanguagesScreen(p)
            OnboardingStep.LEVEL -> LevelScreen(p, vm)
            OnboardingStep.SUMMARY -> SummaryScreen(p, vm)
            null -> Unit
        }
    }
}

private const val STEPS = 3

@Composable
private fun LanguagesScreen(p: UserPreferences) {
    Page(1, STEPS, stringResource(R.string.onboarding_languages_title), stringResource(R.string.onboarding_languages_body)) {
        LanguagesOnboardingContent(p)
    }
}

/** Marco común: fondo, márgenes, desplazamiento y barra de progreso del asistente. */
@Composable
private fun Page(step: Int, steps: Int, title: String, body: String?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (steps > 1) {
            LinearProgressIndicator(progress = { step.toFloat() / steps }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.onboarding_step, step, steps), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

private val LEVEL_HINTS = listOf(
    CefrLevel.A1 to R.string.onboarding_level_a1,
    CefrLevel.A2 to R.string.onboarding_level_a2,
    CefrLevel.B1 to R.string.onboarding_level_b1,
    CefrLevel.B2 to R.string.onboarding_level_b2,
)

@Composable
private fun LevelScreen(p: UserPreferences, vm: OnboardingViewModel) {
    var level by rememberSaveable { mutableStateOf<CefrLevel?>(null) }
    var unsure by rememberSaveable { mutableStateOf(false) }
    BackHandler { vm.chooseLevel(null) }
    val title = p.readingLanguage?.let { stringResource(R.string.onboarding_level_title, Languages.nameInSentence(it)) }
        ?: stringResource(R.string.onboarding_level_title_generic)
    Page(2, STEPS, title, stringResource(R.string.onboarding_level_body)) {
        LEVEL_HINTS.forEach { (l, hint) ->
            LevelRow("${l.name} · ${stringResource(hint)}", selected = level == l) { level = l; unsure = false }
        }
        LevelRow(stringResource(R.string.onboarding_level_unsure), selected = unsure) { level = null; unsure = true }
        Button(
            onClick = { vm.chooseLevel(level) },
            enabled = level != null || unsure,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onboarding_continue_download)) }
        Text(
            stringResource(R.string.onboarding_level_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LevelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

/** «Esto es lo que hemos preparado»: lo descargado, lo que sigue en curso (sin bloquear) y lo que falta. */
@Composable
private fun SummaryScreen(p: UserPreferences, vm: OnboardingViewModel) {
    val catalog by vm.libraryCatalog.collectAsStateWithLifecycle()
    val libraryStates by vm.libraryStates.collectAsStateWithLifecycle()
    val downloaded by vm.downloadedModels.collectAsStateWithLifecycle()
    val downloads by vm.modelDownloads.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // La voz del sistema se comprueba para el idioma principal (el primero elegido).
    val lang = p.readingLanguage
    LaunchedEffect(lang) { lang?.let { vm.startDownloads(); vm.checkVoice(it) } }
    // Al volver de instalar la voz en el motor, se comprueba otra vez.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, lang) {
        if (lang != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { if (vm.voice.value is VoiceCheck.Missing) vm.checkVoice(lang) }
    }
    BackHandler { vm.finish() }

    Page(STEPS, STEPS, stringResource(R.string.onboarding_summary_title), stringResource(R.string.onboarding_summary_body)) {
        val retry = stringResource(R.string.common_retry)
        p.learningLanguages.forEach { l ->
            val info = catalog[l]
            val libraryTitle = stringResource(R.string.onboarding_library_of, Languages.nameInSentence(l))
            when (val st = libraryStates[l] ?: LibraryPackState.NotInstalled) {
                is LibraryPackState.Installed -> Item(
                    R.drawable.ic_library_books, libraryTitle,
                    stringResource(R.string.onboarding_library_ready, pluralStringResource(R.plurals.common_books, st.books, st.books)), Status.DONE,
                )
                is LibraryPackState.Downloading -> Item(
                    R.drawable.ic_library_books, libraryTitle,
                    stringResource(R.string.common_downloading_progress, mb(st.bytes), mb(st.total)), Status.RUNNING,
                )
                is LibraryPackState.Installing -> Item(
                    R.drawable.ic_library_books, libraryTitle,
                    if (st.total == 0) stringResource(R.string.common_preparing) else stringResource(R.string.onboarding_preparing_books, st.done, st.total),
                    Status.RUNNING,
                )
                is LibraryPackState.Failed -> Item(R.drawable.ic_library_books, libraryTitle, st.error.toUiText().asString(), Status.FAILED, retry) {
                    vm.retryLibrary(l)
                }
                LibraryPackState.NotInstalled -> Item(
                    R.drawable.ic_library_books, libraryTitle,
                    if (info == null) stringResource(R.string.onboarding_no_library)
                    else stringResource(R.string.onboarding_library_pending, pluralStringResource(R.plurals.common_books, info.books, info.books)),
                    Status.PENDING,
                )
            }
        }
        if (lang != null) {
            vm.neededModels(p).forEach { code ->
                val title = stringResource(R.string.onboarding_translator_of, Languages.nameInSentence(code))
                when {
                    code in downloaded -> Item(R.drawable.ic_translate, title, stringResource(R.string.onboarding_translator_ready), Status.DONE)
                    downloads[code] == ModelDownload.Running -> Item(
                        R.drawable.ic_translate, title,
                        stringResource(if (p.translationWifiOnly) R.string.common_downloading_or_wifi else R.string.common_downloading), Status.RUNNING,
                    )
                    downloads[code] is ModelDownload.Failed -> Item(
                        R.drawable.ic_translate, title, (downloads[code] as ModelDownload.Failed).message.asString(), Status.FAILED, retry,
                    ) {
                        vm.retryModel(code)
                    }
                    else -> Item(
                        R.drawable.ic_translate, title, stringResource(R.string.common_pending), Status.PENDING, stringResource(R.string.common_download),
                    ) { vm.retryModel(code) }
                }
            }
            val voiceTitle = stringResource(R.string.onboarding_voice_in, Languages.nameInSentence(lang))
            when (val v = voice) {
                VoiceCheck.Checking -> Item(R.drawable.ic_record_voice_over, voiceTitle, stringResource(R.string.common_checking), Status.RUNNING)
                VoiceCheck.Ready -> Item(R.drawable.ic_record_voice_over, voiceTitle, stringResource(R.string.onboarding_voice_ready), Status.DONE)
                is VoiceCheck.Missing -> Item(
                    R.drawable.ic_record_voice_over, voiceTitle,
                    stringResource(R.string.onboarding_voice_missing), Status.FAILED, stringResource(R.string.common_install),
                ) {
                    // Al motor que usará la app, no al predeterminado de Android (que puede ser otro).
                    val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(Intent(install).apply { v.enginePackage?.let(::setPackage) }) }
                        .recoverCatching { context.startActivity(install) }
                }
                VoiceCheck.NoEngine -> Item(
                    R.drawable.ic_record_voice_over, voiceTitle,
                    stringResource(R.string.onboarding_voice_no_engine), Status.FAILED,
                )
            }
        } else {
            Item(
                R.drawable.ic_translate, stringResource(R.string.onboarding_language_to_learn),
                stringResource(R.string.onboarding_language_not_chosen), Status.PENDING,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = vm::finish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_start_reading)) }
    }
}

private enum class Status { DONE, RUNNING, PENDING, FAILED }

@Composable
private fun Item(icon: Int, title: String, detail: String, status: Status, action: String? = null, onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).background(
                    when (status) {
                        Status.DONE -> colors.primaryContainer
                        Status.FAILED -> colors.errorContainer
                        else -> colors.surfaceContainerHighest
                    },
                    CircleShape,
                ),
            ) {
                if (status == Status.RUNNING) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = if (status == Status.FAILED) colors.error else colors.onSurfaceVariant)
            }
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun mb(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)
