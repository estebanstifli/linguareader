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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.linguareader.R
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
    onViewOriginal: (android.net.Uri) -> Unit = {},
) {
    when (state) {
        ImportUiState.Idle, is ImportUiState.Done -> Unit
        is ImportUiState.Reading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.import_title_importing)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Text(state.label.asString())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
        is ImportUiState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_failed_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.message.asString())
                    state.detail?.let {
                        Text(
                            stringResource(R.string.common_technical_detail, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.viewUri != null) Text(stringResource(R.string.import_failed_can_view))
                }
            },
            confirmButton = {
                val uri = state.viewUri
                if (uri != null) {
                    TextButton(onClick = { onDismiss(); onViewOriginal(uri) }) { Text(stringResource(R.string.import_view_pdf)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
            },
            dismissButton = state.viewUri?.let { { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } } },
        )
        is ImportUiState.Review -> AlertDialog(
            onDismissRequest = { if (!state.saving) onDismiss() },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.catalog_import_book)) },
            text = { ReviewForm(state, onEdit) },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = !state.saving) {
                    Text(stringResource(if (state.saving) R.string.common_saving else R.string.common_import))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.common_cancel)) } },
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
            listOf(
                state.draft.sourceName,
                document.format.uppercase(),
                pluralStringResource(R.plurals.import_chapters, document.chapters.size, document.chapters.size),
                pluralStringResource(R.plurals.import_paragraphs, document.paragraphCount, document.paragraphCount),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.draft.alreadyImported) {
            Text(stringResource(R.string.import_already_imported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = state.title,
            onValueChange = { v -> onEdit { it.copy(title = v) } },
            label = { Text(stringResource(R.string.book_edit_field_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.author,
            onValueChange = { v -> onEdit { it.copy(author = v) } },
            label = { Text(stringResource(R.string.book_edit_field_author)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.import_text_language), style = MaterialTheme.typography.labelLarge)
        if (state.draft.detectedLanguage == null) {
            Text(
                stringResource(R.string.import_language_unknown),
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
            Text(stringResource(R.string.import_translation_into, Languages.nameInSentence(state.translationTarget)), style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                selected = !state.translateNow,
                title = stringResource(R.string.import_translate_while_reading),
                detail = stringResource(R.string.import_translate_while_reading_detail),
                onClick = { onEdit { it.copy(translateNow = false) } },
            )
            ChoiceRow(
                selected = state.translateNow,
                title = stringResource(R.string.import_translate_now),
                detail = pluralStringResource(R.plurals.import_translate_now_detail, document.paragraphCount, document.paragraphCount),
                onClick = { onEdit { it.copy(translateNow = true) } },
            )
        }
        Text(stringResource(R.string.import_level_cefr), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CefrLevel.entries.forEach { level ->
                FilterChip(selected = state.level == level, onClick = { onEdit { it.copy(level = level) } }, label = { Text(level.name) })
            }
        }
    }
}
