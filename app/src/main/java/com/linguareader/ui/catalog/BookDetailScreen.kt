package com.linguareader.ui.catalog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.core.model.OriginalOpenMode
import com.linguareader.engine.audio.AudioGenerationService
import com.linguareader.engine.audio.BookAudioState
import com.linguareader.engine.audio.BookAudioStatus
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.ui.common.Labels
import com.linguareader.ui.common.Languages
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

/**
 * Ficha de un libro: portada, datos, progreso y, antes de abrirlo, lo que se puede dejar preparado en segundo
 * plano (traducción completa y, con Supertonic, el audio de todo el libro). «Leer» abre el lector; «Leer y
 * escuchar» lo abre y arranca la voz.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onRead: (bookId: String, listen: Boolean) -> Unit,
    onViewOriginal: (bookId: String, title: String) -> Unit,
    onOpenVoiceSettings: () -> Unit,
    vm: BookDetailViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var chooseMode by remember { mutableStateOf<Boolean>(false) }
    var confirmDeleteAudio by remember { mutableStateOf(false) }
    // Antes de generar audio: avisar si otro libro está generando (solo uno a la vez) y, la primera vez,
    // preguntar si seguir con la pantalla apagada.
    var confirmReplace by remember { mutableStateOf(false) }
    var askScreenOff by remember { mutableStateOf(false) }
    val s = state.summary

    val requestStartAudio = {
        when {
            state.otherGenerating != null -> confirmReplace = true
            !state.generateScreenOffChosen -> askScreenOff = true
            else -> vm.startAudio()
        }
    }
    AudioGenerationServiceEffect(state, s?.title.orEmpty())

    // Documentos con visor propio (PDF): «Leer» respeta la elección de Ajustes › Archivos (o pregunta).
    val open = { listen: Boolean ->
        when {
            s == null -> Unit
            state.original?.viewable != true || listen -> onRead(s.id, listen)
            state.originalOpenMode == OriginalOpenMode.READER -> onRead(s.id, false)
            state.originalOpenMode == OriginalOpenMode.ORIGINAL -> onViewOriginal(s.id, s.title)
            else -> chooseMode = true
        }
    }
    if (chooseMode && s != null) {
        OpenModeSheet(
            item = CatalogItem(summary = s, progress = state.progress, original = state.original),
            onChoose = { mode, remember ->
                chooseMode = false
                if (remember) vm.setOriginalOpenMode(mode)
                if (mode == OriginalOpenMode.ORIGINAL) onViewOriginal(s.id, s.title) else onRead(s.id, false)
            },
            onDismiss = { chooseMode = false },
        )
    }
    state.otherGenerating?.takeIf { confirmReplace }?.let { (_, otherTitle) ->
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.book_audio_other_running_title)) },
            text = { Text(stringResource(R.string.book_audio_other_running_message, otherTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    if (state.generateScreenOffChosen) vm.startAudio() else askScreenOff = true
                }) { Text(stringResource(R.string.book_audio_other_running_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (askScreenOff) {
        GenerateAudioDialog(
            screenOffDefault = state.generateScreenOff,
            onConfirm = { screenOff, remember ->
                askScreenOff = false
                vm.startAudio(screenOff, remember)
            },
            onDismiss = { askScreenOff = false },
        )
    }
    if (confirmDeleteAudio) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAudio = false },
            title = { Text(stringResource(R.string.book_audio_delete)) },
            text = { Text(stringResource(R.string.book_audio_delete_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDeleteAudio = false; vm.deleteAudio() }) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDeleteAudio = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding).padding(16.dp))
            s == null -> Text(stringResource(R.string.book_edit_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(16.dp))
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.width(132.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
                        state.cover?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            ?: GeneratedCover(s.title, s.learningLanguage)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (s.authors.isNotEmpty()) Text(s.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = muted)
                        Text("${Languages.name(s.learningLanguage)} · ${s.cefrLevel.name}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOf(
                                stringResource(R.string.catalog_minutes, s.estimatedMinutes),
                                stringResource(R.string.book_detail_words, s.wordCount),
                                stringResource(R.string.book_detail_passages, s.passageCount),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                        if (state.imported && !state.fromLibrary) Text(stringResource(R.string.catalog_imported), style = MaterialTheme.typography.bodySmall, color = muted)
                        state.progress?.let { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.common_percent, (p * 100).toInt()), style = MaterialTheme.typography.labelSmall, color = muted)
                            }
                        }
                    }
                }
                s.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(false) }, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_menu_book), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.progress != null) stringResource(R.string.book_detail_continue) else stringResource(R.string.book_detail_read))
                    }
                    Button(onClick = { open(true) }, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.book_detail_read_listen))
                    }
                }
                if (state.original?.viewable == true) {
                    TextButton(onClick = { onViewOriginal(s.id, s.title) }) {
                        Icon(painterResource(R.drawable.ic_find_in_page), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.catalog_view_original, state.original!!.format.uppercase()))
                    }
                }

                TranslationCard(state, vm)
                state.audio?.let { AudioCard(it, vm, onOpenVoiceSettings, onStart = requestStartAudio, onDelete = { confirmDeleteAudio = true }) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Traducción completa del libro al idioma elegido: estado, progreso y empezar / continuar / cancelar. */
