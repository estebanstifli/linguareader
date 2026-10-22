package com.linguareader.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linguareader.R
import com.linguareader.ui.common.Languages

/**
 * Tarjeta de búsqueda de la biblioteca (lupa de la cabecera). Los cambios se aplican al momento: la lista de detrás
 * se filtra mientras se escribe y [resultCount] dice cuántos libros quedan.
 *
 * @param languages idiomas del apartado; el filtro de idioma solo sale si hay más de uno.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogSearchCard(
    search: CatalogSearch,
    language: String?,
    languages: List<String>,
    resultCount: Int,
    onSearch: (CatalogSearch) -> Unit,
    onLanguage: (String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // El texto vive aquí: si fuera de ida y vuelta por el ViewModel (que filtra en otro hilo), cada pulsación llegaría
    // tarde y machacaría la anterior (letras perdidas, cursor fuera de sitio).
    var text by rememberSaveable { mutableStateOf(search.text) }
    val current = search.copy(text = text)
    LaunchedEffect(Unit) { focus.requestFocus() }
    // usePlatformDefaultWidth = false: la tarjeta ocupa el ancho del móvil, arriba, como un buscador.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; onSearch(current.copy(text = it)) },
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    trailingIcon = {
                        if (text.isNotEmpty()) {
                            IconButton(onClick = { text = ""; onSearch(current.copy(text = "")) }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear_text))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onDismiss() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )

                Group(stringResource(R.string.search_group_status)) {
                    ReadStatus.entries.forEach { st ->
                        FilterChip(selected = search.status == st, onClick = { onSearch(current.copy(status = st)) }, label = { Text(stringResource(st.label)) })
                    }
                }
                if (languages.size > 1) {
                    Group(stringResource(R.string.search_group_language)) {
                        FilterChip(selected = language == null, onClick = { onLanguage(null) }, label = { Text(stringResource(R.string.search_language_all)) })
                        languages.forEach { tag ->
                            FilterChip(selected = language == tag, onClick = { onLanguage(if (language == tag) null else tag) }, label = { Text(Languages.name(tag)) })
                        }
                    }
                }
                Group(stringResource(R.string.search_group_sort)) {
                    SortOrder.entries.forEach { order ->
                        FilterChip(selected = search.sort == order, onClick = { onSearch(current.copy(sort = order)) }, label = { Text(stringResource(order.label)) })
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { text = ""; onClear() }, enabled = current.isActive || language != null) { Text(stringResource(R.string.search_clear)) }
                    Row(Modifier.weight(1f)) {}
                    Button(onClick = onDismiss) {
                        Text(
                            if (resultCount == 0) stringResource(R.string.search_no_results)
                            else pluralStringResource(R.plurals.search_see_books, resultCount, resultCount),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

/** Línea bajo la cabecera con la búsqueda activa: tocarla la reabre; la ✕ la quita. */
@Composable
fun ActiveSearchBar(summary: String, onEdit: () -> Unit, onClear: () -> Unit) {
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = onClear) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_remove)) }
        }
    }
}

/** «"tolstoi" · Leyendo · Inglés · por título». */
@Composable
fun searchSummary(search: CatalogSearch, language: String?, resultCount: Int): String = listOfNotNull(
    search.text.trim().takeIf { it.isNotEmpty() }?.let { stringResource(R.string.common_quoted, it) },
    search.status.takeIf { it != ReadStatus.ALL }?.let { stringResource(it.label) },
    language?.let(Languages::name),
    search.sort.takeIf { it != SortOrder.DEFAULT }?.let { stringResource(R.string.search_sorted_by, stringResource(it.label).lowercase()) },
).joinToString(" · ") + " — $resultCount"
