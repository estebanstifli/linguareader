package com.linguareader.ui.catalog

import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.data.content.ImportRequest
import com.linguareader.data.content.PendingImports
import com.linguareader.data.db.DeviceDocumentDao
import com.linguareader.data.db.DeviceDocumentEntity
import com.linguareader.ui.common.Languages
import com.linguareader.ui.settings.formatLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DeviceDocumentsViewModel @Inject constructor(
    dao: DeviceDocumentDao,
    private val pending: PendingImports,
) : ViewModel() {
    val documents: Flow<List<DeviceDocumentEntity>> = dao.observeAll()

    /** Importa el documento con el flujo de siempre: la biblioteca muestra el diálogo y abre el libro. */
    fun open(doc: DeviceDocumentEntity) {
        pending.offer(ImportRequest.Document(uriOf(doc)))
    }

    fun uriOf(doc: DeviceDocumentEntity): Uri =
        if (doc.location.startsWith("content://")) Uri.parse(doc.location) else Uri.fromFile(File(doc.location))
}

private enum class Grouping(@StringRes val label: Int) {
    FORMAT(R.string.device_docs_group_format),
    AUTHOR(R.string.device_docs_group_author),
    FOLDER(R.string.device_docs_group_folder),
}

/** Documentos encontrados en el dispositivo (Ajustes › Examinar archivos), clasificados para elegir qué abrir. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDocumentsScreen(
    onBack: () -> Unit,
    onOpenScanSettings: () -> Unit,
    onViewPdf: (uri: String, title: String) -> Unit = { _, _ -> },
    vm: DeviceDocumentsViewModel = hiltViewModel(),
) {
    val documents by vm.documents.collectAsStateWithLifecycle(initialValue = null)
    var choosing by remember { mutableStateOf<DeviceDocumentEntity?>(null) }
    choosing?.let { doc ->
        // Un PDF se puede mirar sin importarlo; importarlo da el modo lectura (voz y traducción).
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(stringResource(R.string.device_docs_pdf_choice)) },
            confirmButton = { TextButton(onClick = { choosing = null; vm.open(doc) }) { Text(stringResource(R.string.device_docs_import_to_read)) } },
            dismissButton = {
                TextButton(onClick = { choosing = null; onViewPdf(vm.uriOf(doc).toString(), doc.title) }) { Text(stringResource(R.string.device_docs_just_view)) }
            },
        )
    }
    var grouping by rememberSaveable { mutableStateOf(Grouping.FORMAT) }
    var format by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_on_device)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = onOpenScanSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.device_docs_scan_options))
                    }
                },
            )
        },
    ) { padding ->
        val all = documents ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (all.isEmpty()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.device_docs_empty), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.device_docs_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.Button(onClick = onOpenScanSettings) { Text(stringResource(R.string.scan_title)) }
                }
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.device_docs_search_placeholder)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Grouping.entries.forEachIndexed { i, g ->
                    SegmentedButton(
                        selected = grouping == g,
                        onClick = { grouping = g },
                        shape = SegmentedButtonDefaults.itemShape(i, Grouping.entries.size),
                    ) { Text(stringResource(g.label)) }
                }
            }
            val formats = all.map { formatLabel(it.format) }.distinct().sorted()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = format == null, onClick = { format = null }, label = { Text(stringResource(R.string.device_docs_all_count, all.size)) })
                formats.forEach { f ->
                    FilterChip(
                        selected = format == f,
                        onClick = { format = if (format == f) null else f },
                        label = { Text("$f (${all.count { formatLabel(it.format) == f }})") },
                    )
                }
            }
            val q = query.trim().lowercase(Locale.ROOT)
            val visible = all.filter { d ->
                (format == null || formatLabel(d.format) == format) &&
                    (q.isEmpty() || listOfNotNull(d.title, d.author, d.fileName).any { q in it.lowercase(Locale.ROOT) })
            }
            val unknownAuthor = stringResource(R.string.device_docs_unknown_author)
            val groups = visible.groupBy { d ->
                when (grouping) {
                    Grouping.FORMAT -> formatLabel(d.format)
                    Grouping.AUTHOR -> d.author ?: unknownAuthor
                    Grouping.FOLDER -> d.folder.substringAfterLast('/').ifEmpty { d.folder }
                }
            }.toSortedMap(compareBy<String> { it == unknownAuthor }.thenBy { it.lowercase(Locale.ROOT) })

            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (name, docs) ->
                    item(key = "h:$name") {
                        Text(
                            "$name · ${docs.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    // Al importar, la app vuelve sola a la biblioteca (PendingImports.arrivals).
                    items(docs, key = { it.location }) { doc ->
                        DocumentRow(doc, onClick = { if (doc.format == "pdf") choosing = doc else vm.open(doc) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(doc: DeviceDocumentEntity, onClick: () -> Unit) {
    val description = stringResource(R.string.device_docs_row_description, doc.title, formatLabel(doc.format))
    val size = Formatter.formatShortFileSize(LocalContext.current, doc.sizeBytes)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp, 56.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp)),
        ) {
            Text(
                formatLabel(doc.format).take(4),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(doc.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(doc.author, doc.language?.let(Languages::name), size).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                doc.folder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

