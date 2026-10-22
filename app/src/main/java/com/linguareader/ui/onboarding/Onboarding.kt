package com.linguareader.ui.onboarding

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.db.DeviceDocumentDao
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.DocumentScanner
import com.linguareader.data.scan.ScanSettings
import com.linguareader.data.scan.ScanSettingsRepository
import com.linguareader.data.scan.ScanState
import com.linguareader.data.scan.StorageAccess
import com.linguareader.engine.packs.LibraryInfo
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryState
import com.linguareader.engine.translation.ModelDownload
import com.linguareader.engine.translation.TranslationModelManager
import com.linguareader.ui.common.Languages
import com.linguareader.ui.settings.ScanOfferScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** Estado de la voz del sistema para el idioma que se aprende (resumen final). */
sealed interface VoiceCheck {
    data object Checking : VoiceCheck
    data object Ready : VoiceCheck
    /** Falta descargar la voz de ese idioma en el motor del sistema: se ofrece instalarla. */
    data object Missing : VoiceCheck
    data object NoEngine : VoiceCheck
}

class OnboardingViewModel(
    private val repo: UserPreferencesRepository,
    private val scanSettings: ScanSettingsRepository,
    private val libraries: LibraryPackManager,
    private val models: TranslationModelManager,
    private val scanner: DocumentScanner,
    private val tts: StreamingSpeechEngine,
    documents: DeviceDocumentDao,
) : ViewModel() {
    val preferences: StateFlow<UserPreferences?> = repo.preferences.map<UserPreferences, UserPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val scan: StateFlow<ScanSettings?> = scanSettings.settings.map<ScanSettings, ScanSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val libraryCatalog: StateFlow<Map<String, LibraryInfo>> = libraries.catalog
    val libraryStates: StateFlow<Map<String, LibraryState>> = libraries.states
    val downloadedModels: StateFlow<Set<String>> = models.downloaded
    val modelDownloads: StateFlow<Map<String, ModelDownload>> = models.downloads
    val scanState: StateFlow<ScanState> = scanner.state
    val documentCount: StateFlow<Int> = documents.observeAll().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val voice = MutableStateFlow<VoiceCheck>(VoiceCheck.Checking)

    /** Idiomas a los que ML Kit sabe traducir (selector «Tu idioma»). */
    val translationLanguages: List<String> get() = models.supportedLanguages
    val deviceLanguage: String get() = models.deviceLanguage()

    init { viewModelScope.launch { models.refresh() } }

    private fun update(t: (UserPreferences) -> UserPreferences) = viewModelScope.launch { repo.update(t) }

    fun choosePurpose(purpose: AppPurpose) = update { it.withPurpose(purpose) }

    /** null = «Ahora no»: sin idioma no hay biblioteca ni nivel que preguntar. */
    fun chooseLearnLanguage(lang: String?) = update { p ->
        p.copy(
            readingLanguage = lang ?: p.readingLanguage,
            learnLanguageChosen = true,
            learnerLevelChosen = p.learnerLevelChosen || lang == null,
        )
    }

    fun chooseNativeLanguage(code: String, wifiOnly: Boolean) =
        update { it.copy(translationLanguage = code, translationWifiOnly = wifiOnly, translationSetupDone = true) }

    /** Último dato de idiomas: se guarda el nivel y arranca todo lo que se descarga, en segundo plano y de una vez. */
    fun chooseLevel(level: CefrLevel?) = viewModelScope.launch {
        repo.update { it.copy(learnerLevel = level, learnerLevelChosen = true) }
        startDownloads()
    }

    /**
     * Biblioteca del idioma que se aprende y los dos modelos de traducción (el suyo y el del usuario). Respeta
     * «solo con Wi-Fi» (ML Kit espera a tener Wi-Fi). Se puede volver a llamar: lo ya descargado no se repite.
     */
    fun startDownloads() = viewModelScope.launch {
        val p = repo.preferences.first()
        val lang = p.readingLanguage ?: return@launch
        val library = libraries.states.value[lang]
        if (lang in libraries.catalog.value && (library == null || library is LibraryState.NotInstalled || library is LibraryState.Failed)) {
            libraries.download(lang)
        }
        neededModels(p).filter { it !in models.downloaded.value && models.downloads.value[it] != ModelDownload.Running }
            .forEach(models::download)
    }

    /** Códigos ML Kit de los idiomas entre los que se traduce. */
    fun neededModels(p: UserPreferences): List<String> =
        listOfNotNull(p.readingLanguage, p.translationLanguage).mapNotNull(models::code).distinct()
            .takeIf { it.size > 1 }.orEmpty()

    fun retryLibrary(lang: String) = libraries.download(lang)
    fun retryModel(code: String) = models.download(code)

    fun checkVoice(lang: String) = viewModelScope.launch {
        voice.value = VoiceCheck.Checking
        voice.value = when (val r = runCatching { tts.prepare(lang, null) }.getOrNull()) {
            EngineReadiness.Ready -> VoiceCheck.Ready
            is EngineReadiness.Unavailable -> when (r.reason) {
                EngineUnavailableReason.LANGUAGE_DATA_MISSING, EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED -> VoiceCheck.Missing
                else -> VoiceCheck.NoEngine
            }
            null -> VoiceCheck.NoEngine
        }
    }

    fun finish() = update { it.copy(onboardingSummaryShown = true) }
}

