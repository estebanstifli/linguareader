package com.linguareader.ui.settings

import com.linguareader.ui.common.toUiText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.alignment.AlignerModelSpec
import com.linguareader.engine.alignment.AlignmentModelManager
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.ui.common.Languages
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class WordAlignmentViewModel(
    repo: UserPreferencesRepository,
    private val manager: AlignmentModelManager,
) : ViewModel() {
    val preferences: StateFlow<UserPreferences> = repo.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())
    val states: StateFlow<Map<String, ModelState>> = manager.states
    val catalog: List<AlignerModelSpec> get() = manager.catalog
    val hostingConfigured: Boolean get() = manager.hostingConfigured

    init { manager.refresh() }

    fun download(id: String) = manager.download(id)
    fun cancel(id: String) = manager.cancel(id)
    fun delete(id: String) = viewModelScope.launch { manager.delete(id) }
}

private fun mb(bytes: Long) = "%d MB".format(Locale.ROOT, bytes / 1_000_000)

/**
 * Modelos opcionales de sincronía por palabra para voces generadas (Supertonic), uno por idioma de
 * lectura. Sin modelo, Supertonic sigue resaltando por frase.
 */
@Composable
fun WordAlignmentSection(vm: WordAlignmentViewModel = koinViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val reading = prefs.readingLanguage?.let(LanguageTags::primary)
    // El del idioma de lectura actual primero.
    val models = vm.catalog.sortedByDescending { it.language == reading }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.alignment_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.alignment_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!vm.hostingConfigured) {
            Text(
                stringResource(R.string.model_repo_not_configured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        models.forEach { spec ->
            val state = states[spec.id] ?: ModelState.NotInstalled(spec.totalBytes, 0)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (spec.language == reading) stringResource(R.string.alignment_current_language, Languages.name(spec.language)) else Languages.name(spec.language),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.alignment_model_license, spec.name, spec.license), style = MaterialTheme.typography.bodySmall)
                    if (spec.nonCommercial) {
                        Text(stringResource(R.string.alignment_non_commercial), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (val m = state) {
                        is ModelState.NotInstalled -> {
                            Text(
                                if (m.partialBytes > 0) stringResource(R.string.model_not_installed_partial, mb(m.totalBytes), mb(m.partialBytes))
                                else stringResource(R.string.model_not_installed, mb(m.totalBytes)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { vm.download(spec.id) }, enabled = vm.hostingConfigured) { Text(stringResource(R.string.model_download_size, mb(m.totalBytes - m.partialBytes))) }
                        }
                        is ModelState.Downloading -> {
                            LinearProgressIndicator(progress = { m.downloadedBytes.toFloat() / m.totalBytes.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.model_progress, mb(m.downloadedBytes), mb(m.totalBytes)), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { vm.cancel(spec.id) }) { Text(stringResource(R.string.common_cancel)) }
                        }
                        is ModelState.Verifying -> Text(stringResource(R.string.model_verifying), style = MaterialTheme.typography.bodySmall)
                        is ModelState.Installed -> {
                            Text(stringResource(R.string.model_installed_on_disk, mb(m.bytesOnDisk)), style = MaterialTheme.typography.bodyMedium)
                            Row { OutlinedButton(onClick = { scope.launch { vm.delete(spec.id).join() } }) { Text(stringResource(R.string.common_remove)) } }
                        }
                        is ModelState.Failed -> {
                            Text(m.error.toUiText().asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.download(spec.id) }, enabled = vm.hostingConfigured) { Text(stringResource(R.string.common_retry)) }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(
                R.string.alignment_footer,
                vm.catalog.joinToString { it.source },
                vm.catalog.map { it.license }.distinct().joinToString(", "),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
