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

/** Sección de Ajustes: idioma nativo (al que se traduce), modelos descargados y política de descarga. */
@Composable
fun TranslationSection(vm: TranslationViewModel = hiltViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val current = prefs ?: return
    val target = vm.code(current.translationLanguage) ?: current.translationLanguage
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tu idioma", style = MaterialTheme.typography.labelLarge)
            LanguagePicker(target, vm.languages) { vm.setTarget(it) }
        }
        Text(
            "Tu idioma nativo: las palabras y frases se traducen a él. Por defecto es el del móvil. " +
                "La traducción se hace en el propio dispositivo con Google ML Kit y funciona sin conexión " +
                "una vez descargado el modelo de cada idioma (≈40 MB).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelRow(target, target in downloaded, downloads[target], vm)
        WifiOnlyRow(current.translationWifiOnly, vm::setWifiOnly)

        val others = downloaded.filter { it != target }.sortedBy(::languageName)
        if (others.isNotEmpty()) {
            Text("Otros idiomas descargados", style = MaterialTheme.typography.labelLarge)
            others.forEach { code -> ModelRow(code, true, downloads[code], vm) }
        }
        val reading = current.readingLanguage?.let(vm::code)
        if (reading != null && reading != target && reading !in downloaded) {
            Text("Idioma que aprendes", style = MaterialTheme.typography.labelLarge)
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
