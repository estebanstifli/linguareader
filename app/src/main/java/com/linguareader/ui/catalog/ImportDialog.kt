package com.linguareader.ui.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.linguareader.core.model.CefrLevel
import com.linguareader.ui.common.Languages

/** Diálogo de importación: progreso de lectura, revisión de metadatos y errores. */
@Composable
fun ImportDialog(
    state: ImportUiState,
    onEdit: ((ImportUiState.Review) -> ImportUiState.Review) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        ImportUiState.Idle, is ImportUiState.Done -> Unit
        is ImportUiState.Reading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Importando") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Text(state.label)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        )
        is ImportUiState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("No se pudo importar") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        )
        is ImportUiState.Review -> AlertDialog(
            onDismissRequest = { if (!state.saving) onDismiss() },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Importar libro") },
            text = { ReviewForm(state, onEdit) },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = !state.saving) { Text(if (state.saving) "Guardando…" else "Importar") }
            },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewForm(state: ImportUiState.Review, onEdit: ((ImportUiState.Review) -> ImportUiState.Review) -> Unit) {
    val document = state.draft.document
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${state.draft.sourceName} · ${document.format.uppercase()} · ${document.chapters.size} capítulos · ${document.paragraphCount} párrafos",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.draft.alreadyImported) {
            Text("Este fichero ya está en tu biblioteca: se reemplazará.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = state.title,
            onValueChange = { v -> onEdit { it.copy(title = v) } },
            label = { Text("Título") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.author,
            onValueChange = { v -> onEdit { it.copy(author = v) } },
            label = { Text("Autor") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Idioma del texto", style = MaterialTheme.typography.labelLarge)
        if (state.draft.detectedLanguage == null) {
            Text(
                "El documento no indica su idioma: elige el correcto para que la voz y la segmentación funcionen bien.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.languageOptions.forEach { tag ->
                FilterChip(
                    selected = state.language == tag,
                    onClick = { onEdit { it.copy(language = tag) } },
                    label = { Text(Languages.name(tag)) },
                )
            }
        }
        if (state.canTranslate) {
            Text("Traducción al ${Languages.nameInSpanish(state.translationTarget)}", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                selected = !state.translateNow,
                title = "Mientras leo",
                detail = "Se traduce cada pasaje al llegar a él. Puedes empezar a leer ya.",
                onClick = { onEdit { it.copy(translateNow = false) } },
            )
            ChoiceRow(
                selected = state.translateNow,
                title = "Traducir el libro entero ahora",
                detail = "En segundo plano y en el dispositivo; tarda según el tamaño " +
                    "(${document.paragraphCount} párrafos). Después se lee sin esperas ni conexión.",
                onClick = { onEdit { it.copy(translateNow = true) } },
            )
        }
        Text("Nivel (MCER)", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CefrLevel.entries.forEach { level ->
                FilterChip(selected = state.level == level, onClick = { onEdit { it.copy(level = level) } }, label = { Text(level.name) })
            }
        }
    }
}
