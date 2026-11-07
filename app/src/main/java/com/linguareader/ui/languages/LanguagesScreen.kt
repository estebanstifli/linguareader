package com.linguareader.ui.languages

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.LibraryInfo
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryPackState
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.engine.translation.ModelDownload
import com.linguareader.engine.translation.TranslationModelManager
import com.linguareader.ui.common.Languages
import com.linguareader.ui.common.toUiText
import com.linguareader.ui.settings.LibraryProgress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Idiomas: el nativo (al que se traduce) y los que se aprenden (uno o varios). Elegir uno prepara lo que necesita
 * ([LanguageInstaller]). La misma pantalla sirve para la primera apertura, «Aprender otros idiomas» y Ajustes.
 */
class LanguagesViewModel(
    private val repo: UserPreferencesRepository,
    private val libraries: LibraryPackManager,
    private val models: TranslationModelManager,
    private val installer: LanguageInstaller,
) : ViewModel() {
    val preferences: StateFlow<UserPreferences?> = repo.preferences.map<UserPreferences, UserPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val libraryCatalog: StateFlow<Map<String, LibraryInfo>> = libraries.catalog
    val libraryStates: StateFlow<Map<String, LibraryPackState>> = libraries.states
    val downloadedModels: StateFlow<Set<String>> = models.downloaded
    val modelDownloads: StateFlow<Map<String, ModelDownload>> = models.downloads

    /** Idiomas a los que ML Kit sabe traducir (selector «Tu idioma»). */
    val nativeOptions: List<String> get() = models.supportedLanguages
    val deviceLanguage: String get() = models.deviceLanguage()

    init { viewModelScope.launch { models.refresh(); libraries.refreshCatalog() } }

    /** Idiomas que se pueden aprender: los que tienen biblioteca primero, después el resto de los que la voz sabe leer. */
    fun learnable(catalog: Map<String, LibraryInfo>): List<String> =
        (catalog.keys + SupertonicModel.LANGUAGES).distinct()
            .sortedWith(compareBy({ it !in catalog }, { Languages.name(it).lowercase() }))

    fun code(tag: String): String? = models.code(tag)
    fun neededModels(lang: String, native: String) = installer.neededModels(lang, native)

    /** Primera apertura: todo de una vez, y arrancan las descargas. */
    fun chooseLanguages(learning: List<String>, native: String, wifiOnly: Boolean) = viewModelScope.launch {
        repo.update { it.withLanguagesChosen(learning, native, wifiOnly) }
        installer.installAll(repo.preferences.first())
    }

    fun addLearning(lang: String) = viewModelScope.launch {
        repo.update { it.withLearningLanguages(it.learningLanguages + lang) }
        installer.install(lang, repo.preferences.first())
    }

    /** Quita el idioma del menú; sus libros descargados se conservan (se borran aparte con [deleteLibrary]). */
    fun removeLearning(lang: String) = viewModelScope.launch {
        repo.update { it.withLearningLanguages(it.learningLanguages - lang) }
    }

    fun setNative(code: String) = viewModelScope.launch {
        repo.update { it.copy(translationLanguage = code) }
        installer.installAll(repo.preferences.first())
    }

    fun setWifiOnly(value: Boolean) = viewModelScope.launch { repo.update { it.copy(translationWifiOnly = value) } }

    fun downloadLibrary(lang: String) = libraries.download(lang)
    fun deleteLibrary(lang: String) = viewModelScope.launch { libraries.delete(lang) }
    fun downloadModel(code: String) = models.download(code)
    fun deleteModel(code: String) = viewModelScope.launch { models.delete(code) }
}

/** Pantalla propia (desde «Aprender otros idiomas» del menú). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_page_languages)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            LanguagesSection()
        }
    }
}

/** Selector desplegable de idioma (nativo). */
@Composable
private fun LanguagePicker(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(Languages.name(selected), style = MaterialTheme.typography.titleMedium) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { code ->
                DropdownMenuItem(text = { Text(Languages.name(code)) }, onClick = { onSelect(code); open = false })
            }
        }
    }
}

