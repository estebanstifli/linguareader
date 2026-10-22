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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.LibraryInfo
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryState
import com.linguareader.ui.common.Languages
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class LibraryViewModel(
    private val libraries: LibraryPackManager,
) : ViewModel() {
    val catalog: StateFlow<Map<String, LibraryInfo>> = libraries.catalog
    val states: StateFlow<Map<String, LibraryState>> = libraries.states

    fun download(lang: String) = libraries.download(lang)
    fun delete(lang: String) = viewModelScope.launch { libraries.delete(lang) }
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)

@Composable
private fun summary(info: LibraryInfo) = "${pluralStringResource(R.plurals.common_books, info.books, info.books)} · ${mb(info.sizeBytes)}"

/** Ajustes → Bibliotecas: descargar, actualizar o borrar la biblioteca de cada idioma. */
@Composable
fun LibrarySection(vm: LibraryViewModel = koinViewModel()) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.libraries_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.libraries_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        if (catalog.isEmpty()) {
            Text(stringResource(R.string.libraries_none), style = MaterialTheme.typography.bodyMedium)
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
                    Text(stringResource(R.string.library_not_downloaded, summary(info)), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDownload) { Text(stringResource(R.string.common_download)) }
                }
                is LibraryState.Downloading -> LibraryProgress(state)
                is LibraryState.Installing -> LibraryProgress(state)
                is LibraryState.Installed -> {
                    Text(
                        if (state.updateAvailable) {
                            stringResource(
                                R.string.library_installed_update,
                                pluralStringResource(R.plurals.common_books, state.books, state.books),
                                pluralStringResource(R.plurals.common_books, info.books, info.books),
                            )
                        } else {
                            stringResource(R.string.library_installed, pluralStringResource(R.plurals.common_books, state.books, state.books))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.updateAvailable) Button(onClick = onDownload) { Text(stringResource(R.string.library_update)) }
                        OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.common_remove)) }
                    }
                }
                is LibraryState.Failed -> {
                    Text(state.message.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDownload) { Text(stringResource(R.string.common_retry)) }
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
            Text(stringResource(R.string.common_downloading_progress, mb(state.bytes), mb(state.total)), style = MaterialTheme.typography.bodySmall)
        }
        is LibraryState.Installing -> {
            if (state.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            Text(
                if (state.total == 0) stringResource(R.string.model_unpacking) else stringResource(R.string.onboarding_preparing_books, state.done, state.total),
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
fun LibraryDownloadBanner(vm: LibraryViewModel = koinViewModel()) {
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
            Text(stringResource(R.string.onboarding_library_of, Languages.nameInSentence(lang)), style = MaterialTheme.typography.titleSmall)
            if (state is LibraryState.Failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.message.asString(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.download(lang) }) { Text(stringResource(R.string.common_retry)) }
                }
            } else {
                LibraryProgress(state)
            }
        }
    }
}
