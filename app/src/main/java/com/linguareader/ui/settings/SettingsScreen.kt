package com.linguareader.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.linguareader.core.model.TextJustification
import com.linguareader.data.db.DeviceDocumentDao
import com.linguareader.engine.supertonic.SupertonicModel
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.HighlightStyle
import com.linguareader.core.model.ThemeMode
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.ui.common.Labels
import com.linguareader.ui.common.Languages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: UserPreferencesRepository,
    documents: DeviceDocumentDao,
) : ViewModel() {
    val preferences: StateFlow<UserPreferences> = repo.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())
    /** Documentos encontrados al examinar archivos (resumen del índice). */
    val documentCount: StateFlow<Int> = documents.observeAll().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun update(transform: (UserPreferences) -> UserPreferences) = viewModelScope.launch { repo.update(transform) }
}

/** Subpantallas de Ajustes, una por tema. */
enum class SettingsPage(val title: String, val icon: Int) {
    LANGUAGE("Idioma y traducción", R.drawable.ic_translate),
    LIBRARIES("Bibliotecas", R.drawable.ic_library_books),
    FILES("Examinar archivos", R.drawable.ic_find_in_page),
    READING("Lectura", R.drawable.ic_menu_book),
    APPEARANCE("Texto y tema", R.drawable.ic_palette),
    VOICE("Voz y audio", R.drawable.ic_record_voice_over),
    ABOUT("Acerca de", R.drawable.ic_info),
}

