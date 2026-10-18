package com.linguareader.ui.catalog

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.data.content.ImportRequest
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.ui.common.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onOpenBook: (String) -> Unit,
    onOpenVocabulary: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: CatalogViewModel = hiltViewModel(),
    importVm: ImportViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    val importState by importVm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<CatalogItem?>(null) }

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
        vm.setReadingLanguage(done.language)
        importVm.dismiss()
        onOpenBook(done.bookId)
    }
    ImportDialog(state = importState, onEdit = importVm::edit, onConfirm = importVm::confirm, onDismiss = importVm::dismiss)

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Borrar libro importado") },
            text = { Text("«${item.summary.title}» se eliminará del dispositivo. Puedes volver a importarlo cuando quieras.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteImported(item.summary.id); pendingDelete = null }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca") },
                actions = {
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
                    IconButton(onClick = onOpenVocabulary) {
                        Icon(painterResource(R.drawable.ic_star), contentDescription = "Vocabulario")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_tune), contentDescription = "Ajustes")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "filters") {
                Filters(
                    readingOptions = state.readingOptions,
                    readingLanguage = state.readingLanguage,
                    translationLanguage = state.translationLanguage,
                    levels = state.levels,
                    onReadingLanguage = vm::setReadingLanguage,
                    onTranslationLanguage = vm::setTranslationLanguage,
                    onToggleLevel = vm::toggleLevel,
                )
            }
            when {
                state.error != null -> item(key = "error") { Text(state.error!!, color = MaterialTheme.colorScheme.error) }
                !state.loading && state.items.isEmpty() -> item(key = "empty") {
                    Text(
                        "No hay libros con estos filtros. Usa + para importar un EPUB, TXT, FB2, HTML, Markdown o subtítulos.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> items(state.items, key = { it.summary.id }) { item ->
                    BookCard(
                        item,
                        covers[item.summary.id],
                        onClick = { onOpenBook(item.summary.id) },
                        onDelete = if (item.imported) ({ pendingDelete = item }) else null,
                        translationTarget = state.translationLanguage,
                        onAction = { action ->
                            when (action) {
                                is TranslationAction.Cancel -> vm.cancelTranslation(action.bookId)
                                is TranslationAction.Retry -> vm.retryTranslation(action.bookId, action.target)
                                is TranslationAction.Dismiss -> vm.dismissTranslationError(action.bookId)
                            }
                        },
                    )
                }
            }
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

@Composable
private fun Filters(
    readingOptions: List<String?>,
    readingLanguage: String?,
    translationLanguage: String,
    levels: Set<CefrLevel>,
    onReadingLanguage: (String?) -> Unit,
    onTranslationLanguage: (String) -> Unit,
    onToggleLevel: (CefrLevel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Idioma del texto", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            readingOptions.forEach { tag ->
                FilterChip(
                    selected = readingLanguage == tag,
                    onClick = { onReadingLanguage(tag) },
                    label = { Text(Languages.name(tag)) },
                )
            }
        }
        Text("Nivel", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CefrLevel.entries.forEach { level ->
                FilterChip(selected = level in levels, onClick = { onToggleLevel(level) }, label = { Text(level.name) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Traducir al", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            var open by remember { mutableStateOf(false) }
            Box {
                AssistChip(onClick = { open = true }, label = { Text(Languages.name(translationLanguage)) })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    (Languages.translationTargets + translationLanguage).distinct().forEach { tag ->
                        DropdownMenuItem(text = { Text(Languages.name(tag)) }, onClick = { onTranslationLanguage(tag); open = false })
                    }
                }
            }
        }
    }
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

@Composable
private fun BookCard(
    item: CatalogItem,
    cover: ImageBitmap?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    translationTarget: String,
    onAction: (TranslationAction) -> Unit,
) {
    val s = item.summary
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {
            contentDescription = "${s.title}, ${Languages.name(s.learningLanguage)}, nivel ${s.cefrLevel.name}"
        },
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 7f).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    "${s.learningLanguage.uppercase()} · ${s.cefrLevel.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Borrar «${s.title}»")
                        }
                    }
                }
                s.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val translations = s.translationLanguages.sorted().joinToString(", ") { Languages.name(it) }
                Text(
                    listOfNotNull(
                        Languages.name(s.learningLanguage),
                        s.genre ?: "Importado".takeIf { item.imported },
                        "≈ ${s.estimatedMinutes} min",
                        "${s.passageCount} pasajes",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (translations.isNotEmpty()) {
                    Text("Traducciones: $translations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.machineTranslations.isNotEmpty()) {
                    Text(
                        "Traducción automática: ${item.machineTranslations.sorted().joinToString(", ") { Languages.name(it) }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val job = item.translationJob
                val pause = item.translationPause
                when {
                    job != null -> TranslationJobRow(item.summary.id, job, onAction)
                    pause != null -> TranslationPausedRow(item.summary.id, pause, onAction)
                    // Libro importado aún sin traducir entero: se puede lanzar cuando se quiera.
                    item.imported && translationTarget !in item.machineTranslations &&
                        !LanguageTags.sameLanguage(s.learningLanguage, translationTarget) ->
                        TextButton(onClick = { onAction(TranslationAction.Retry(s.id, translationTarget)) }) {
                            Text("Traducir el libro entero al ${Languages.nameInSpanish(translationTarget)}")
                        }
                }
                if (item.progress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    item.progressLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
