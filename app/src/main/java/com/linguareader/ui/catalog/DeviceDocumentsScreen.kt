package com.linguareader.ui.catalog

import android.net.Uri
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.painterResource
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
        val uri = if (doc.location.startsWith("content://")) Uri.parse(doc.location) else Uri.fromFile(File(doc.location))
        pending.offer(ImportRequest.Document(uri))
    }
}

private enum class Grouping(val label: String) { FORMAT("Formato"), AUTHOR("Autor"), FOLDER("Carpeta") }

/** Documentos encontrados en el dispositivo (Ajustes › Examinar archivos), clasificados para elegir qué abrir. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDocumentsScreen(onBack: () -> Unit, onOpenScanSettings: () -> Unit, vm: DeviceDocumentsViewModel = hiltViewModel()) {
    val documents by vm.documents.collectAsStateWithLifecycle(initialValue = null)
    var grouping by rememberSaveable { mutableStateOf(Grouping.FORMAT) }
    var format by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("En el dispositivo") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Volver") } },
                actions = {
                    IconButton(onClick = onOpenScanSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Opciones de búsqueda") }
                },
            )
        },
    ) { padding ->
        val all = documents ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (all.isEmpty()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Aún no hay documentos encontrados.", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Busca los libros que ya tienes en el móvil desde Ajustes › Examinar archivos.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.Button(onClick = onOpenScanSettings) { Text("Examinar archivos") }
                }
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar por título, autor o archivo") },
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
                    ) { Text(g.label) }
                }
            }
            val formats = all.map { formatLabel(it.format) }.distinct().sorted()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = format == null, onClick = { format = null }, label = { Text("Todos (${all.size})") })
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
            val groups = visible.groupBy { d ->
                when (grouping) {
                    Grouping.FORMAT -> formatLabel(d.format)
                    Grouping.AUTHOR -> d.author ?: "Autor desconocido"
                    Grouping.FOLDER -> d.folder.substringAfterLast('/').ifEmpty { d.folder }
                }
            }.toSortedMap(compareBy<String> { it == "Autor desconocido" }.thenBy { it.lowercase(Locale.ROOT) })

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
                    items(docs, key = { it.location }) { doc -> DocumentRow(doc, onClick = { vm.open(doc) }) }
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(doc: DeviceDocumentEntity, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${doc.title}, ${formatLabel(doc.format)}. Tocar para abrir" }
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
                listOfNotNull(doc.author, doc.language?.let(Languages::name), size(doc.sizeBytes)).joinToString(" · "),
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

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
    else -> "${(bytes / 1000).coerceAtLeast(1)} KB"
}
