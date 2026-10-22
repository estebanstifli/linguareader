package com.linguareader.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.data.db.DeviceDocumentDao
import com.linguareader.data.db.DeviceDocumentEntity
import com.linguareader.data.scan.DocumentScanner
import com.linguareader.data.scan.ScanSettings
import com.linguareader.data.scan.ScanSettingsRepository
import com.linguareader.data.scan.ScanState
import com.linguareader.data.scan.StorageAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val repo: ScanSettingsRepository,
    private val scanner: DocumentScanner,
    documents: DeviceDocumentDao,
) : ViewModel() {
    val settings: StateFlow<ScanSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val state: StateFlow<ScanState> = scanner.state
    val documents: StateFlow<List<DeviceDocumentEntity>> =
        documents.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val supportedFormats: Set<String> get() = scanner.supportedFormats

    /** Se vuelve a comprobar al volver de la pantalla de permisos del sistema. */
    val hasAccess = MutableStateFlow(false)

    fun refreshAccess(granted: Boolean) { hasAccess.value = granted }

    fun update(t: (ScanSettings) -> ScanSettings) = viewModelScope.launch { repo.update(t) }

    fun start() = scanner.start()
    fun cancel() = scanner.cancel()

    fun addFolder(uri: Uri) = update { it.copy(folders = it.folders + uri.toString()) }
    fun removeFolder(uri: String) = update { it.copy(folders = it.folders - uri) }
    fun addExclusion(path: String) = update { it.copy(exclusions = it.exclusions + path) }
    fun removeExclusion(path: String) = update { it.copy(exclusions = it.exclusions - path) }

    /**
     * Primera apertura: se ofreció la búsqueda; si [scan], empieza ya. Si no (o no se dio el permiso), tampoco se busca
     * sola al abrir la app: se respeta el «Ahora no» y se puede activar en Ajustes › Examinar archivos.
     */
    fun finishOffer(scan: Boolean) = viewModelScope.launch {
        repo.update { it.copy(offered = true, autoStart = scan) }
        if (scan) scanner.start()
    }
}

/** Grupos de formatos tal como los ve el usuario (la app distingue más extensiones). Nombres de formato: no se traducen. */
internal val FORMAT_GROUPS: List<Pair<String, Set<String>>> = listOf(
    "EPUB" to setOf("epub"),
    "PDF" to setOf("pdf"),
    "FB2" to setOf("fb2", "fbz"),
    "Kindle" to setOf("mobi", "prc", "azw", "azw3"),
    "Word/ODT/RTF" to setOf("docx", "odt", "rtf"),
    "Web" to setOf("html", "htm", "xhtml", "xht"),
    "Markdown" to setOf("md", "markdown"),
    "TXT" to setOf("txt", "text"),
    "SRT/VTT" to setOf("srt", "vtt"),
)

internal fun formatLabel(format: String): String = FORMAT_GROUPS.firstOrNull { format in it.second }?.first ?: format.uppercase()

/** Las carpetas elegidas con el selector del sistema llegan como URI de árbol: se enseña su ruta legible. */
internal fun treeLabel(uri: String, internalStorage: String): String = runCatching {
    DocumentsContract.getTreeDocumentId(Uri.parse(uri)).let { id ->
        val (volume, path) = id.substringBefore(':') to id.substringAfter(':', "")
        (if (volume == "primary") internalStorage else volume) + if (path.isNotEmpty()) "/$path" else ""
    }
}.getOrDefault(uri)