/**
 * Ajustes: un índice por temas y una subpantalla por tema. Con [initialPage] se abre directamente esa subpantalla
 * (p. ej. «Voz y audio» desde un aviso del lector) y «atrás» sale de Ajustes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDocuments: () -> Unit = {},
    initialPage: SettingsPage? = null,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val documentCount by vm.documentCount.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val backToIndex = page != null && initialPage == null
    BackHandler(enabled = backToIndex) { page = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "Ajustes") },
                navigationIcon = {
                    IconButton(onClick = { if (backToIndex) page = null else onBack() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        val current = page
        if (current == null) {
            SettingsIndex(prefs, documentCount, Modifier.padding(padding)) { page = it }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsPageContent(current, prefs, onUpdate = { t -> vm.update(t) }, onOpenDocuments = onOpenDocuments)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsIndex(prefs: UserPreferences, documentCount: Int, modifier: Modifier, onOpen: (SettingsPage) -> Unit) {
    fun summary(page: SettingsPage): String = when (page) {
        SettingsPage.LANGUAGE -> "Tu idioma: ${Languages.name(prefs.translationLanguage)} · traducción sin conexión"
        SettingsPage.LIBRARIES -> "Lecturas graduadas A1–B2 para descargar"
        SettingsPage.FILES -> if (documentCount > 0) "$documentCount documentos encontrados en el móvil" else "Buscar libros y documentos en el móvil"
        SettingsPage.READING -> "${Labels.displayMode(prefs.displayMode)} · resaltado ${Labels.highlight(prefs.highlightStyle).lowercase()}"
        SettingsPage.APPEARANCE -> "Tema ${Labels.theme(prefs.theme).lowercase()} · texto al ${(prefs.fontScale * 100).toInt()} %"
        SettingsPage.VOICE -> when {
            !prefs.ttsMode -> "Lectura en voz alta desactivada"
            prefs.ttsEngineId == UserPreferences.ANDROID_TTS_ENGINE -> "Voz del sistema (Android)"
            else -> SupertonicModel.DISPLAY_NAME
        }
        SettingsPage.ABOUT -> "Versión 0.2.0 y licencias"
    }
    val groups = listOf(
        "Contenido" to listOf(SettingsPage.LANGUAGE, SettingsPage.LIBRARIES, SettingsPage.FILES),
        "Lectura" to listOf(SettingsPage.READING, SettingsPage.APPEARANCE, SettingsPage.VOICE),
        "Aplicación" to listOf(SettingsPage.ABOUT),
    )
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        groups.forEach { (title, pages) ->
            item(key = title) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(pages, key = { it.name }) { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    IconBadge(p.icon)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        Text(summary(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsPageContent(
    page: SettingsPage,
    prefs: UserPreferences,
    onUpdate: ((UserPreferences) -> UserPreferences) -> Unit,
    onOpenDocuments: () -> Unit,
) {
    val context = LocalContext.current
    when (page) {
        SettingsPage.LANGUAGE -> TranslationSection()
        SettingsPage.LIBRARIES -> LibrarySection()
        SettingsPage.FILES -> ScanFilesSection(onOpenDocuments = onOpenDocuments)
        SettingsPage.READING -> ReadingOptions(prefs, onUpdate)
        SettingsPage.APPEARANCE -> AppearanceOptions(prefs, onUpdate)
        SettingsPage.VOICE -> {
            VoiceOptions(prefs, onUpdate)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TtsEngineSection()
            WordAlignmentSection()
            PacksSection()
            Text(
                "Voces del sistema: se instalan desde los ajustes de Android; se prefieren voces sin conexión.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Abrir ajustes de texto a voz") }
        }
        SettingsPage.ABOUT -> Text(
            "LinguaReader 0.2.0 · Biblioteca local sin conexión. " +
                "Iconos: Material Icons (Apache 2.0). Ver docs/ARCHITECTURE.md para licencias.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Preferencias de lectura compartidas entre la pantalla de ajustes y la hoja del lector. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPreferencesPanel(
    prefs: UserPreferences,
    onUpdate: ((UserPreferences) -> UserPreferences) -> Unit,
    voices: List<VoiceInfo>,
    selectedVoice: String?,
    onVoice: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppearanceOptions(prefs, onUpdate)
        ReadingOptions(prefs, onUpdate)
        VoiceOptions(prefs, onUpdate)

        SectionTitle("Traducir al")
        ChipRow(Languages.translationTargets, prefs.translationLanguage, Languages::name) { tag ->
            onUpdate { it.copy(translationLanguage = tag) }
        }

        if (voices.isNotEmpty()) {
            SectionTitle("Voz")
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(voices.firstOrNull { it.id == selectedVoice }?.displayName ?: "Automática (sin conexión si existe)")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Automática (sin conexión si existe)") }, onClick = { onVoice(null); open = false })
                    voices.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v.displayName + if (v.requiresNetwork) " · requiere red" else "") },
                            onClick = { onVoice(v.id); open = false },
                        )
                    }
                }
            }
        }
    }
}

/** Lectura: modo de visualización, resaltado y comportamiento del lector. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingOptions(prefs: UserPreferences, onUpdate: ((UserPreferences) -> UserPreferences) -> Unit) {
    SectionTitle("Modo de visualización")
    DisplayMode.entries.forEach { mode ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().selectable(
                selected = prefs.displayMode == mode,
                onClick = { onUpdate { it.copy(displayMode = mode) } },
                role = Role.RadioButton,
            ),
        ) {
            RadioButton(selected = prefs.displayMode == mode, onClick = null)
            Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                Text(Labels.displayMode(mode), style = MaterialTheme.typography.bodyLarge)
                Text(Labels.displayModeHint(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    SectionTitle("Resaltado")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        HighlightStyle.entries.forEachIndexed { i, style ->
            SegmentedButton(
                selected = prefs.highlightStyle == style,
                onClick = { onUpdate { it.copy(highlightStyle = style) } },
                shape = SegmentedButtonDefaults.itemShape(i, HighlightStyle.entries.size),
            ) { Text(Labels.highlight(style)) }
        }
    }

    SectionTitle("Al leer")
    SwitchRow("Dedo indicador sobre la palabra", prefs.fingerPointerEnabled) { v -> onUpdate { it.copy(fingerPointerEnabled = v) } }
    SwitchRow("Pasar página automáticamente al leer en voz alta", prefs.autoScroll) { v -> onUpdate { it.copy(autoScroll = v) } }
    SwitchRow("Mostrar ilustraciones", prefs.showIllustrations) { v -> onUpdate { it.copy(showIllustrations = v) } }
    SwitchRow("No apagar la pantalla mientras leo", prefs.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
    SwitchRow("Pausar al consultar una palabra", prefs.pauseOnWordTap) { v -> onUpdate { it.copy(pauseOnWordTap = v) } }
    SwitchRow("Continuar con el siguiente pasaje", prefs.autoContinue) { v -> onUpdate { it.copy(autoContinue = v) } }
    SwitchRow("Reducir movimiento", prefs.reduceMotion) { v -> onUpdate { it.copy(reduceMotion = v) } }
}

/** Texto y tema: tema de color, tamaño, interlineado y alineación. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceOptions(prefs: UserPreferences, onUpdate: ((UserPreferences) -> UserPreferences) -> Unit) {
    SectionTitle("Tema")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = prefs.theme == mode,
                onClick = { onUpdate { it.copy(theme = mode) } },
                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
            ) { Text(Labels.theme(mode)) }
        }
    }

    SectionTitle("Texto")
    LabeledSlider("Tamaño", prefs.fontScale, 0.8f..1.8f, "%.0f%%".format(Locale.ROOT, prefs.fontScale * 100)) { v ->
        onUpdate { it.copy(fontScale = v) }
    }
    LabeledSlider("Interlineado", prefs.lineHeight, 1.2f..2.2f, "%.1f".format(Locale.ROOT, prefs.lineHeight)) { v ->
        onUpdate { it.copy(lineHeight = v) }
    }
    Text("Alineación", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        TextJustification.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = prefs.textJustification == mode,
                onClick = { onUpdate { it.copy(textJustification = mode) } },
                shape = SegmentedButtonDefaults.itemShape(i, TextJustification.entries.size),
            ) { Text(Labels.justification(mode), maxLines = 1) }
        }
    }
}

/** Voz y audio: lectura en voz alta, pantalla apagada, pausas y normalización del texto. */
@Composable
private fun VoiceOptions(prefs: UserPreferences, onUpdate: ((UserPreferences) -> UserPreferences) -> Unit) {
    SwitchRow("Modo aprendizaje con voz (TTS)", prefs.ttsMode) { v -> onUpdate { it.copy(ttsMode = v, ttsModeChosen = true) } }
    SwitchRow("Seguir leyendo con la pantalla apagada", prefs.readWithScreenOff) { v -> onUpdate { it.copy(readWithScreenOff = v) } }

    SectionTitle("Pausas de lectura")
    LabeledSlider("Entre frases", prefs.sentencePauseMs / 1000f, 0.2f..1.5f, "%.1f s".format(Locale.ROOT, prefs.sentencePauseMs / 1000f)) { v ->
        onUpdate { it.copy(sentencePauseMs = (v * 1000).toInt() / 50 * 50) }
    }
    LabeledSlider("Entre párrafos", prefs.paragraphPauseMs / 1000f, 0.3f..3.0f, "%.1f s".format(Locale.ROOT, prefs.paragraphPauseMs / 1000f)) { v ->
        onUpdate { it.copy(paragraphPauseMs = (v * 1000).toInt() / 50 * 50) }
    }
    SwitchRow("Leer números y abreviaturas como palabras (voz Supertonic)", prefs.normalizeSpeech) { v -> onUpdate { it.copy(normalizeSpeech = v) } }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange, role = Role.Switch).padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row { Text(label, Modifier.weight(1f)); Text(valueLabel) }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range)
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}
