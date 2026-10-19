package com.linguareader.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.translation.ModelDownload
import com.linguareader.engine.translation.TranslationModelManager
import com.linguareader.ui.common.Languages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val repo: UserPreferencesRepository,
    private val models: TranslationModelManager,
) : ViewModel() {
    val preferences: StateFlow<UserPreferences?> = repo.preferences.map<UserPreferences, UserPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val downloaded: StateFlow<Set<String>> = models.downloaded
    val downloads: StateFlow<Map<String, ModelDownload>> = models.downloads
    val languages: List<String> get() = models.supportedLanguages
    val deviceLanguage: String get() = models.deviceLanguage()

    init { viewModelScope.launch { models.refresh() } }

    fun code(tag: String): String? = models.code(tag)

    fun download(code: String) = models.download(code)

    fun delete(code: String) = viewModelScope.launch { models.delete(code) }

    fun setTarget(code: String) = viewModelScope.launch { repo.update { it.copy(translationLanguage = code) } }

    fun setWifiOnly(value: Boolean) = viewModelScope.launch { repo.update { it.copy(translationWifiOnly = value) } }

    /** Primera apertura: con voz (aprender idiomas) o lector clásico. Se puede cambiar en Ajustes. */
    fun chooseMode(tts: Boolean) = viewModelScope.launch { repo.update { it.copy(ttsMode = tts, ttsModeChosen = true) } }

    /** Cierra la configuración inicial; si [download], descarga el modelo del idioma elegido en segundo plano. */
    fun finishSetup(target: String, wifiOnly: Boolean, download: Boolean) = viewModelScope.launch {
        repo.update { it.copy(translationLanguage = target, translationWifiOnly = wifiOnly, translationSetupDone = true) }
        if (download) models.download(target)
    }
}

private fun languageName(code: String) = Languages.name(code)

/** Selector de idioma de traducción (todos los que admite ML Kit). */
@Composable
private fun LanguagePicker(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(languageName(selected)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { code ->
                DropdownMenuItem(text = { Text(languageName(code)) }, onClick = { onSelect(code); open = false })
            }
        }
    }
}

@Composable
private fun WifiOnlyRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text("Descargar solo con Wi-Fi", Modifier.padding(start = 8.dp))
    }
}

/**
 * Configuración inicial (primera apertura): idioma de traducción, por defecto el del sistema, y
 * descarga de su modelo. Se puede omitir y cambiar después en Ajustes.
 */
@Composable
fun TranslationSetupGate(content: @Composable () -> Unit, vm: TranslationViewModel = hiltViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        content()
        val current = prefs ?: return@Box // aún cargando: no se muestra nada encima
        when {
            !current.ttsModeChosen -> ModeChoiceScreen(onChoose = vm::chooseMode)
            // Solo dentro del primer arranque (antes de configurar la traducción): quien ya usaba la app
            // no ve esta pantalla al actualizar; tiene las bibliotecas en Ajustes.
            current.ttsMode && !current.learnLanguageChosen && !current.translationSetupDone -> LearnLanguageScreen()
            !current.translationSetupDone -> TranslationSetupScreen(vm, current)
        }
    }
}

/** ¿Para qué se va a usar la app? Decide si el lector arranca con voz y controles de reproducción. */
@Composable
private fun ModeChoiceScreen(onChoose: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("¿Cómo vas a usar LinguaReader?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Puedes cambiarlo cuando quieras en Ajustes o con el altavoz de la barra superior del lector.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModeOption(
            title = "Aprender idiomas",
            body = "Lectura en voz alta con la palabra iluminada, controles de reproducción y traducción al mantener pulsada una palabra.",
            primary = true,
            onClick = { onChoose(true) },
        )
        ModeOption(
            title = "Solo leer",
            body = "Lector de libros clásico a pantalla completa, sin voz. La traducción al mantener pulsada una palabra sigue disponible.",
            primary = false,
            onClick = { onChoose(false) },
        )
    }
}

@Composable
private fun ModeOption(title: String, body: String, primary: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (primary) Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(title) }
        else OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(title) }
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TranslationSetupScreen(vm: TranslationViewModel, prefs: UserPreferences) {
    var target by remember { mutableStateOf(vm.deviceLanguage) }
    var wifiOnly by remember { mutableStateOf(prefs.translationWifiOnly) }
    BackHandler { vm.finishSetup(target, wifiOnly, download = false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Traducción en tu móvil", style = MaterialTheme.typography.headlineSmall)
        Text(
            "LinguaReader traduce palabras, frases y libros enteros sin conexión con el traductor de Google ML Kit, " +
                "que funciona en el propio dispositivo. Cada idioma necesita un modelo de unos 40 MB que se descarga una vez.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Los libros incluidos ya traen traducciones revisadas; el traductor automático se usa para tus libros importados " +
                "y para lo que no esté incluido.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Traducir al", style = MaterialTheme.typography.titleMedium)
            LanguagePicker(target, vm.languages) { target = it }
        }
        WifiOnlyRow(wifiOnly) { wifiOnly = it }
        Text(
            "Para traducir entre dos idiomas hace falta el modelo de cada uno (el del libro y el de traducción): " +
                "se te ofrecerá descargar el que falte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { vm.finishSetup(target, wifiOnly, download = true) }, modifier = Modifier.fillMaxWidth()) {
            Text("Descargar (≈40 MB) y empezar")
        }
        TextButton(onClick = { vm.finishSetup(target, wifiOnly, download = false) }, modifier = Modifier.fillMaxWidth()) {
            Text("Ahora no")
        }
    }
}

/** Sección de Ajustes: idioma de traducción, modelos descargados y política de descarga. */
@Composable
fun TranslationSection(vm: TranslationViewModel = hiltViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val current = prefs ?: return
    val target = vm.code(current.translationLanguage) ?: current.translationLanguage
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Traducción automática (ML Kit)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Se usa cuando el libro no incluye la traducción: libros importados, palabras fuera del glosario. " +
                "Funciona sin conexión una vez descargado el modelo de cada idioma (≈40 MB por idioma).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Traducir al", style = MaterialTheme.typography.labelLarge)
            LanguagePicker(target, vm.languages) { vm.setTarget(it) }
        }
        ModelRow(target, target in downloaded, downloads[target], vm)
        WifiOnlyRow(current.translationWifiOnly, vm::setWifiOnly)

        val others = downloaded.filter { it != target }.sortedBy(::languageName)
        if (others.isNotEmpty()) {
            Text("Otros idiomas descargados", style = MaterialTheme.typography.labelLarge)
            others.forEach { code -> ModelRow(code, true, downloads[code], vm) }
        }
        val reading = current.readingLanguage?.let(vm::code)
        if (reading != null && reading != target && reading !in downloaded) {
            Text("Idioma de lectura actual", style = MaterialTheme.typography.labelLarge)
            ModelRow(reading, false, downloads[reading], vm)
        }
    }
}

@Composable
private fun ModelRow(code: String, installed: Boolean, download: ModelDownload?, vm: TranslationViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${languageName(code)} — ${if (installed) "descargado" else "no descargado"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            when {
                download == ModelDownload.Running -> Unit
                installed && !LanguageTags.sameLanguage(code, "en") -> TextButton(onClick = { vm.delete(code) }) { Text("Borrar") }
                !installed -> OutlinedButton(onClick = { vm.download(code) }) { Text("Descargar") }
            }
        }
        when (download) {
            ModelDownload.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ModelDownload.Failed -> Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
    }
}