/** Ajustes › Examinar archivos: buscar en el dispositivo los documentos que la app sabe abrir. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanFilesSection(onOpenDocuments: () -> Unit, vm: ScanViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val access by vm.hasAccess.collectAsStateWithLifecycle()
    val s = settings ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refreshAccess(StorageAccess.granted(context)) }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshAccess(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.addFolder(uri)
        }
    }
    val pickExclusion = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::pathOfTree)?.let(vm::addExclusion)
    }
    val requestAccess = {
        if (StorageAccess.needsSettingsScreen) context.startActivity(StorageAccess.settingsIntent(context))
        else askPermission.launch(StorageAccess.RUNTIME_PERMISSION)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusPanel(documents, state, s.lastScanAt, onStart = vm::start, onCancel = vm::cancel, onOpenDocuments = onOpenDocuments)

        if (!access && (s.wholeDevice || s.downloads)) PermissionCard(onGrant = requestAccess)

        ToggleRow(R.drawable.ic_schedule, stringResource(R.string.scan_on_open), stringResource(R.string.scan_on_open_detail), s.autoStart) { v ->
            vm.update { it.copy(autoStart = v) }
        }

        GroupTitle(stringResource(R.string.scan_where))
        ToggleRow(R.drawable.ic_smartphone, stringResource(R.string.scan_whole_device), stringResource(R.string.scan_whole_device_detail), s.wholeDevice) { v ->
            vm.update { it.copy(wholeDevice = v) }
        }
        ToggleRow(R.drawable.ic_download, stringResource(R.string.scan_downloads), stringResource(R.string.scan_downloads_detail), s.downloads) { v ->
            vm.update { it.copy(downloads = v) }
        }
        val internalStorage = stringResource(R.string.scan_internal_storage)
        val chosenFolder = stringResource(R.string.scan_chosen_folder)
        s.folders.sorted().forEach { uri ->
            ItemRow(R.drawable.ic_folder, treeLabel(uri, internalStorage), chosenFolder) { vm.removeFolder(uri) }
        }
        TextButton(onClick = { pickFolder.launch(null) }) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(" " + stringResource(R.string.scan_add_folder))
        }

        GroupTitle(stringResource(R.string.scan_what))
        val active = s.formats.ifEmpty { vm.supportedFormats }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FORMAT_GROUPS.filter { (_, exts) -> exts.any { it in vm.supportedFormats } }.forEach { (label, exts) ->
                val on = exts.any { it in active } && (label != "TXT" || s.includeTxt)
                FilterChip(
                    selected = on,
                    onClick = {
                        vm.update { cur ->
                            if (label == "TXT") cur.copy(includeTxt = !on)
                            else {
                                val base = cur.formats.ifEmpty { vm.supportedFormats }
                                cur.copy(formats = if (on) base - exts else base + exts)
                            }
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
        Text(
            stringResource(R.string.scan_small_txt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GroupTitle(stringResource(R.string.scan_other_options))
        ToggleRow(R.drawable.ic_visibility, stringResource(R.string.scan_hidden), stringResource(R.string.scan_hidden_detail), s.includeHidden) { v ->
            vm.update { it.copy(includeHidden = v) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_block)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.scan_excluded_folders), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (s.exclusions.isEmpty()) stringResource(R.string.scan_excluded_none) else "${s.exclusions.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { pickExclusion.launch(null) }) { Text(stringResource(R.string.scan_exclude)) }
        }
        s.exclusions.sorted().forEach { path -> ItemRow(R.drawable.ic_block, path.substringAfterLast('/'), path) { vm.removeExclusion(path) } }
    }
}

/** Ruta absoluta de una carpeta elegida con el selector (solo en almacenamiento local), para excluirla. */
private fun pathOfTree(uri: Uri): String? = runCatching {
    val id = DocumentsContract.getTreeDocumentId(uri)
    val (volume, path) = id.substringBefore(':') to id.substringAfter(':', "")
    val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
    if (path.isEmpty()) root else "$root/$path"
}.getOrNull()

