package com.linguareader.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.core.model.CefrLevel
import com.linguareader.data.catalog.OnlineBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineLibraryScreen(onBack: () -> Unit, onOpenBook: (String) -> Unit, vm: OnlineLibraryViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inDetail = state.detail != null || state.detailLoading || state.detailError
    val back = { if (inDetail) vm.closeDetail() else onBack() }
    BackHandler(enabled = inDetail) { vm.closeDetail() }
    LaunchedEffect(state.openBook) { state.openBook?.let { vm.opened(); onOpenBook(it) } }
    var confirm by rememberSaveable(state.detail?.id) { mutableStateOf(false) }
    var selectedLevel by rememberSaveable(state.detail?.id) { mutableStateOf<String?>(null) }
    val level = state.detail?.cefr_estimated?.let(CefrLevel::parse) ?: selectedLevel?.let(CefrLevel::parse)

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.online_add_title)) },
            text = { Text(stringResource(R.string.online_add_message, state.detail?.title.orEmpty())) },
            confirmButton = { TextButton(onClick = { confirm = false; level?.let { vm.add(it, true) } }) { Text(stringResource(R.string.online_add_read)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { confirm = false; level?.let { vm.add(it, false) } }) { Text(stringResource(R.string.online_add_only)) }
                }
            },
        )
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (inDetail) R.string.online_book_details else R.string.online_library)) },
                navigationIcon = { IconButton(onClick = back, enabled = !state.importing) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                } },
            )
        },
        bottomBar = {
            if (inDetail && state.detail != null && !state.detailLoading && !state.detailError) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.importing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.online_downloading), Modifier.padding(top = 8.dp))
                        } else if (state.localId != null) {
                            Text(stringResource(R.string.online_already_added), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { state.localId?.let(onOpenBook) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.online_read)) }
                        } else {
                            if (state.importError) Text(stringResource(R.string.online_import_error), color = MaterialTheme.colorScheme.error)
                            if (state.detail?.download == null) Text(stringResource(R.string.online_unavailable))
                            if (state.detail?.cefr_estimated?.let(CefrLevel::parse) == null && state.detail?.download != null) {
                                Text(stringResource(R.string.online_choose_level), style = MaterialTheme.typography.labelLarge)
                                ChipRow {
                                    CefrLevel.entries.forEach { value -> FilterChip(selectedLevel == value.name, { selectedLevel = value.name }, label = { Text(value.name) }) }
                                }
                            }
                            Button(onClick = { confirm = true }, enabled = level != null && state.detail?.download != null, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.online_add_title))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (inDetail) {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.detailError) ErrorPanel(vm::retryDetail)
                state.detail?.let { book ->
                    OnlineCover(book, Modifier.width(190.dp).aspectRatio(0.67f), vm.provider::fetch)
                    Text(book.title, style = MaterialTheme.typography.headlineSmall)
                    book.author_names?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(book.languageTags.joinToString(" · ", transform = ::languageName), style = MaterialTheme.typography.labelLarge)
                    if (!state.detailLoading && !state.detailError) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.online_summary), style = MaterialTheme.typography.titleMedium)
                            Text(book.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.online_no_summary), style = MaterialTheme.typography.bodyLarge)
                            book.source_reading_level?.let { readingLevel ->
                                Text(stringResource(R.string.online_reading_level), style = MaterialTheme.typography.titleMedium)
                                Text(readingLevel)
                            }
                            book.cefr_estimated?.let { estimated -> Text(stringResource(R.string.online_estimated_level, estimated)) }
                        }
                    }
                }
            }
        } else {
            val grid = rememberLazyGridState()
            LaunchedEffect(state.filter) { grid.scrollToItem(0) }
            LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), state = grid, modifier = Modifier.padding(padding).fillMaxSize().testTag("online_books"),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(vm.provider.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(value = state.filter.query, onValueChange = { vm.filter(state.filter.copy(query = it)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                            singleLine = true, label = { Text(stringResource(R.string.online_search)) }, modifier = Modifier.fillMaxWidth().testTag("online_search"),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) })
                        Text(stringResource(R.string.online_categories), style = MaterialTheme.typography.titleSmall)
                        ChipRow {
                            FilterChip(state.filter.category.isEmpty(), { vm.filter(state.filter.copy(category = "")) }, label = { Text(stringResource(R.string.online_all_categories)) })
                            state.categories.forEach { category -> FilterChip(state.filter.category == category.slug, { vm.filter(state.filter.copy(category = category.slug)) }, label = { Text(category.name) }) }
                        }
                        Text(stringResource(R.string.online_languages), style = MaterialTheme.typography.titleSmall)
                        ChipRow {
                            FilterChip(state.filter.language.isEmpty(), { vm.filter(state.filter.copy(language = "")) }, label = { Text(stringResource(R.string.online_all_languages)) })
                            state.languages.forEach { language -> FilterChip(state.filter.language == language.code, { vm.filter(state.filter.copy(language = language.code)) }, label = { Text(languageName(language.code)) }) }
                        }
                        ChipRow {
                            FilterChip(state.filter.level.isEmpty(), { vm.filter(state.filter.copy(level = "")) }, label = { Text(stringResource(R.string.online_all_levels)) })
                            CefrLevel.entries.forEach { level -> FilterChip(state.filter.level == level.name, { vm.filter(state.filter.copy(level = level.name)) }, label = { Text(level.name) }, modifier = Modifier.testTag("online_level_${level.name}")) }
                        }
                        Text(stringResource(R.string.online_popular), style = MaterialTheme.typography.titleLarge)
                    }
                }
                items(state.books, key = { it.id }) { book ->
                    Column(Modifier.testTag("online_book_${book.id}").clickable { keyboard?.hide(); focus.clearFocus(); vm.select(book.id) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            OnlineCover(book, Modifier.fillMaxWidth().aspectRatio(0.67f), vm.provider::fetch)
                            book.cefr_estimated?.let { level ->
                                Surface(Modifier.padding(8.dp), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(level, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book.author_names.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when {
                            state.loading -> CircularProgressIndicator()
                            state.error -> ErrorPanel(vm::reload)
                            state.books.isEmpty() -> Text(stringResource(R.string.online_empty))
                            state.page < state.pages -> OutlinedButton(onClick = vm::more) { Text(stringResource(R.string.online_more)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun ErrorPanel(retry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.online_connection_error))
        TextButton(onClick = retry) { Text(stringResource(R.string.common_retry)) }
    }
}

private fun languageName(tag: String): String = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

private val coverCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
private fun OnlineCover(book: OnlineBook, modifier: Modifier, fetch: suspend (String, Long) -> ByteArray) {
    val bitmap by produceState<Bitmap?>(null, book.cover_url) {
        val url = book.cover_url ?: return@produceState
        value = coverCache.get(url) ?: try {
            withContext(Dispatchers.IO) {
                val bytes = fetch(url, 5L * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 800 || bounds.outHeight / inSampleSize > 1200) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { coverCache.put(url, it) }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { null }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), contentDescription = book.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_library_books), null, Modifier.size(36.dp))
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}
