package com.linguareader.ui.settings

import com.linguareader.ui.common.toUiText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.GithubPackSource
import com.linguareader.engine.packs.VoicePackManager
import com.linguareader.engine.supertonic.ModelState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class PacksViewModel(
    private val repo: UserPreferencesRepository,
    private val voicePacks: VoicePackManager,
    val source: GithubPackSource,
) : ViewModel() {
    val voicePack: StateFlow<ModelState> = voicePacks.state

    fun downloadVoices() = voicePacks.download()
    fun deleteVoices() = viewModelScope.launch { voicePacks.delete() }
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)

/**
 * Origen de los paquetes descargables (voces pregeneradas y modelos de alineación): una release de
 * GitHub (público).
 */
@Composable
fun PacksSection(vm: PacksViewModel = koinViewModel()) {
    val voices by vm.voicePack.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.packs_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.packs_intro, vm.source.repo, vm.source.tag),
            style = MaterialTheme.typography.bodySmall,
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.packs_voices_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.packs_voices_detail), style = MaterialTheme.typography.bodySmall)
                when (val m = voices) {
                    is ModelState.NotInstalled -> {
                        Text(stringResource(R.string.model_not_installed, mb(m.totalBytes)), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = vm::downloadVoices) { Text(stringResource(R.string.common_download)) }
                    }
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { m.downloadedBytes.toFloat() / m.totalBytes.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.model_progress, mb(m.downloadedBytes), mb(m.totalBytes)), style = MaterialTheme.typography.bodySmall)
                    }
                    is ModelState.Verifying -> {
                        LinearProgressIndicator(progress = { m.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.model_unpacking), style = MaterialTheme.typography.bodySmall)
                    }
                    is ModelState.Installed -> {
                        Text(stringResource(R.string.model_installed, mb(m.bytesOnDisk)), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { scope.launch { vm.deleteVoices().join() } }) { Text(stringResource(R.string.common_remove)) }
                    }
                    is ModelState.Failed -> {
                        Text(m.error.toUiText().asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = vm::downloadVoices) { Text(stringResource(R.string.common_retry)) }
                    }
                }
            }
        }
    }
}
