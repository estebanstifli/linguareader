package com.linguareader.ui.settings

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.GithubPackSource
import com.linguareader.engine.packs.VoicePackManager
import com.linguareader.engine.supertonic.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PacksViewModel @Inject constructor(
    private val repo: UserPreferencesRepository,
    private val voicePacks: VoicePackManager,
    val source: GithubPackSource,
) : ViewModel() {
    val token: StateFlow<String> = repo.packsToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val voicePack: StateFlow<ModelState> = voicePacks.state

    fun saveToken(value: String) = viewModelScope.launch { repo.setPacksToken(value) }
    fun downloadVoices() = voicePacks.download()
    fun deleteVoices() = viewModelScope.launch { voicePacks.delete() }
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)

/**
 * Origen de los paquetes descargables (voces pregeneradas y modelos de alineación): una release de
 * GitHub. Si el repositorio es privado, hace falta un token de solo lectura que se guarda solo en el móvil.
 */
@Composable
fun PacksSection(vm: PacksViewModel = hiltViewModel()) {
    val token by vm.token.collectAsStateWithLifecycle()
    val voices by vm.voicePack.collectAsStateWithLifecycle()
    var draft by remember(token) { mutableStateOf(token) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Descarga de paquetes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Voces pregeneradas y modelos de alineación se descargan de github.com/${vm.source.repo} (release ${vm.source.tag}). " +
                "Si el repositorio es privado, pega un token de GitHub con permiso de solo lectura (Contents: read). " +
                "Se guarda únicamente en este móvil y solo se envía a GitHub.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Token de GitHub (opcional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveToken(draft) }, enabled = draft.trim() != token) { Text("Guardar token") }
            if (token.isNotEmpty()) OutlinedButton(onClick = { draft = ""; vm.saveToken("") }) { Text("Borrar") }
        }

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Paquete de voces (Supertonic)", style = MaterialTheme.typography.titleSmall)
                Text("Muestras de cada voz y frases de «preparando la lectura» ya sintetizadas. Se descarga solo al instalar el modelo.", style = MaterialTheme.typography.bodySmall)
                when (val m = voices) {
                    is ModelState.NotInstalled -> {
                        Text("No instalado · ${mb(m.totalBytes)}", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = vm::downloadVoices) { Text("Descargar") }
                    }
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { m.downloadedBytes.toFloat() / m.totalBytes.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        Text("${mb(m.downloadedBytes)} de ${mb(m.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                    }
                    is ModelState.Verifying -> {
                        LinearProgressIndicator(progress = { m.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("Descomprimiendo y verificando…", style = MaterialTheme.typography.bodySmall)
                    }
                    is ModelState.Installed -> {
                        Text("Instalado · ${mb(m.bytesOnDisk)}", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { scope.launch { vm.deleteVoices().join() } }) { Text("Eliminar") }
                    }
                    is ModelState.Failed -> {
                        Text(m.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = vm::downloadVoices) { Text("Reintentar") }
                    }
                }
            }
        }
    }
}
