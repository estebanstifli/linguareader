package com.linguareader.ui.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.linguareader.R
import com.linguareader.data.content.BookImportService
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.content.PdfLayoutState
import com.linguareader.data.content.PdfTextLayouts
import com.linguareader.ui.common.UiText
import com.linguareader.ui.navigation.ReaderRoute
import com.linguareader.ui.viewer.PdfDocumentSession
import com.linguareader.ui.viewer.PdfViewerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * El PDF original dentro del lector («Ver original»): abre el fichero referenciado y obtiene la
 * posición de cada palabra sobre las páginas. La voz, la traducción y el progreso siguen siendo
 * los de [ReaderViewModel]; esto solo aporta las páginas y dónde está cada token.
 */
@HiltViewModel
class OriginalPdfViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imported: ImportedBookRepository,
    private val importer: BookImportService,
    private val layouts: PdfTextLayouts,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val bookId = savedState.toRoute<ReaderRoute>().bookId

    /** El libro tiene un original que se puede ver (PDF): muestra el botón «Ver original» del lector. */
    val available: StateFlow<Boolean> = imported.originals
        .map { it[bookId]?.viewable == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _document = MutableStateFlow<PdfViewerState>(PdfViewerState.Opening)
    val document: StateFlow<PdfViewerState> = _document.asStateFlow()

    val layout: StateFlow<PdfLayoutState> = layouts.state(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, PdfLayoutState.Idle)

    private var session: PdfDocumentSession? = null
    private var opened = false

    /** Abre el PDF la primera vez que se pasa a «Ver original» (no antes: el lector de texto no lo necesita). */
    fun open() {
        if (opened) return
        opened = true
        reopen()
    }

    private fun reopen() = viewModelScope.launch {
        _document.value = PdfViewerState.Opening
        val uri = imported.original(bookId)?.uri
        if (uri == null) {
            _document.value = PdfViewerState.Unavailable(canRelocate = true)
            return@launch
        }
        _document.value = try {
            val next = PdfDocumentSession.open(context, Uri.parse(uri))
            session?.close()
            session = next
            layouts.ensure(bookId)
            PdfViewerState.Ready(next, initialPage = 0)
        } catch (_: PdfDocumentSession.Unavailable) {
            PdfViewerState.Unavailable(canRelocate = true)
        } catch (e: PdfDocumentSession.Unreadable) {
            PdfViewerState.Failed(
                UiText.res(if (e.cause is SecurityException) R.string.pdf_password_protected else R.string.pdf_unreadable),
            )
        }
    }

    /** El usuario eligió de nuevo el fichero (se movió o se perdió el permiso). */
    fun relocate(uri: Uri) = viewModelScope.launch {
        importer.keepAccess(uri)
        imported.updateOriginal(bookId, uri.toString())
        layouts.invalidate(bookId)
        reopen()
    }

    /** Volver a intentar el cálculo de posiciones si falló (p. ej. sin permiso en ese momento). */
    fun retryLayout() = layouts.ensure(bookId)

    override fun onCleared() {
        session?.close()
    }
}