@Composable
private fun StatusPanel(
    documents: List<DeviceDocumentEntity>,
    state: ScanState,
    lastScanAt: Long,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenDocuments: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.scan_stat_documents), documents.size.toString(), Modifier.weight(1f))
                StatTile(stringResource(R.string.scan_stat_authors), documents.mapNotNull { it.author }.distinct().size.toString(), Modifier.weight(1f))
                StatTile(stringResource(R.string.scan_stat_formats), documents.map { formatLabel(it.format) }.distinct().size.toString(), Modifier.weight(1f))
            }
            val byFormat = documents.groupingBy { formatLabel(it.format) }.eachCount().entries.sortedByDescending { it.value }
            if (byFormat.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    byFormat.forEach { (label, n) ->
                        Text(
                            "$label · $n",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            when (state) {
                is ScanState.Running -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.scan_running, state.folders, state.found) +
                            if (state.currentFolder.isNotEmpty()) "\n${state.currentFolder}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.scan_stop)) }
                }
                else -> {
                    val line = when (state) {
                        is ScanState.Finished ->
                            if (state.removed > 0) stringResource(R.string.scan_last_result_removed, state.found, state.added, state.removed)
                            else stringResource(R.string.scan_last_result, state.found, state.added)
                        is ScanState.Failed -> state.message.asString()
                        else -> if (lastScanAt == 0L) stringResource(R.string.scan_never)
                        else stringResource(
                            R.string.scan_last_date,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastScanAt)),
                        )
                    }
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is ScanState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(" " + stringResource(R.string.scan_now))
                        }
                        if (documents.isNotEmpty()) OutlinedButton(onClick = onOpenDocuments) { Text(stringResource(R.string.scan_view_documents)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.scan_permission_missing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                stringResource(if (StorageAccess.needsSettingsScreen) R.string.scan_permission_all_files else R.string.scan_permission_storage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Button(onClick = onGrant) {
                Text(stringResource(if (StorageAccess.needsSettingsScreen) R.string.scan_open_permission_settings else R.string.scan_allow))
            }
        }
    }
}

@Composable
internal fun IconBadge(icon: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ToggleRow(icon: Int, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange, role = Role.Switch).padding(vertical = 4.dp),
    ) {
        IconBadge(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ItemRow(icon: Int, title: String, subtitle: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 52.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.scan_remove_item, title)) }
    }
}

/**
 * Primera apertura: ofrecer buscar libros en el móvil. Pide el permiso necesario (o lleva a la pantalla de Android
 * donde se concede) y empieza a buscar en segundo plano. Se puede omitir; queda en Ajustes › Examinar archivos.
 */
@Composable
fun ScanOfferScreen(step: Int = 0, steps: Int = 0, vm: ScanViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var waitingForSettings by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Al VOLVER de la pantalla de permisos del sistema (ON_RESUME tras salir) se busca si se concedió; la oferta se
    // cierra en todo caso.
    DisposableEffect(lifecycle, waitingForSettings) {
        // Al registrarse, el observador recibe ON_RESUME en el acto (la pantalla ya está visible): solo cuenta el
        // ON_RESUME que llega después de haber salido a los ajustes del sistema.
        var left = false
        val observer = LifecycleEventObserver { _, event ->
            if (!waitingForSettings) return@LifecycleEventObserver
            if (event == Lifecycle.Event.ON_PAUSE) left = true
            if (event == Lifecycle.Event.ON_RESUME && left) vm.finishOffer(scan = StorageAccess.granted(context))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.finishOffer(scan = granted)
    }
    androidx.activity.compose.BackHandler { vm.finishOffer(scan = false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (steps > 1) {
            LinearProgressIndicator(progress = { step.toFloat() / steps }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.onboarding_step, step, steps), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconBadge(R.drawable.ic_find_in_page)
        Text(stringResource(R.string.scan_offer_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.scan_offer_body), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (StorageAccess.needsSettingsScreen) R.string.scan_offer_all_files else R.string.scan_offer_storage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                when {
                    StorageAccess.granted(context) -> vm.finishOffer(scan = true)
                    StorageAccess.needsSettingsScreen -> {
                        waitingForSettings = true
                        context.startActivity(StorageAccess.settingsIntent(context))
                    }
                    else -> askPermission.launch(StorageAccess.RUNTIME_PERMISSION)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.scan_offer_search)) }
        TextButton(onClick = { vm.finishOffer(scan = false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_not_now)) }
        Text(
            stringResource(R.string.scan_offer_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