@Composable
private fun WifiOnlyRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(R.string.onboarding_wifi_only), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun mb(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** «16 libros · 2,1 MB» si hay biblioteca; si no, «Libros próximamente». */
@Composable
private fun availability(info: LibraryInfo?): String =
    if (info == null) stringResource(R.string.languages_coming_soon)
    else "${pluralStringResource(R.plurals.common_books, info.books, info.books)} · ${mb(info.sizeBytes)}"

/**
 * Primera apertura: nativo arriba, lista de idiomas con casillas (uno o varios) y «Continuar». No guarda nada hasta
 * pulsar el botón: así cerrar la app a medias vuelve a esta pantalla.
 */
@Composable
fun LanguagesOnboardingContent(p: UserPreferences, vm: LanguagesViewModel = koinViewModel()) {
    val catalog by vm.libraryCatalog.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(p.learningLanguages.toSet()) }
    var native by rememberSaveable { mutableStateOf(if (p.translationSetupDone) p.translationLanguage else vm.deviceLanguage) }
    var wifiOnly by rememberSaveable { mutableStateOf(p.translationWifiOnly) }
    val nativeClash = selected.any { LanguageTags.sameLanguage(it, native) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.translation_your_language), style = MaterialTheme.typography.labelLarge)
            LanguagePicker(native, vm.nativeOptions, onSelect = { native = it })
        }
        if (nativeClash) {
            Text(stringResource(R.string.onboarding_native_same), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.languages_learning_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        var showAll by rememberSaveable { mutableStateOf(false) }
        val all = vm.learnable(catalog)
        // Primero los que tienen libros; los demás (solo voz y traductor) se despliegan aparte para no alargar la lista.
        val shown = if (showAll) all else all.filter { it in catalog || it in selected }
        shown.forEach { lang ->
            val checked = lang in selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = {
                    selected = if (it) selected + lang else selected - lang
                }),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(Languages.name(lang), style = MaterialTheme.typography.titleMedium)
                        Text(availability(catalog[lang]), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!showAll && shown.size < all.size) {
            TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.languages_show_more, all.size - shown.size)) }
        }
        WifiOnlyRow(wifiOnly) { wifiOnly = it }
        Button(
            onClick = { vm.chooseLanguages(p.learningLanguages.filter { it in selected } + (selected - p.learningLanguages.toSet()).sorted(), native, wifiOnly) },
            enabled = selected.isNotEmpty() && !nativeClash,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.common_continue)) }
    }
}

/**
 * Ajustes › Idiomas y «Aprender otros idiomas»: nativo con su traductor, los idiomas que se aprenden (estado de
 * biblioteca y traductor, quitar) y los que se pueden añadir. Cada cambio se aplica al momento.
 */
