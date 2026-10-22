package com.linguareader.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.linguareader.R
import com.linguareader.data.content.BookImportService
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.ui.common.UiText
import com.linguareader.ui.navigation.PdfViewerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PdfViewerState {
    data object Opening : PdfViewerState
    data class Ready(val session: PdfDocumentSession, val initialPage: Int) : PdfViewerState
    /** No se puede leer el fichero (movido, borrado, permiso caducado); [canRelocate] si es de un libro importado. */
    data class Unavailable(val canRelocate: Boolean) : PdfViewerState
    data class Failed(val message: UiText) : PdfViewerState
}

/**
 * Visor del PDF original: de un libro importado ([PdfViewerRoute.bookId], con su referencia al
 * fichero) o de un fichero suelto sin importar ([PdfViewerRoute.uri], desde Examinar archivos).
 */
@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imported: ImportedBookRepository,
    private val importer: BookImportService,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val route = savedState.toRoute<PdfViewerRoute>()
    val bookId: String? = route.bookId
    private val _title = MutableStateFlow(route.title.orEmpty())
    /** Título del libro o, para un fichero suelto, su nombre. */
    val title: StateFlow<String> = _title.asStateFlow()

    private val _state = MutableStateFlow<PdfViewerState>(PdfViewerState.Opening)
    val state: StateFlow<PdfViewerState> = _state.asStateFlow()

    /** Última página vista de cada PDF: el visor vuelve a ella. */
    private val positions = context.getSharedPreferences("pdf_viewer", Context.MODE_PRIVATE)
    private val positionKey: String get() = bookId ?: route.uri.orEmpty()

    init {
        open()
    }

    private fun open() = viewModelScope.launch {
        _state.value = PdfViewerState.Opening
        val uri = route.uri ?: bookId?.let { imported.original(it)?.uri }
        if (uri == null) {
            _state.value = PdfViewerState.Unavailable(canRelocate = bookId != null)
            return@launch
        }
        if (_title.value.isEmpty()) _title.value = displayName(Uri.parse(uri)).orEmpty()
        _state.value = try {
            val session = PdfDocumentSession.open(context, Uri.parse(uri))
            current?.close()
            current = session
            PdfViewerState.Ready(session, positions.getInt(positionKey, 0).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0)))
        } catch (_: PdfDocumentSession.Unavailable) {
            PdfViewerState.Unavailable(canRelocate = bookId != null)
        } catch (e: PdfDocumentSession.Unreadable) {
            PdfViewerState.Failed(
                UiText.res(if (e.cause is SecurityException) R.string.pdf_password_protected else R.string.pdf_unreadable),
            )
        }
    }

    private var current: PdfDocumentSession? = null

    /** El usuario eligió de nuevo el fichero: se guarda la nueva referencia (con permiso persistente) y se abre. */
    fun relocate(uri: Uri) {
        val id = bookId ?: return
        viewModelScope.launch {
            importer.keepAccess(uri)
            imported.updateOriginal(id, uri.toString())
            open()
        }
    }

    private suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    fun savePage(page: Int) {
        positions.edit().putInt(positionKey, page).apply()
    }

    override fun onCleared() {
        current?.close()
    }
}