@Composable
private fun TranslationCard(state: BookDetailUiState, vm: BookDetailViewModel) {
    val s = state.summary ?: return
    val target = state.translationTarget
    val language = Languages.nameInSentence(target)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_translate), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.import_translation_into, Languages.name(target)), style = MaterialTheme.typography.titleMedium)
            }
            val job = state.translationJob
            val pause = state.translationPause
            when {
                state.sameLanguage -> Text(stringResource(R.string.translation_unavailable_same_language), style = MaterialTheme.typography.bodyMedium, color = muted)
                state.bundledTranslation -> Text(stringResource(R.string.book_detail_translation_included), style = MaterialTheme.typography.bodyMedium, color = muted)
                job != null -> TranslationJobRow(s.id, job) { action ->
                    when (action) {
                        is TranslationAction.Cancel -> vm.cancelTranslation()
                        is TranslationAction.Retry -> vm.startTranslation(action.target)
                        is TranslationAction.Dismiss -> vm.dismissTranslationError()
                    }
                }
                state.machineTranslated -> Text(stringResource(R.string.book_detail_translation_done, language), style = MaterialTheme.typography.bodyMedium, color = muted)
                pause != null -> TranslationPausedRow(s.id, pause) { action ->
                    if (action is TranslationAction.Retry) vm.startTranslation(action.target)
                }
                !state.imported -> Text(stringResource(R.string.book_detail_translation_on_tap), style = MaterialTheme.typography.bodyMedium, color = muted)
                else -> {
                    Text(stringResource(R.string.book_detail_translation_hint), style = MaterialTheme.typography.bodyMedium, color = muted)
                    FilledTonalButton(onClick = { vm.startTranslation(target) }) { Text(stringResource(R.string.book_detail_translate_start)) }
                }
            }
        }
    }
}

/** Audio completo del libro con Supertonic (y tiempos por palabra si hay alineador): estado en disco y controles. */
@Composable
private fun AudioCard(audio: AudioSection, vm: BookDetailViewModel, onOpenVoiceSettings: () -> Unit, onStart: () -> Unit, onDelete: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_record_voice_over), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.book_audio_title), style = MaterialTheme.typography.titleMedium)
            }
            val narration = audio.narration
            when {
                narration != null -> NarrationState(narration, onDownload = vm::downloadNarration)
                audio.unavailable == AudioUnavailable.NOT_SUPERTONIC -> {
                    Text(stringResource(R.string.book_audio_needs_supertonic), style = MaterialTheme.typography.bodyMedium, color = muted)
                    TextButton(onClick = onOpenVoiceSettings) { Text(stringResource(R.string.book_audio_open_voice_settings)) }
                }
                audio.unavailable == AudioUnavailable.MODEL_NOT_INSTALLED -> {
                    Text(stringResource(R.string.book_audio_model_missing), style = MaterialTheme.typography.bodyMedium, color = muted)
                    TextButton(onClick = onOpenVoiceSettings) { Text(stringResource(R.string.book_audio_open_voice_settings)) }
                }
                audio.unavailable == AudioUnavailable.LANGUAGE_NOT_SUPPORTED ->
                    Text(stringResource(R.string.book_audio_language_unsupported), style = MaterialTheme.typography.bodyMedium, color = muted)
                else -> AudioControls(audio, vm, onStart, onDelete)
            }
        }
    }
}

