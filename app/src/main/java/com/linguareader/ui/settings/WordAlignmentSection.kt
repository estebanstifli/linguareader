package com.linguareader.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.alignment.AlignerModelSpec
import com.linguareader.engine.alignment.AlignmentModelManager
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.ui.common.Languages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WordAlignmentViewModel @Inject constructor(
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
fun WordAlignmentSection(vm: WordAlignmentViewModel = hiltViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val reading = prefs.readingLanguage?.let(LanguageTags::primary)
    // El del idioma de lectura actual primero.
    val models = vm.catalog.sortedByDescending { it.language == reading }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sincronía por palabra (voz Supertonic)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Supertonic no entrega tiempos por palabra. Con un modelo de alineación instalado para el idioma de lectura, " +
                "la app alinea cada frase en segundo plano y resalta la palabra que suena. Sin modelo, o si la alineación no es fiable, " +
                "se resalta la frase entera. Opcional; se usa solo con la voz Supertonic.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!vm.hostingConfigured) {
            Text(
                "Esta compilación no tiene configurado el repositorio de paquetes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        models.forEach { spec ->
            val state = states[spec.id] ?: ModelState.NotInstalled(spec.totalBytes, 0)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        Languages.name(spec.language) + if (spec.language == reading) " · idioma de lectura actual" else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("${spec.name} · licencia ${spec.license}", style = MaterialTheme.typography.bodySmall)
                    if (spec.nonCommercial) {
                        Text("Licencia no comercial: solo para uso personal y no comercial.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (val m = state) {
                        is ModelState.NotInstalled -> {
                            Text(
                                "No instalado · ${mb(m.totalBytes)}" + if (m.partialBytes > 0) " (descargados ${mb(m.partialBytes)}, se reanudará)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { vm.download(spec.id) }, enabled = vm.hostingConfigured) { Text("Descargar (${mb(m.totalBytes - m.partialBytes)})") }
                        }
                        is ModelState.Downloading -> {
                            LinearProgressIndicator(progress = { m.downloadedBytes.toFloat() / m.totalBytes.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                            Text("${mb(m.downloadedBytes)} de ${mb(m.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { vm.cancel(spec.id) }) { Text("Cancelar") }
                        }
                        is ModelState.Verifying -> Text("Verificando…", style = MaterialTheme.typography.bodySmall)
                        is ModelState.Installed -> {
                            Text("Instalado · ${mb(m.bytesOnDisk)} en disco", style = MaterialTheme.typography.bodyMedium)
                            Row { OutlinedButton(onClick = { scope.launch { vm.delete(spec.id).join() } }) { Text("Eliminar") } }
                        }
                        is ModelState.Failed -> {
                            Text(m.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.download(spec.id) }, enabled = vm.hostingConfigured) { Text("Reintentar") }
                        }
                    }
                }
            }
        }
        Text(
            "Los modelos son redes wav2vec2 de Meta (${vm.catalog.joinToString { it.source }}), convertidas a ONNX y " +
                "cuantizadas a int8 con el script del proyecto. Licencias: ${vm.catalog.map { it.license }.distinct().joinToString(", ")}. " +
                "Se guardan en el almacenamiento de la app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
