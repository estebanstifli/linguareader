package com.linguareader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.PackSource
import com.linguareader.engine.packs.VoicePackManager
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.ui.common.Languages
import com.linguareader.ui.common.toUiText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

class PacksViewModel(
    repo: UserPreferencesRepository,
    private val voicePacks: VoicePackManager,
    val source: PackSource,
) : ViewModel() {
    /** Paquete de voces por idioma. */
    val voicePackStates: StateFlow<Map<String, ModelState>> = voicePacks.states
    val readingLanguage: StateFlow<String?> = repo.preferences.map { it.readingLanguage }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun downloadVoices(lang: String) = voicePacks.download(lang)
    fun deleteVoices(lang: String) = viewModelScope.launch { voicePacks.delete(lang) }
    fun deleteAllVoices() = viewModelScope.launch { voicePacks.deleteAll() }
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)

/**
 * Origen de los paquetes descargables (voces pregeneradas por idioma, modelos de alineación, bibliotecas) y el
 * estado de las voces de cada idioma.
 */
@Composable
fun PacksSection(vm: PacksViewModel = koinViewModel()) {
    val packs by vm.voicePackStates.collectAsStateWithLifecycle()
    val reading by vm.readingLanguage.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.packs_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.packs_intro, vm.source.host), style = MaterialTheme.typography.bodySmall)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.packs_voices_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.packs_voices_detail), style = MaterialTheme.typography.bodySmall)
                val installed = packs.filterValues { it is ModelState.Installed }
                val active = packs.filterValues { it is ModelState.Downloading || it is ModelState.Verifying || it is ModelState.Failed }
                val shown = languagesFirstReading((installed.keys + active.keys).toList(), reading)
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.packs_voices_none), style = MaterialTheme.typography.bodyMedium)
                }
                shown.forEach { lang -> VoicePackRow(lang, packs.getValue(lang), vm) }
                if (installed.size > 1) {
                    TextButton(onClick = vm::deleteAllVoices) { Text(stringResource(R.string.packs_voices_delete_all)) }
                }
            }
        }
    }
}

@Composable
private fun VoicePackRow(lang: String, state: ModelState, vm: PacksViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(Languages.name(lang), style = MaterialTheme.typography.bodyLarge)
            when (state) {
                is ModelState.Installed -> Text(stringResource(R.string.model_installed, mb(state.bytesOnDisk)), style = MaterialTheme.typography.bodySmall)
                is ModelState.Downloading -> {
                    LinearProgressIndicator(progress = { state.downloadedBytes.toFloat() / state.totalBytes.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.model_progress, mb(state.downloadedBytes), mb(state.totalBytes)), style = MaterialTheme.typography.bodySmall)
                }
                is ModelState.Verifying -> {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.model_unpacking), style = MaterialTheme.typography.bodySmall)
                }
                is ModelState.Failed -> Text(state.error.toUiText().asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                is ModelState.NotInstalled -> Text(stringResource(R.string.model_not_installed, mb(state.totalBytes)), style = MaterialTheme.typography.bodySmall)
            }
        }
        when (state) {
            is ModelState.Installed -> OutlinedButton(onClick = { vm.deleteVoices(lang) }) { Text(stringResource(R.string.common_remove)) }
            is ModelState.Failed, is ModelState.NotInstalled -> OutlinedButton(onClick = { vm.downloadVoices(lang) }) { Text(stringResource(R.string.common_retry)) }
            else -> Unit
        }
    }
}
