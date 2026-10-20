package com.linguareader.ui.catalog

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.data.content.ImportRequest
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.library.LibraryRepository
import com.linguareader.data.library.Shelf
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.ui.common.Languages
import com.linguareader.ui.reader.NewCollectionDialog
import com.linguareader.ui.settings.LibraryDownloadBanner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onOpenBook: (String) -> Unit,
    onOpenVocabulary: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditBook: (String) -> Unit,
    onOpenDeviceDocuments: () -> Unit = {},
    vm: CatalogViewModel = hiltViewModel(),
    importVm: ImportViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    val foundDocuments by vm.foundDocuments.collectAsStateWithLifecycle()
    val importState by importVm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<CatalogItem?>(null) }
    var newCollectionFor by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // "*/*": muchos gestores de ficheros etiquetan .fb2, .srt o .md como application/octet-stream.
    // El formato real se detecta por el contenido al importar.
    val pickerContract = remember { ImportDocumentContract() }
    val picker = rememberLauncherForActivityResult(pickerContract) { uri ->
        if (uri != null) importVm.start(ImportRequest.Document(uri))
    }
    fun pick(start: PickerStart) {
        pickerContract.start = start
        picker.launch(arrayOf("*/*"))
    }
    var importMenu by remember { mutableStateOf(false) }
    var askUrl by remember { mutableStateOf(false) }
    var whatsAppHelp by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    if (whatsAppHelp) {
        WhatsAppHelpDialog(
            onOpenWhatsApp = context.packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { launch ->
                { whatsAppHelp = false; context.startActivity(launch) }
            },
            onSearch = { whatsAppHelp = false; pick(PickerStart.WHATSAPP) },
            onDismiss = { whatsAppHelp = false },
        )
    }

    if (askUrl) {
        UrlDialog(
            initial = clipboard.getText()?.text?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty(),
            onConfirm = { url -> askUrl = false; importVm.start(ImportRequest.WebPage(url)) },
            onDismiss = { askUrl = false },
        )
    }

    LaunchedEffect(importState) {
        val done = importState as? ImportUiState.Done ?: return@LaunchedEffect
        vm.showSection(LibrarySection.Books) // el libro nuevo se ve al volver, sea cual sea su idioma
        importVm.dismiss()
        onOpenBook(done.bookId)
    }
    ImportDialog(state = importState, onEdit = importVm::edit, onConfirm = importVm::confirm, onDismiss = importVm::dismiss)

    newCollectionFor?.let { bookId ->
        NewCollectionDialog(
            onCreate = { name -> vm.createCollection(name, bookId); newCollectionFor = null },
            onDismiss = { newCollectionFor = null },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Borrar definitivamente") },
            text = { Text("«${item.summary.title}» se eliminará del dispositivo. Puedes volver a importarlo cuando quieras.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteForever(item.summary.id); pendingDelete = null }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            LibraryDrawer(
                state = state,
                onSelect = { section -> vm.showSection(section); scope.launch { drawer.close() } },
                onVocabulary = { scope.launch { drawer.close() }; onOpenVocabulary() },
                onSettings = { scope.launch { drawer.close() }; onOpenSettings() },
                onDeviceDocuments = { scope.launch { drawer.close() }; onOpenDeviceDocuments() },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(painterResource(R.drawable.ic_menu), contentDescription = "Menú")
                        }
                    },
                    title = { Text(sectionTitle(state.section, state.shelves), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        val searching = state.filters.search.isActive || searchLanguage(state) != null
                        IconButton(onClick = { showSearch = true }) {
                            BadgedBox(badge = { if (searching) Badge() }) {
                                Icon(painterResource(R.drawable.ic_search), contentDescription = if (searching) "Buscar (hay una búsqueda activa)" else "Buscar")
                            }
                        }
                        IconButton(onClick = { vm.setGrid(!state.grid) }) {
                            Icon(
                                painterResource(if (state.grid) R.drawable.ic_view_list else R.drawable.ic_grid_view),
                                contentDescription = if (state.grid) "Ver como lista" else "Ver portadas",
                            )
                        }
                        Box {
                            IconButton(onClick = { importMenu = true }) {
                                Icon(painterResource(R.drawable.ic_add), contentDescription = "Importar libro")
                            }
                            DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Desde un fichero…") },
                                    onClick = { importMenu = false; pick(PickerStart.ANY) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Recibido por WhatsApp…") },
                                    onClick = { importMenu = false; whatsAppHelp = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Recibido por Telegram…") },
                                    onClick = { importMenu = false; pick(PickerStart.TELEGRAM) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Desde una página web…") },
                                    onClick = { importMenu = false; askUrl = true },
                                )
                                // El portapapeles se lee solo al pulsar: leerlo al abrir el menú hace que
                                // Android avise de que la app ha accedido a él.
                                DropdownMenuItem(
                                    text = { Text("Pegar texto copiado") },
                                    onClick = {
                                        importMenu = false
                                        importVm.start(ImportRequest.Text(clipboard.getText()?.text.orEmpty(), null))
                                    },
                                )
                                HorizontalDivider()
                                Text(
                                    "También: abre el archivo en WhatsApp, Telegram o el correo y elige " +
                                        "«Abrir con» o «Compartir» › LinguaReader.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(240.dp),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (showSearch) {
                CatalogSearchCard(
                    search = state.filters.search,
                    language = searchLanguage(state),
                    // En «Aprender otros idiomas» el idioma ya se elige con sus chips; en «Aprender inglés»… es fijo.
                    languages = if (state.section == LibrarySection.LearnOther) emptyList() else state.sectionLanguages,
                    resultCount = state.items.size,
                    onSearch = vm::setSearch,
                    onLanguage = vm::setOtherLanguage,
                    onClear = vm::clearSearch,
                    onDismiss = { showSearch = false },
                )
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                SubFilterBar(state, vm)
                if (state.filters.search.isActive || searchLanguage(state) != null) {
                    ActiveSearchBar(
                        summary = searchSummary(state.filters.search, searchLanguage(state), state.items.size),
                        onEdit = { showSearch = true },
                        onClear = vm::clearSearch,
                    )
                }
                val onOpen = { item: CatalogItem -> if (!item.trashed) onOpenBook(item.summary.id) }
                val onShare = { item: CatalogItem ->
                    scope.launch {
                        val uri = vm.exportForSharing(item.summary.id) ?: return@launch
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, item.summary.title)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Compartir libro"))
                    }
                    Unit
                }
                val onTranslation = { action: TranslationAction ->
                    when (action) {
                        is TranslationAction.Cancel -> vm.cancelTranslation(action.bookId)
                        is TranslationAction.Retry -> vm.retryTranslation(action.bookId, action.target)
                        is TranslationAction.Dismiss -> vm.dismissTranslationError(action.bookId)
                    }
                }
                val listState = rememberLazyListState()
                val gridState = rememberLazyGridState()
                // Al cambiar de apartado o de filtros, o al aparecer «¿Por dónde empiezo?», se vuelve arriba: si no, la
                // lista se queda anclada al libro que estaba primero y lo nuevo queda por encima, fuera de la vista.
                LaunchedEffect(state.section, state.filters, state.suggestion?.summary?.id) {
                    listState.scrollToItem(0)
                    gridState.scrollToItem(0)
                }
                LibraryDownloadBanner()
                if (state.section == LibrarySection.Books && foundDocuments > 0 && !state.filters.search.isActive) {
                    FoundDocumentsBanner(foundDocuments, onOpen = onOpenDeviceDocuments, onDismiss = vm::dismissFoundDocuments)
                }
                when {
                    state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp))
                    !state.loading && state.items.isEmpty() -> Text(
                        emptyMessage(state.section, filtering = state.filters.search.isActive || state.filters.levels.isNotEmpty() || state.filters.language != null),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                    state.grid -> LazyVerticalGrid(
                        state = gridState,
                        // Tantas columnas como quepan: 3 en un móvil en vertical, más en horizontal o en una tablet.
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.suggestion?.let { s ->
                            item(key = "suggestion", span = { GridItemSpan(maxLineSpan) }) {
                                StartHereCard(s, covers[s.summary.id], onOpen = { onOpen(s) }, modifier = Modifier)
                            }
                        }
                        items(state.items, key = { it.summary.id }) { item ->
                            BookCover(
                                item = item,
                                cover = covers[item.summary.id],
                                shelves = state.systemShelves,
                                translationTarget = state.translationLanguage,
                                onClick = { onOpen(item) },
                                onShelf = { shelf, inShelf -> vm.setInShelf(shelf.id, item.summary.id, inShelf) },
                                onTrash = { vm.moveToTrash(item) },
                                onRestore = { vm.restore(item.summary.id) },
                                onDeleteForever = if (item.trashed && vm.canDeleteForever(item.summary.id)) ({ pendingDelete = item }) else null,
                                onEdit = { onEditBook(item.summary.id) },
                                onShare = { onShare(item) },
                                onAction = onTranslation,
                            )
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.suggestion?.let { s ->
                            item(key = "suggestion") { StartHereCard(s, covers[s.summary.id], onOpen = { onOpen(s) }, modifier = Modifier) }
                        }
                        items(state.items, key = { it.summary.id }) { item ->
                            BookCard(
                                item = item,
                                cover = covers[item.summary.id],
                                shelves = state.systemShelves,
                                collections = state.collections,
                                translationTarget = state.translationLanguage,
                                onClick = { onOpen(item) },
                                onShelf = { shelf, inShelf -> vm.setInShelf(shelf.id, item.summary.id, inShelf) },
                                onNewCollection = { newCollectionFor = item.summary.id },
                                onEdit = { onEditBook(item.summary.id) },
                                onShare = { onShare(item) },
                                onTrash = { vm.moveToTrash(item) },
                                onRestore = { vm.restore(item.summary.id) },
                                onDeleteForever = if (item.trashed && vm.canDeleteForever(item.summary.id)) ({ pendingDelete = item }) else null,
                                onAction = onTranslation,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Idioma elegido en la búsqueda (fuera de «Aprender otros idiomas», donde es el de sus chips). */
private fun searchLanguage(state: CatalogUiState): String? =
    state.filters.language.takeIf { state.section != LibrarySection.LearnOther }

private fun sectionTitle(section: LibrarySection, shelves: List<Shelf>): String = when (section) {
    LibrarySection.Reading -> "Leyendo ahora"
    LibrarySection.Books -> "Libros"
    is LibrarySection.Learn -> "Aprender ${Languages.nameInSpanish(section.language)}"
    LibrarySection.LearnOther -> "Aprender otros idiomas"
    is LibrarySection.OnShelf -> shelves.firstOrNull { it.id == section.id }?.name ?: "Libros"
    LibrarySection.Collections -> "Colecciones"
    LibrarySection.Trash -> "Papelera"
}

private fun emptyMessage(section: LibrarySection, filtering: Boolean): String = when {
    filtering -> "Ningún libro coincide con la búsqueda o los filtros. Prueba a cambiarlos o quítalos con la ✕."
    section == LibrarySection.Books ->
        "Aún no tienes libros. Abre uno de los documentos de tu móvil (menú › En el dispositivo), descarga lecturas " +
            "graduadas en Ajustes › Bibliotecas o usa + para importar un EPUB, PDF, TXT, FB2, Word, HTML o subtítulos."
    else -> sectionEmptyMessage(section)
}

private fun sectionEmptyMessage(section: LibrarySection): String = when (section) {
    LibrarySection.Reading -> "Aún no has empezado ningún libro. Los que abras aparecerán aquí, el último primero."
    LibrarySection.Trash -> "La papelera está vacía."
    is LibrarySection.OnShelf -> "No hay libros en esta estantería. Añádelos desde el ⋮ de una portada o desde el lector: menú ⋮ › Agregar a…"
    LibrarySection.Collections -> "No hay libros en colecciones. Crea una con el icono de carpeta de un libro (vista de lista) o desde el lector: menú ⋮ › Agregar a… › Nueva colección."
    is LibrarySection.Learn, LibrarySection.LearnOther ->
        "No hay libros de este idioma. Descarga su biblioteca de lecturas graduadas en Ajustes › Bibliotecas o importa los tuyos con +."
    else -> "No hay libros aquí todavía."
}

/** Menú lateral: apartados de la biblioteca (la mayoría son filtros de los mismos libros), vocabulario y ajustes. */
@Composable
private fun LibraryDrawer(
    state: CatalogUiState,
    onSelect: (LibrarySection) -> Unit,
    onVocabulary: () -> Unit,
    onSettings: () -> Unit,
    onDeviceDocuments: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(12.dp))
                Text("LinguaReader", style = MaterialTheme.typography.titleLarge)
            }
            @Composable
            fun item(section: LibrarySection, label: String, icon: @Composable () -> Unit) = NavigationDrawerItem(
                label = { Text(label) },
                icon = icon,
                badge = { state.counts[section]?.takeIf { it > 0 }?.let { Text("$it") } },
                selected = state.section == section,
                onClick = { onSelect(section) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
            fun res(id: Int): @Composable () -> Unit = { Icon(painterResource(id), contentDescription = null) }
            item(LibrarySection.Reading, "Leyendo ahora", res(R.drawable.ic_history))
            item(LibrarySection.Books, "Libros", res(R.drawable.ic_library_books))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LibrarySection.LEARN_LANGUAGES.forEach { tag ->
                item(LibrarySection.Learn(tag), "Aprender ${Languages.nameInSpanish(tag)}") { LanguageBadge(tag) }
            }
            item(LibrarySection.LearnOther, "Aprender otros idiomas", res(R.drawable.ic_language))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            state.systemShelves.forEach { shelf ->
                val icon = when (shelf.kind) {
                    LibraryRepository.FAVORITES -> R.drawable.ic_star
                    LibraryRepository.TO_READ -> R.drawable.ic_schedule
                    else -> R.drawable.ic_done_all
                }
                item(LibrarySection.OnShelf(shelf.id), shelf.name, res(icon))
            }
            item(LibrarySection.Collections, "Colecciones", res(R.drawable.ic_folder))
            item(LibrarySection.Trash, "Papelera", res(R.drawable.ic_delete))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                label = { Text("En el dispositivo") }, icon = res(R.drawable.ic_find_in_page), selected = false, onClick = onDeviceDocuments,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
            NavigationDrawerItem(
                label = { Text("Vocabulario") }, icon = res(R.drawable.ic_translate), selected = false, onClick = onVocabulary,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
            NavigationDrawerItem(
                label = { Text("Ajustes") }, icon = res(R.drawable.ic_settings), selected = false, onClick = onSettings,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Código del idioma en un círculo, como icono de «Aprender inglés/francés». */
@Composable
private fun LanguageBadge(tag: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Text(tag.uppercase(), color = MaterialTheme.colorScheme.surface, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** Subfiltros del apartado: niveles en «Aprender…», procedencia en «Libros», idioma y colección donde aplica. */
@Composable
private fun SubFilterBar(state: CatalogUiState, vm: CatalogViewModel) {
    val f = state.filters
    @Composable
    fun chips(content: @Composable () -> Unit) = Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
    @Composable
    fun levels() = chips {
        FilterChip(selected = f.levels.isEmpty(), onClick = vm::clearLevels, label = { Text("Todos los niveles") })
        CefrLevel.entries.forEach { level ->
            FilterChip(selected = level in f.levels, onClick = { vm.toggleLevel(level) }, label = { Text(level.name) })
        }
    }
    @Composable
    fun translateTo() = chips {
        Text("Traducir al", style = MaterialTheme.typography.labelLarge)
        var open by remember { mutableStateOf(false) }
        Box {
            AssistChip(onClick = { open = true }, label = { Text(Languages.name(state.translationLanguage)) })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (Languages.translationTargets + state.translationLanguage).distinct().forEach { tag ->
                    DropdownMenuItem(text = { Text(Languages.name(tag)) }, onClick = { vm.setTranslationLanguage(tag); open = false })
                }
            }
        }
    }
    when (state.section) {
        is LibrarySection.Learn -> { levels(); translateTo() }
        LibrarySection.LearnOther -> {
            if (state.otherLanguages.size > 1) chips {
                FilterChip(selected = f.language == null, onClick = { vm.setOtherLanguage(null) }, label = { Text("Todos los idiomas") })
                state.otherLanguages.forEach { tag ->
                    FilterChip(selected = f.language == tag, onClick = { vm.setOtherLanguage(tag) }, label = { Text(Languages.name(tag)) })
                }
            }
            levels()
            translateTo()
        }
        LibrarySection.Collections -> if (state.collections.isNotEmpty()) {
            chips {
                FilterChip(selected = f.collectionId == null, onClick = { vm.setCollection(null) }, label = { Text("Todas") })
                state.collections.forEach { c ->
                    FilterChip(
                        selected = f.collectionId == c.id,
                        onClick = { vm.setCollection(c.id) },
                        label = { Text("${c.name} (${c.bookIds.size})") },
                    )
                }
            }
            state.collections.firstOrNull { it.id == f.collectionId }?.let { c ->
                TextButton(onClick = { vm.deleteCollection(c.id) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Borrar la colección «${c.name}» (los libros no se borran)")
                }
            }
        }
        LibrarySection.Books, LibrarySection.Reading, is LibrarySection.OnShelf, LibrarySection.Trash -> Unit
    }
}

/**
 * Portada en la cuadrícula: imagen del libro (o una portada generada con el título si no tiene), nivel, progreso y un
 * menú ⋮ con estanterías, papelera y traducción.
 */
@Composable
private fun BookCover(
    item: CatalogItem,
    cover: ImageBitmap?,
    shelves: List<Shelf>,
    translationTarget: String,
    onClick: () -> Unit,
    onShelf: (Shelf, Boolean) -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: (() -> Unit)?,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onAction: (TranslationAction) -> Unit,
) {
    val s = item.summary
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.clickable(onClick = onClick).semantics(mergeDescendants = true) {
            contentDescription = "${s.title}, ${Languages.name(s.learningLanguage)}, nivel ${s.cefrLevel.name}"
        },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))) {
            if (cover != null) {
                Image(bitmap = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                GeneratedCover(s.title, s.learningLanguage)
            }
            Text(
                "${s.learningLanguage.uppercase()} · ${s.cefrLevel.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Box(Modifier.align(Alignment.BottomEnd)) {
                // Botón pequeño sobre la portada (un IconButton impone 48 dp y taparía la ilustración).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(end = 4.dp, bottom = 8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { menu = true }
                        .semantics { contentDescription = "Opciones de «${s.title}»" },
                ) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (item.trashed) {
                        DropdownMenuItem(text = { Text("Restaurar") }, onClick = { menu = false; onRestore() })
                        onDeleteForever?.let { DropdownMenuItem(text = { Text("Borrar definitivamente") }, onClick = { menu = false; it() }) }
                    } else {
                        shelves.forEach { shelf ->
                            val inShelf = s.id in shelf.bookIds
                            DropdownMenuItem(
                                text = { Text(shelf.name) },
                                leadingIcon = { Checkbox(checked = inShelf, onCheckedChange = null) },
                                onClick = { onShelf(shelf, !inShelf) },
                            )
                        }
                        if (item.imported && translationTarget !in item.machineTranslations && item.translationJob == null &&
                            !LanguageTags.sameLanguage(s.learningLanguage, translationTarget)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Traducir entero al ${Languages.nameInSpanish(translationTarget)}") },
                                onClick = { menu = false; onAction(TranslationAction.Retry(s.id, translationTarget)) },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Editar") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Compartir libro") }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text("Enviar a la papelera") }, onClick = { menu = false; onTrash() })
                    }
                }
            }
            if (item.progress != null) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp),
                )
            }
        }
        Text(
            s.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        item.translationJob?.let { job ->
            if (job is BookTranslationState.Running && job.total > 0) {
                LinearProgressIndicator(progress = { job.done.toFloat() / job.total }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
    }
}

/** Portada para libros sin imagen: color estable por título y el título en grande, como una cubierta de tela. */
@Composable
private fun GeneratedCover(title: String, language: String) {
    val palette = listOf(
        Color(0xFF2E5E6E), Color(0xFF7A3E3E), Color(0xFF4F6B3A), Color(0xFF5B4A7A),
        Color(0xFF8A6A2F), Color(0xFF3D4F7A), Color(0xFF6E3F5E), Color(0xFF2F6B5B),
    )
    val base = palette[Math.floorMod(title.hashCode(), palette.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(base, base.copy(alpha = 0.82f)))),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
            Box(Modifier.width(36.dp).height(1.dp).background(Color.White.copy(alpha = 0.6f)))
            Text(
                title,
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Box(Modifier.width(36.dp).height(1.dp).background(Color.White.copy(alpha = 0.6f)))
            Text(Languages.name(language), color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Android no deja a otras apps explorar la carpeta privada de WhatsApp en todos los móviles, así que
 * se explican los dos caminos que funcionan siempre: «Abrir con»/«Compartir» desde WhatsApp, y
 * buscar en «Recientes»/«Documentos» del selector (que usa el índice de Android, no las carpetas).
 */
@Composable
private fun WhatsAppHelpDialog(onOpenWhatsApp: (() -> Unit)?, onSearch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Libro recibido por WhatsApp") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("La forma más sencilla:", style = MaterialTheme.typography.labelLarge)
                Text(
                    "1. En WhatsApp, abre el chat y toca el archivo.\n" +
                        "2. Elige «Abrir con» › LinguaReader.\n" +
                        "Si se abre con otra app, mantén pulsado el mensaje y usa «Compartir» › LinguaReader.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("O desde aquí:", style = MaterialTheme.typography.labelLarge)
                Text(
                    "«Buscar el archivo» abre el selector; si la carpeta de WhatsApp sale vacía, mira en «Recientes» " +
                        "o en «Documentos», o busca el nombre del libro con la lupa.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSearch) { Text("Buscar el archivo") } },
        dismissButton = {
            Row {
                onOpenWhatsApp?.let { TextButton(onClick = it) { Text("Abrir WhatsApp") } }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

@Composable
private fun UrlDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(initial) }
    val valid = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar página web") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Se descarga solo el texto principal del artículo (sin menús ni comentarios). " +
                        "También puedes usar «Compartir» desde el navegador.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Dirección (https://…)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(url.trim()) }, enabled = valid) { Text("Importar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Acciones sobre la pretraducción de un libro desde su tarjeta. */
sealed interface TranslationAction {
    data class Cancel(val bookId: String) : TranslationAction
    /** Empezar, reintentar o continuar la traducción del libro entero. */
    data class Retry(val bookId: String, val target: String) : TranslationAction
    data class Dismiss(val bookId: String) : TranslationAction
}

@Composable
private fun TranslationPausedRow(bookId: String, pause: ImportedBookRepository.TranslationPause, onAction: (TranslationAction) -> Unit) {
    val language = Languages.nameInSpanish(pause.target)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val fraction = if (pause.total == 0) 0f else pause.done.toFloat() / pause.total
        Text(
            if (pause.total == 0) "Traducción al $language pausada" else
                "Traducción al $language pausada: ${(fraction * 100).toInt()} % (${pause.done}/${pause.total} frases)",
            style = MaterialTheme.typography.labelMedium,
        )
        if (pause.total > 0) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onAction(TranslationAction.Retry(bookId, pause.target)) }) { Text("Continuar traducción") }
    }
}

@Composable
private fun TranslationJobRow(bookId: String, job: BookTranslationState, onAction: (TranslationAction) -> Unit) {
    val language = Languages.nameInSpanish(job.target)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (job) {
            is BookTranslationState.Preparing -> {
                Text("Preparando la traducción al $language…", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is BookTranslationState.Running -> {
                val fraction = if (job.total == 0) 0f else job.done.toFloat() / job.total
                Text(
                    "Traduciendo al $language: ${(fraction * 100).toInt()} % (${job.done}/${job.total} frases)",
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            is BookTranslationState.Failed ->
                Text(job.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Row {
            if (job is BookTranslationState.Failed) {
                TextButton(onClick = { onAction(TranslationAction.Retry(bookId, job.target)) }) { Text("Reintentar") }
                TextButton(onClick = { onAction(TranslationAction.Dismiss(bookId)) }) { Text("Cerrar") }
            } else {
                TextButton(onClick = { onAction(TranslationAction.Cancel(bookId)) }) { Text("Cancelar traducción") }
            }
        }
    }
}

/**
 * Ficha de la vista de lista: portada pequeña (del tamaño de la cuadrícula) y, a su derecha, título, autor, datos,
 * progreso y una fila de acciones. Las acciones van en orden de uso al planificar la lectura: para leer, leído,
 * favorito, colección y, al final, ⋮ (editar, compartir, papelera). Una acción activa se marca con un fondo tonal.
 */
@Composable
private fun BookCard(
    item: CatalogItem,
    cover: ImageBitmap?,
    shelves: List<Shelf>,
    collections: List<Shelf>,
    translationTarget: String,
    onClick: () -> Unit,
    onShelf: (Shelf, Boolean) -> Unit,
    onNewCollection: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: (() -> Unit)?,
    onAction: (TranslationAction) -> Unit,
) {
    val s = item.summary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {
            contentDescription = "${s.title}, ${Languages.name(s.learningLanguage)}, nivel ${s.cefrLevel.name}"
        },
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(COVER_WIDTH).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))) {
                if (cover != null) {
                    Image(bitmap = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    GeneratedCover(s.title, s.learningLanguage)
                }
                Text(
                    s.cefrLevel.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    s.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (s.authors.isNotEmpty()) {
                    Text(s.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(Languages.name(s.learningLanguage), "≈ ${s.estimatedMinutes} min", "Importado".takeIf { item.imported && !item.fromLibrary })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.weight(1f))
                if (item.progress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.weight(1f))
                        Text("${(item.progress * 100).toInt()} %", style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
                val job = item.translationJob
                val pause = item.translationPause
                when {
                    job != null -> TranslationJobRow(s.id, job, onAction)
                    pause != null -> TranslationPausedRow(s.id, pause, onAction)
                }
                if (item.trashed) {
                    TrashActions(onRestore, onDeleteForever)
                } else {
                    BookActions(
                        item = item,
                        shelves = shelves,
                        collections = collections,
                        translationTarget = translationTarget,
                        onShelf = onShelf,
                        onNewCollection = onNewCollection,
                        onEdit = onEdit,
                        onShare = onShare,
                        onTrash = onTrash,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

/** Ancho de la portada en la lista: el de una portada de la cuadrícula en un móvil en vertical. */
private val COVER_WIDTH = 96.dp

@Composable
private fun TrashActions(onRestore: () -> Unit, onDeleteForever: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onRestore) {
            Icon(painterResource(R.drawable.ic_restore), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(" Restaurar")
        }
        if (onDeleteForever != null) {
            TextButton(onClick = onDeleteForever) { Text("Borrar") }
        } else {
            Text("Incluido en la app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BookActions(
    item: CatalogItem,
    shelves: List<Shelf>,
    collections: List<Shelf>,
    translationTarget: String,
    onShelf: (Shelf, Boolean) -> Unit,
    onNewCollection: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
    onAction: (TranslationAction) -> Unit,
) {
    val s = item.summary
    fun shelf(kind: String) = shelves.firstOrNull { it.kind == kind }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple(LibraryRepository.TO_READ, R.drawable.ic_schedule, "Para leer"),
            Triple(LibraryRepository.READ, R.drawable.ic_done_all, "Leído"),
            Triple(LibraryRepository.FAVORITES, R.drawable.ic_star, "Favorito"),
        ).forEach { (kind, icon, label) ->
            val target = shelf(kind) ?: return@forEach
            val on = s.id in target.bookIds
            ActionIcon(icon, if (on) "Quitar de «${target.name}»" else label, active = on) { onShelf(target, !on) }
        }

        var collectionMenu by remember { mutableStateOf(false) }
        Box {
            ActionIcon(R.drawable.ic_folder, "Agregar a una colección", active = collections.any { s.id in it.bookIds }) { collectionMenu = true }
            DropdownMenu(expanded = collectionMenu, onDismissRequest = { collectionMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Crear colección…") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    onClick = { collectionMenu = false; onNewCollection() },
                )
                if (collections.isNotEmpty()) HorizontalDivider()
                collections.forEach { c ->
                    val inIt = s.id in c.bookIds
                    DropdownMenuItem(
                        text = { Text(c.name) },
                        leadingIcon = { Checkbox(checked = inIt, onCheckedChange = null) },
                        onClick = { onShelf(c, !inIt) },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        var more by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { more = true }, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Más opciones de «${s.title}»", modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(
                    text = { Text("Editar") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    onClick = { more = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Compartir libro") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                    onClick = { more = false; onShare() },
                )
                if (item.imported && translationTarget !in item.machineTranslations && item.translationJob == null &&
                    item.translationPause == null && !LanguageTags.sameLanguage(s.learningLanguage, translationTarget)
                ) {
                    DropdownMenuItem(
                        text = { Text("Traducir entero al ${Languages.nameInSpanish(translationTarget)}") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_translate), contentDescription = null) },
                        onClick = { more = false; onAction(TranslationAction.Retry(s.id, translationTarget)) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Enviar a la papelera") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    onClick = { more = false; onTrash() },
                )
            }
        }
    }
}

/** Botón de acción de la ficha: icono pequeño; activo = fondo tonal y color de acento. */
@Composable
private fun ActionIcon(icon: Int, description: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (active) colors.secondaryContainer else Color.Transparent),
        ) {
            Icon(
                painterResource(icon),
                contentDescription = description,
                tint = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Tras buscar en el móvil: cuántos documentos hay para abrir (se cierra con la ✕ y no vuelve a salir). */
@Composable
private fun FoundDocumentsBanner(count: Int, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
            Icon(painterResource(R.drawable.ic_find_in_page), contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    if (count == 1) "Hemos encontrado 1 documento en tu móvil" else "Hemos encontrado $count documentos en tu móvil",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("Tócalo para verlos y abrir el que quieras", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Cerrar aviso") }
        }
    }
}

/** «¿Por dónde empiezo?»: el libro más corto del nivel elegido, mientras no se haya empezado ninguno del idioma. */
@Composable
private fun StartHereCard(item: CatalogItem, cover: ImageBitmap?, onOpen: () -> Unit, modifier: Modifier) {
    val s = item.summary
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp))) {
                if (cover != null) Image(bitmap = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else GeneratedCover(s.title, s.learningLanguage)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("¿Por dónde empiezo?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${s.cefrLevel.name} · ≈ ${s.estimatedMinutes} min", style = MaterialTheme.typography.bodySmall)
            }
            Text("Empezar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