@Composable
fun LanguagesSection(vm: LanguagesViewModel = koinViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val catalog by vm.libraryCatalog.collectAsStateWithLifecycle()
    val states by vm.libraryStates.collectAsStateWithLifecycle()
    val downloaded by vm.downloadedModels.collectAsStateWithLifecycle()
    val downloads by vm.modelDownloads.collectAsStateWithLifecycle()
    val p = prefs ?: return
    val native = vm.code(p.translationLanguage) ?: p.translationLanguage

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.translation_your_language), style = MaterialTheme.typography.labelLarge)
            LanguagePicker(native, vm.nativeOptions, onSelect = vm::setNative)
        }
        Text(stringResource(R.string.translation_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelRow(native, native in downloaded, downloads[native], deletable = false, vm)
        WifiOnlyRow(p.translationWifiOnly, vm::setWifiOnly)

        Text(stringResource(R.string.languages_learning_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        if (p.learningLanguages.isEmpty()) {
            Text(stringResource(R.string.languages_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        p.learningLanguages.forEach { lang ->
            LearningCard(lang, catalog[lang], states[lang] ?: LibraryPackState.NotInstalled, native, downloaded, downloads, vm)
        }

        val others = vm.learnable(catalog).filter { lang -> p.learningLanguages.none { LanguageTags.sameLanguage(it, lang) } }
        var showAll by rememberSaveable { mutableStateOf(false) }
        val shown = if (showAll) others else others.filter { it in catalog }
        if (others.isNotEmpty()) {
            Text(stringResource(R.string.languages_add_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            shown.forEach { lang ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Languages.name(lang), style = MaterialTheme.typography.titleSmall)
                            Text(availability(catalog[lang]), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val clash = LanguageTags.sameLanguage(lang, native)
                        TextButton(onClick = { vm.addLearning(lang) }, enabled = !clash) { Text(stringResource(R.string.common_add)) }
                    }
                }
            }
            if (!showAll && shown.size < others.size) {
                TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.languages_show_more, others.size - shown.size)) }
            }
        }
    }
}

/** Un idioma que se aprende: biblioteca (estado y acciones), traductor y «Quitar». */
@Composable
private fun LearningCard(
    lang: String,
    info: LibraryInfo?,
    state: LibraryPackState,
    native: String,
    downloaded: Set<String>,
    downloads: Map<String, ModelDownload>,
    vm: LanguagesViewModel,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Languages.name(lang), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.removeLearning(lang) }) { Text(stringResource(R.string.common_remove)) }
            }
            when (state) {
                LibraryPackState.NotInstalled ->
                    if (info == null) {
                        Text(stringResource(R.string.languages_coming_soon), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.library_not_downloaded, availability(info)),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { vm.downloadLibrary(lang) }) { Text(stringResource(R.string.common_download)) }
                        }
                    }
                is LibraryPackState.Downloading, is LibraryPackState.Installing -> LibraryProgress(state)
                is LibraryPackState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.updateAvailable && info != null) {
                            stringResource(
                                R.string.library_installed_update,
                                pluralStringResource(R.plurals.common_books, state.books, state.books),
                                pluralStringResource(R.plurals.common_books, info.books, info.books),
                            )
                        } else {
                            stringResource(R.string.library_installed, pluralStringResource(R.plurals.common_books, state.books, state.books))
                        },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    if (state.updateAvailable) TextButton(onClick = { vm.downloadLibrary(lang) }) { Text(stringResource(R.string.library_update)) }
                    TextButton(onClick = { vm.deleteLibrary(lang) }) { Text(stringResource(R.string.languages_remove_books)) }
                }
                is LibraryPackState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.error.toUiText().asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.downloadLibrary(lang) }) { Text(stringResource(R.string.common_retry)) }
                }
            }
            vm.neededModels(lang, native).filter { it != native }.forEach { code ->
                ModelRow(code, code in downloaded, downloads[code], deletable = true, vm)
            }
        }
    }
}

@Composable
private fun ModelRow(code: String, installed: Boolean, download: ModelDownload?, deletable: Boolean, vm: LanguagesViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (installed) R.string.translation_model_downloaded else R.string.translation_model_not_downloaded,
                    stringResource(R.string.onboarding_translator_of, Languages.nameInSentence(code)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            when {
                download == ModelDownload.Running -> Unit
                // El modelo inglés es el pivote de ML Kit: no se puede borrar.
                installed && deletable && !LanguageTags.sameLanguage(code, "en") ->
                    TextButton(onClick = { vm.deleteModel(code) }) { Text(stringResource(R.string.common_delete)) }
                !installed -> TextButton(onClick = { vm.downloadModel(code) }) { Text(stringResource(R.string.common_download)) }
            }
        }
        when (download) {
            ModelDownload.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ModelDownload.Failed -> Text(download.message.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
    }
}