/** Libro con narración pregrabada (voz de estudio y tiempos por palabra del servidor): no hay nada que generar. */
@Composable
private fun NarrationState(narration: NarrationSection, onDownload: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        narration.ready -> {
            Text(stringResource(R.string.book_audio_narration_ready, mb(narration.bytes)), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.book_audio_narration_ready_hint), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        narration.downloading -> {
            Text(stringResource(R.string.book_audio_narration_downloading), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        else -> {
            Text(stringResource(R.string.book_audio_narration_available, mb(narration.bytes)), style = MaterialTheme.typography.bodyMedium, color = muted)
            FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.book_audio_narration_download)) }
        }
    }
}

@Composable
private fun AudioControls(audio: AudioSection, vm: BookDetailViewModel, onStart: () -> Unit, onDelete: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val job = audio.job
    val status = audio.status
    Text(
        if (status?.wordTimings == true) stringResource(R.string.book_audio_hint_words) else stringResource(R.string.book_audio_hint_sentences),
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    when (job) {
        is BookAudioState.Preparing -> {
            Text(stringResource(R.string.book_audio_preparing), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelAudio) { Text(stringResource(R.string.book_audio_cancel)) }
        }
        is BookAudioState.Running -> {
            val fraction = if (job.total == 0) 0f else job.done.toFloat() / job.total
            Text(
                if (job.waiting) stringResource(R.string.book_audio_waiting, (fraction * 100).toInt(), job.done, job.total)
                else stringResource(R.string.book_audio_running, (fraction * 100).toInt(), job.done, job.total),
                style = MaterialTheme.typography.labelMedium,
            )
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelAudio) { Text(stringResource(R.string.book_audio_cancel)) }
        }
        is BookAudioState.Failed -> {
            Text(Labels.audioUnavailable(job.reason, ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = onStart) { Text(stringResource(R.string.common_retry)) }
                TextButton(onClick = vm::dismissAudioError) { Text(stringResource(R.string.common_close)) }
            }
        }
        null -> AudioIdle(status, onStart, onDelete)
    }
}

@Composable
private fun AudioIdle(status: BookAudioStatus?, onStart: () -> Unit, onDelete: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (status == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val fraction = if (status.total == 0) 0f else status.ready.toFloat() / status.total
    when {
        status.complete -> {
            Text(stringResource(R.string.book_audio_complete, status.total, mb(status.bytes)), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onDelete) { Text(stringResource(R.string.book_audio_delete)) }
        }
        status.ready > 0 -> {
            Text(stringResource(R.string.book_audio_partial, (fraction * 100).toInt(), status.ready, status.total, mb(status.bytes)), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Row {
                FilledTonalButton(onClick = onStart) { Text(stringResource(R.string.book_audio_continue)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.book_audio_delete)) }
            }
        }
        else -> {
            Text(stringResource(R.string.book_audio_none, status.total), style = MaterialTheme.typography.bodyMedium, color = muted)
            FilledTonalButton(onClick = onStart) { Text(stringResource(R.string.book_audio_start)) }
        }
    }
}

/**
 * Al pulsar «Generar audio» (hasta marcar «No volver a preguntar»): explica que no hace falta esperar a todo el audio
 * para escuchar y ofrece seguir generando aunque se apague la pantalla (servicio en primer plano con notificación).
 */
@Composable
private fun GenerateAudioDialog(screenOffDefault: Boolean, onConfirm: (screenOff: Boolean, remember: Boolean) -> Unit, onDismiss: () -> Unit) {
    var screenOff by remember { mutableStateOf(screenOffDefault) }
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_audio_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.book_audio_dialog_message))
                Spacer(Modifier.height(8.dp))
                CheckRow(stringResource(R.string.book_audio_screen_off_option), screenOff) { screenOff = it }
                CheckRow(stringResource(R.string.book_audio_dont_ask_again), dontAsk) { dontAsk = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(screenOff, dontAsk) }) { Text(stringResource(R.string.book_audio_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange, role = Role.Checkbox),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * Generación con la pantalla apagada: mientras este libro genera audio y el usuario lo aceptó, corre el servicio en
 * primer plano (se detiene solo al terminar). La primera vez pide el permiso de notificaciones (Android 13+); sin él
 * el servicio funciona igual, sin aviso visible.
 */
@Composable
private fun AudioGenerationServiceEffect(state: BookDetailUiState, title: String) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val job = state.audio?.job
    val active = (job is BookAudioState.Preparing || job is BookAudioState.Running) && state.generateScreenOff
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AudioGenerationService.start(context, title)
    }
    LaunchedEffect(state.generateScreenOff) {
        if (!state.generateScreenOff) AudioGenerationService.stop(context)
    }
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
