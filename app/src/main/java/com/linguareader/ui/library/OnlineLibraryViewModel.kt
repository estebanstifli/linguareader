package com.linguareader.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.core.model.CefrLevel
import com.linguareader.data.catalog.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnlineLibraryState(
    val filter: CatalogFilter = CatalogFilter(),
    val books: List<OnlineBook> = emptyList(),
    val categories: List<CatalogCategory> = emptyList(),
    val languages: List<CatalogLanguage> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val page: Int = 0,
    val pages: Int = 0,
    val total: Int = 0,
    val detail: OnlineBook? = null,
    val detailLoading: Boolean = false,
    val detailError: Boolean = false,
    val importing: Boolean = false,
    val importError: Boolean = false,
    val localId: String? = null,
    val openBook: String? = null,
)

class OnlineLibraryViewModel(
    val provider: OnlineCatalog,
    private val importer: OnlineBookImporter,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(OnlineLibraryState(filter = CatalogFilter(
        query = saved["query"] ?: "", language = saved["language"] ?: "",
        category = saved["category"] ?: "", level = saved["level"] ?: "",
    )))
    val state = mutable.asStateFlow()
    private var listing: Job? = null
    private var details: Job? = null
    init {
        reload()
        saved.get<Int>("selectedBook")?.let { select(it) }
    }

    fun filter(value: CatalogFilter) {
        saved["query"] = value.query
        saved["language"] = value.language
        saved["category"] = value.category
        saved["level"] = value.level
        val debounce = value.query != mutable.value.filter.query
        mutable.update { it.copy(filter = value, books = emptyList(), page = 0, pages = 0) }
        load(false, debounce)
    }
    fun reload() = load(false)
    fun more() { if (!mutable.value.loading && mutable.value.page < mutable.value.pages) load(true) }
    private fun load(append: Boolean, debounce: Boolean = false) {
        listing?.cancel()
        listing = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = false) }
            try {
                if (debounce) delay(350)
                val before = mutable.value
                val (page, cats, langs) = coroutineScope {
                    val categories = async { if (before.categories.isEmpty()) provider.categories() else before.categories }
                    val languages = async { if (before.languages.isEmpty()) provider.languages() else before.languages }
                    Triple(provider.books(before.filter, if (append) before.page + 1 else 1), categories.await(), languages.await())
                }
                mutable.update { it.copy(loading = false, books = (if (append) it.books + page.data else page.data).distinctBy { b -> b.id },
                    categories = cats, languages = langs, page = page.pagination.page, pages = page.pagination.pages, total = page.pagination.total) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.update { it.copy(loading = false, error = true) } }
        }
    }

    fun select(id: Int) {
        saved["selectedBook"] = id
        details?.cancel()
        mutable.update { it.copy(detail = it.books.firstOrNull { b -> b.id == id }, detailLoading = true, detailError = false, localId = null, importError = false) }
        details = viewModelScope.launch {
            try {
                val book = provider.book(id)
                val local = if (importer.exists(provider, book)) importer.bookId(provider, book) else null
                mutable.update { it.copy(detail = book, detailLoading = false, localId = local) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.update { it.copy(detailLoading = false, detailError = true) } }
        }
    }
    fun retryDetail() { saved.get<Int>("selectedBook")?.let(::select) }
    fun closeDetail() {
        if (mutable.value.importing) return
        details?.cancel()
        saved.remove<Int>("selectedBook")
        mutable.update { it.copy(detail = null, detailLoading = false, detailError = false, importError = false) }
    }
    fun add(level: CefrLevel, read: Boolean) {
        val current = mutable.value
        val book = current.detail ?: return
        if (current.importing) return
        viewModelScope.launch {
            mutable.update { it.copy(importing = true, importError = false) }
            try {
                val id = importer.add(provider, book, level)
                mutable.update { it.copy(importing = false, localId = id, openBook = id.takeIf { read }) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.update { it.copy(importing = false, importError = true) } }
        }
    }
    fun opened() { mutable.update { it.copy(openBook = null) } }
}
