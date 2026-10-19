package com.linguareader.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.LibraryInfo
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryState
import com.linguareader.ui.common.Languages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: UserPreferencesRepository,
    private val libraries: LibraryPackManager,
) : ViewModel() {
    val catalog: StateFlow<Map<String, LibraryInfo>> = libraries.catalog
    val states: StateFlow<Map<String, LibraryState>> = libraries.states

    fun download(lang: String) = libraries.download(lang)
    fun delete(lang: String) = viewModelScope.launch { libraries.delete(lang) }

    /**
     * Primera apertura en modo aprendizaje: [lang] pasa a ser el idioma de lectura y, si [download],
     * se baja su biblioteca en segundo plano. null = «Ahora no».
     */
    fun chooseLearningLanguage(lang: String?, download: Boolean) = viewModelScope.launch {
        repo.update { p ->
            val target = if (lang != null && p.translationLanguage == lang) {
                // Traducir al mismo idioma que se aprende no sirve: se propone otro.
                if (lang == "es") "en" else "es"
            } else p.translationLanguage
            p.copy(learnLanguageChosen = true, readingLanguage = lang ?: p.readingLanguage, translationLanguage = target)
        }
        if (lang != null && download) libraries.download(lang)
    }
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)

private fun summary(info: LibraryInfo) = "${info.books} libros · ${mb(info.sizeBytes)}"

/** Ajustes → Bibliotecas: descargar, actualizar o borrar la biblioteca de cada idioma. */
@Composable
fun LibrarySection(vm: LibraryViewModel = hiltViewModel()) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bibliotecas de lecturas graduadas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Libros de los niveles A1 a B2 adaptados para aprender cada idioma, con portada y glosario. " +
                "Se descargan una vez y se leen sin conexión.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (catalog.isEmpty()) {
            Text("No hay bibliotecas disponibles.", style = MaterialTheme.typography.bodyMedium)
        }
        catalog.toSortedMap().forEach { (lang, info) ->
            LibraryRow(lang, info, states[lang] ?: LibraryState.NotInstalled, onDownload = { vm.download(lang) }, onDelete = { vm.delete(lang) })
        }
    }
}

@Composable
private fun LibraryRow(lang: String, info: LibraryInfo, state: LibraryState, onDownload: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Languages.name(lang), style = MaterialTheme.typography.titleSmall)
            when (state) {
                LibraryState.NotInstalled -> {
                    Text("No descargada · ${summary(info)}", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDownload) { Text("Descargar") }
                }
                is LibraryState.Downloading -> LibraryProgress(state)
                is LibraryState.Installing -> LibraryProgress(state)
                is LibraryState.Installed -> {
                    Text(
                        "Instalada · ${state.books} libros" + if (state.updateAvailable) " · hay una versión nueva (${info.books} libros)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.updateAvailable) Button(onClick = onDownload) { Text("Actualizar") }
                        OutlinedButton(onClick = onDelete) { Text("Eliminar") }
                    }
                }
                is LibraryState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDownload) { Text("Reintentar") }
                }
            }
        }
    }
}

@Composable
private fun LibraryProgress(state: LibraryState) {
    when (state) {
        is LibraryState.Downloading -> {
            LinearProgressIndicator(progress = { state.bytes.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text("Descargando ${mb(state.bytes)} de ${mb(state.total)}", style = MaterialTheme.typography.bodySmall)
        }
        is LibraryState.Installing -> {
            if (state.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            Text(
                if (state.total == 0) "Descomprimiendo y verificando…" else "Preparando libros: ${state.done} de ${state.total}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        else -> Unit
    }
}

/**
 * Aviso en la biblioteca mientras se descarga o prepara una biblioteca de idioma (o si falló),
 * para que el usuario sepa que los libros están llegando.
 */
@Composable
fun LibraryDownloadBanner(vm: LibraryViewModel = hiltViewModel()) {
    val states by vm.states.collectAsStateWithLifecycle()
    val (lang, state) = states.entries.firstOrNull { (_, s) ->
        s is LibraryState.Downloading || s is LibraryState.Installing || s is LibraryState.Failed
    }?.toPair() ?: return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Biblioteca de ${Languages.nameInSpanish(lang)}", style = MaterialTheme.typography.titleSmall)
            if (state is LibraryState.Failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("No se pudo descargar: ${state.message}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.download(lang) }) { Text("Reintentar") }
                }
            } else {
                LibraryProgress(state)
            }
        }
    }
}

/** Primera apertura (modo aprendizaje): ¿qué idioma quieres aprender? Ofrece descargar su biblioteca. */
@Composable
fun LearnLanguageScreen(vm: LibraryViewModel = hiltViewModel()) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    BackHandler { vm.chooseLearningLanguage(null, download = false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("¿Qué idioma quieres aprender?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Descargamos su biblioteca de lecturas graduadas (de A1 a B2) para que puedas empezar a leer y escuchar " +
                "sin conexión. Puedes añadir otros idiomas en Ajustes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        catalog.toSortedMap().forEach { (lang, info) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { vm.chooseLearningLanguage(lang, download = true) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Aprender ${Languages.nameInSpanish(lang)}" },
                ) { Text(Languages.name(lang)) }
                Text(summary(info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { vm.chooseLearningLanguage(null, download = false) }) { Text("Ahora no") }
    }
}