/**
 * Primera apertura: según el propósito (leer, aprender o las dos cosas) pregunta solo lo necesario y prepara la app
 * en segundo plano. Se dibuja encima de [content], que ya se va cargando debajo.
 */
@Composable
fun OnboardingGate(content: @Composable () -> Unit, vm: OnboardingViewModel = koinViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        content()
        val p = prefs ?: return@Box
        val s = scan ?: return@Box
        when (nextOnboardingStep(p, s.offered)) {
            OnboardingStep.PURPOSE -> PurposeScreen(vm::choosePurpose)
            OnboardingStep.LEARN_LANGUAGE -> LearnLanguageScreen(p, vm)
            OnboardingStep.NATIVE_LANGUAGE -> NativeLanguageScreen(p, vm)
            OnboardingStep.LEVEL -> LevelScreen(p, vm)
            OnboardingStep.SCAN -> ScanOfferScreen(step = stepsFor(p) - 1, steps = stepsFor(p))
            OnboardingStep.SUMMARY -> SummaryScreen(p, vm)
            null -> Unit
        }
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

/** Número de pasos del camino elegido (para la barra de progreso). */
private fun stepsFor(p: UserPreferences): Int = when (p.purpose) {
    AppPurpose.READ -> 3
    AppPurpose.LEARN -> 5
    AppPurpose.BOTH, null -> 6
}

@Composable
private fun PurposeScreen(onChoose: (AppPurpose) -> Unit) {
    Page(1, 0, stringResource(R.string.onboarding_purpose_title), stringResource(R.string.onboarding_purpose_body)) {
        PurposeCard(
            R.drawable.ic_menu_book,
            stringResource(R.string.onboarding_purpose_read),
            stringResource(R.string.onboarding_purpose_read_detail),
        ) { onChoose(AppPurpose.READ) }
        PurposeCard(
            R.drawable.ic_translate,
            stringResource(R.string.onboarding_purpose_learn),
            stringResource(R.string.onboarding_purpose_learn_detail),
        ) { onChoose(AppPurpose.LEARN) }
        PurposeCard(
            R.drawable.ic_library_books,
            stringResource(R.string.onboarding_purpose_both),
            stringResource(R.string.onboarding_purpose_both_detail),
        ) { onChoose(AppPurpose.BOTH) }
    }
}

@Composable
private fun PurposeCard(icon: Int, title: String, body: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LearnLanguageScreen(p: UserPreferences, vm: OnboardingViewModel) {
    val catalog by vm.libraryCatalog.collectAsStateWithLifecycle()
    BackHandler { vm.chooseLearnLanguage(null) }
    Page(2, stepsFor(p), stringResource(R.string.onboarding_learn_title), stringResource(R.string.onboarding_learn_body)) {
        if (catalog.isEmpty()) CircularProgressIndicator()
        catalog.toSortedMap().forEach { (lang, info) ->
            Surface(
                onClick = { vm.chooseLearnLanguage(lang) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Languages.name(lang), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${pluralStringResource(R.plurals.common_books, info.books, info.books)} · ${mb(info.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = { vm.chooseLearnLanguage(null) }) { Text(stringResource(R.string.common_not_now)) }
    }
}

@Composable
private fun NativeLanguageScreen(p: UserPreferences, vm: OnboardingViewModel) {
    // Por defecto el del móvil; si coincide con el que se aprende, se propone otro (traducir al mismo no sirve).
    val initial = remember {
        val device = vm.deviceLanguage
        if (p.readingLanguage != null && LanguageTags.sameLanguage(device, p.readingLanguage!!)) (if (device == "es") "en" else "es") else device
    }
    var target by rememberSaveable { mutableStateOf(initial) }
    var wifiOnly by rememberSaveable { mutableStateOf(p.translationWifiOnly) }
    BackHandler { vm.chooseNativeLanguage(target, wifiOnly) }
    Page(
        3, stepsFor(p), stringResource(R.string.onboarding_native_title),
        stringResource(R.string.onboarding_native_body),
    ) {
        var open by remember { mutableStateOf(false) }
        Box {
            AssistChip(onClick = { open = true }, label = { Text(Languages.name(target), style = MaterialTheme.typography.titleMedium) })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                vm.translationLanguages.sortedBy(Languages::name).forEach { code ->
                    DropdownMenuItem(text = { Text(Languages.name(code)) }, onClick = { target = code; open = false })
                }
            }
        }
        val same = p.readingLanguage?.let { LanguageTags.sameLanguage(it, target) } == true
        if (same) {
            Text(
                stringResource(R.string.onboarding_native_same),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            Modifier.fillMaxWidth().toggleable(value = wifiOnly, onValueChange = { wifiOnly = it }, role = Role.Checkbox),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = wifiOnly, onCheckedChange = null)
            Text(stringResource(R.string.onboarding_wifi_only), Modifier.padding(start = 8.dp))
        }
        Button(onClick = { vm.chooseNativeLanguage(target, wifiOnly) }, enabled = !same, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_continue))
        }
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
    Page(4, stepsFor(p), title, stringResource(R.string.onboarding_level_body)) {
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
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val documents by vm.documentCount.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val learns = p.purpose != AppPurpose.READ
    val reads = p.purpose != AppPurpose.LEARN
    val lang = p.readingLanguage.takeIf { learns }
    LaunchedEffect(lang) { lang?.let { vm.startDownloads(); vm.checkVoice(it) } }
    BackHandler { vm.finish() }

    Page(stepsFor(p), stepsFor(p), stringResource(R.string.onboarding_summary_title), stringResource(R.string.onboarding_summary_body)) {
        val retry = stringResource(R.string.common_retry)
        if (lang != null) {
            val info = catalog[lang]
            val libraryTitle = stringResource(R.string.onboarding_library_of, Languages.nameInSentence(lang))
            when (val st = libraryStates[lang] ?: LibraryState.NotInstalled) {
                is LibraryState.Installed -> Item(
                    R.drawable.ic_library_books, libraryTitle,
                    stringResource(R.string.onboarding_library_ready, pluralStringResource(R.plurals.common_books, st.books, st.books)), Status.DONE,
                )
                is LibraryState.Downloading -> Item(
                    R.drawable.ic_library_books, libraryTitle,
                    stringResource(R.string.common_downloading_progress, mb(st.bytes), mb(st.total)), Status.RUNNING,
                )
                is LibraryState.Installing -> Item(
                    R.drawable.ic_library_books, libraryTitle,
                    if (st.total == 0) stringResource(R.string.common_preparing) else stringResource(R.string.onboarding_preparing_books, st.done, st.total),
                    Status.RUNNING,
                )
                is LibraryState.Failed -> Item(R.drawable.ic_library_books, libraryTitle, st.message.asString(), Status.FAILED, retry) {
                    vm.retryLibrary(lang)
                }
                LibraryState.NotInstalled -> Item(
                    R.drawable.ic_library_books, libraryTitle,
                    if (info == null) stringResource(R.string.onboarding_no_library)
                    else stringResource(R.string.onboarding_library_pending, pluralStringResource(R.plurals.common_books, info.books, info.books)),
                    Status.PENDING,
                )
            }
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
            when (voice) {
                VoiceCheck.Checking -> Item(R.drawable.ic_record_voice_over, voiceTitle, stringResource(R.string.common_checking), Status.RUNNING)
                VoiceCheck.Ready -> Item(R.drawable.ic_record_voice_over, voiceTitle, stringResource(R.string.onboarding_voice_ready), Status.DONE)
                VoiceCheck.Missing -> Item(
                    R.drawable.ic_record_voice_over, voiceTitle,
                    stringResource(R.string.onboarding_voice_missing), Status.FAILED, stringResource(R.string.common_install),
                ) {
                    runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                VoiceCheck.NoEngine -> Item(
                    R.drawable.ic_record_voice_over, voiceTitle,
                    stringResource(R.string.onboarding_voice_no_engine), Status.FAILED,
                )
            }
        } else if (learns) {
            Item(
                R.drawable.ic_translate, stringResource(R.string.onboarding_language_to_learn),
                stringResource(R.string.onboarding_language_not_chosen), Status.PENDING,
            )
        }
        if (reads) {
            val documentsTitle = stringResource(R.string.onboarding_your_documents)
            when (val st = scanState) {
                is ScanState.Running -> Item(
                    R.drawable.ic_find_in_page, documentsTitle,
                    pluralStringResource(R.plurals.onboarding_searching_found, st.found, st.found), Status.RUNNING,
                )
                is ScanState.Finished -> Item(
                    R.drawable.ic_find_in_page, documentsTitle,
                    pluralStringResource(R.plurals.onboarding_found_on_phone, st.found, st.found), Status.DONE,
                )
                is ScanState.Failed -> Item(R.drawable.ic_find_in_page, documentsTitle, st.message.asString(), Status.FAILED)
                ScanState.Idle -> Item(
                    R.drawable.ic_find_in_page, documentsTitle,
                    when {
                        documents > 0 -> pluralStringResource(R.plurals.onboarding_found_on_phone, documents, documents)
                        StorageAccess.granted(context) -> stringResource(R.string.onboarding_scan_not_done)
                        else -> stringResource(R.string.onboarding_scan_no_permission)
                    },
                    if (documents > 0) Status.DONE else Status.PENDING,
                )
            }
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
