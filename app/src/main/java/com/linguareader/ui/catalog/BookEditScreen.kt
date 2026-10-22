package com.linguareader.ui.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.linguareader.R
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.data.content.CompositeBookRepository
import com.linguareader.data.library.LibraryRepository
import com.linguareader.ui.common.Languages
import com.linguareader.ui.navigation.BookEditRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Datos editables de un libro, tal como se ven ahora (con las correcciones anteriores aplicadas). */
data class BookEditForm(
    val title: String,
    val author: String,
    val language: String,
    val level: CefrLevel,
    /** Ya tiene correcciones: se ofrece volver a los datos originales. */
    val edited: Boolean,
)

sealed interface BookEditState {
    data object Loading : BookEditState
    data class Ready(val form: BookEditForm) : BookEditState
    data object Failed : BookEditState
    /** Guardado o restaurado: la pantalla se cierra. */
    data object Done : BookEditState
}

class BookEditViewModel(
    savedState: SavedStateHandle,
    private val books: CompositeBookRepository,
    private val library: LibraryRepository,
) : ViewModel() {
    private val bookId = savedState.toRoute<BookEditRoute>().bookId
    private val _state = MutableStateFlow<BookEditState>(BookEditState.Loading)
    val state: StateFlow<BookEditState> = _state

    init {
        viewModelScope.launch {
            _state.value = runCatching {
                // El resumen basta: no hace falta abrir un libro largo entero ni esperar al catálogo completo.
                val book = books.loadSummary(bookId) ?: error("Libro no encontrado: $bookId")
                BookEditState.Ready(
                    BookEditForm(
                        title = book.title,
                        author = book.authors.joinToString(", "),
                        language = LanguageTags.primary(book.learningLanguage),
                        level = book.cefrLevel,
                        edited = library.hasEdits(bookId),
                    ),
                )
            }.getOrElse { BookEditState.Failed }
        }
    }

    fun save(form: BookEditForm) = viewModelScope.launch {
        library.editBook(bookId, form.title, form.author, form.language, form.level.name)
        _state.value = BookEditState.Done
    }

    fun reset() = viewModelScope.launch {
        library.resetBook(bookId)
        _state.value = BookEditState.Done
    }
}

/** Pantalla «Editar» del menú ⋮ de un libro: título, autor, idioma y nivel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookEditScreen(onBack: () -> Unit, vm: BookEditViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == BookEditState.Done) onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_edit_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                BookEditState.Loading, BookEditState.Done -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                BookEditState.Failed -> Text(stringResource(R.string.book_edit_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                is BookEditState.Ready -> EditForm(s.form, onSave = vm::save, onReset = vm::reset, onCancel = onBack)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditForm(initial: BookEditForm, onSave: (BookEditForm) -> Unit, onReset: () -> Unit, onCancel: () -> Unit) {
    var form by remember(initial) { mutableStateOf(initial) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = form.title,
            onValueChange = { form = form.copy(title = it) },
            label = { Text(stringResource(R.string.book_edit_field_title)) },
            isError = form.title.isBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.author,
            onValueChange = { form = form.copy(author = it) },
            label = { Text(stringResource(R.string.book_edit_field_author)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = Languages.name(form.language),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.book_edit_field_language)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (Languages.translationTargets + form.language).distinct().sortedBy(Languages::name).forEach { tag ->
                    DropdownMenuItem(text = { Text(Languages.name(tag)) }, onClick = { form = form.copy(language = tag); open = false })
                }
            }
        }
        Text(
            stringResource(R.string.book_edit_language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.book_edit_field_level), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CefrLevel.entries.forEach { level ->
                FilterChip(selected = form.level == level, onClick = { form = form.copy(level = level) }, label = { Text(level.name) })
            }
        }

        Button(onClick = { onSave(form) }, enabled = form.title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_save)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (initial.edited) TextButton(onClick = onReset) { Text(stringResource(R.string.book_edit_reset)) } else Box(Modifier)
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}
