package com.linguareader.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.Passage
import com.linguareader.core.model.Sentence
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.reader.HighlightGranularity
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.PlaybackStatus
import com.linguareader.ui.common.Labels
import com.linguareader.ui.theme.LocalReaderColors
import kotlinx.coroutines.flow.filterNotNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit, vm: ReaderViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Volver") }
                },
                title = {
                    Column {
                        Text(ui.book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ui.chapter?.let {
                            Text(it.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                actions = {
                    val bookmarked = ui.bookmarks.isNotEmpty() && vm.currentBookmark() != null
                    IconButton(onClick = vm::toggleBookmark) {
                        Icon(
                            painterResource(if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border),
                            contentDescription = if (bookmarked) "Quitar marcador" else "Añadir marcador",
                        )
                    }
                    IconButton(onClick = { showChapters = true }) {
                        Icon(painterResource(R.drawable.ic_list), contentDescription = "Capítulos y marcadores")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(painterResource(R.drawable.ic_tune), contentDescription = "Ajustes de lectura")
                    }
                },
            )
        },
        bottomBar = {
            PlayerControls(
                playback = playback,
                sentenceCount = ui.passage?.sentences?.size ?: 0,
                speed = prefs.speechRate,
                onPlayPause = vm::togglePlay,
                onPrevious = vm::previousSentence,
                onRepeat = vm::repeatSentence,
                onNext = vm::nextSentence,
                onSpeed = { rate -> vm.updatePreferences { it.copy(speechRate = rate) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LinearProgressIndicator(
                progress = { if (ui.totalPassages == 0) 0f else (ui.passageIndex + 1f) / ui.totalPassages },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
            AudioBanner(playback, languageTag = ui.book?.learningLanguage, onRetry = vm::retryAudio)
            when {
                ui.error != null -> Text(ui.error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                ui.passage == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> PassageContent(ui, prefs, playback, vm)
            }
        }
    }

    ui.wordCard?.let { card ->
        WordCardSheet(
            card = card,
            onDismiss = vm::dismissCards,
            onSpeak = vm::speak,
            onSave = vm::saveWord,
            onReadFromHere = { vm.readFromToken(card.tokenId) },
            onResume = vm::resumeAfterCard,
        )
    }
    ui.sentenceCard?.let { card ->
        SentenceCardSheet(card = card, onDismiss = vm::dismissCards, onSpeak = vm::speak, onResume = vm::resumeAfterCard)
    }
    if (showSettings) {
        ReaderSettingsSheet(
            prefs = prefs,
            voices = ui.voices,
            bookLanguage = ui.book?.learningLanguage,
            onUpdate = vm::updatePreferences,
            onVoice = vm::setVoice,
            onDismiss = { showSettings = false },
        )
    }
    if (showChapters) {
        val book = ui.book
        if (book != null) {
            ChaptersSheet(
                book = book,
                currentPassageId = ui.passage?.id,
                bookmarks = ui.bookmarks,
                translationLanguage = prefs.translationLanguage,
                onSelectPassage = { id -> vm.goToPassage(id); showChapters = false },
                onSelectBookmark = { b -> vm.goToPassage(b.passageId, b.charOffset); showChapters = false },
                onDeleteBookmark = vm::deleteBookmark,
                onDismiss = { showChapters = false },
            )
        }
    }
}

@Composable
private fun AudioBanner(playback: PlaybackState, languageTag: String?, onRetry: () -> Unit) {
    val context = LocalContext.current
    val readiness = playback.readiness
    val message = when {
        readiness is EngineReadiness.Unavailable -> Labels.audioUnavailable(readiness.reason, readiness.languageTag)
        playback.status == PlaybackStatus.ERROR -> "El motor de voz ha fallado (código ${playback.errorCode}). Pulsa reproducir para reintentar."
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (readiness is EngineReadiness.Unavailable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }) { Text("Instalar voz") }
                    TextButton(onClick = onRetry) { Text("Reintentar") }
                }
            }
        }
    }
}

@Composable
private fun PassageContent(ui: ReaderUiState, prefs: UserPreferences, playback: PlaybackState, vm: ReaderViewModel) {
    val passage = ui.passage ?: return
    val colors = LocalReaderColors.current
    val anchor = remember { ActiveTokenAnchor() }
    val listState = rememberLazyListState()
    var container by remember { mutableStateOf<Rect?>(null) }

    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Serif,
        fontSize = (20 * prefs.fontScale).sp,
        lineHeight = (20 * prefs.fontScale * prefs.lineHeight).sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
    val translationStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = (16 * prefs.fontScale).sp,
        lineHeight = (16 * prefs.fontScale * prefs.lineHeight).sp,
        color = colors.translation,
        fontStyle = FontStyle.Italic,
    )

    // Resaltado honesto: palabra solo con eventos fiables; frase si no; nada si no hay audio activo.
    val isCurrent = playback.passageId == passage.id &&
        (playback.status == PlaybackStatus.PLAYING || playback.status == PlaybackStatus.PAUSED)
    val activeSentence = passage.sentences.getOrNull(playback.sentenceIndex)
        ?.takeIf { isCurrent && playback.granularity != HighlightGranularity.NONE }
        ?.let { it.rangeStart until it.rangeEnd }
    val activeWord = playback.activeTokenId
        ?.takeIf { isCurrent && playback.granularity == HighlightGranularity.WORD }
        ?.let(passage::tokenById)
        ?.let { it.rangeStart until it.rangeEnd }

    val showImage = passage.image != null && (prefs.showIllustrations || prefs.displayMode == DisplayMode.ILLUSTRATED)
    val perSentence = prefs.displayMode == DisplayMode.TRANSLATION_BELOW || prefs.displayMode == DisplayMode.TRANSLATION_ON_TAP
    val firstSentenceItem = 1 + if (showImage) 1 else 0

    // Auto-scroll guiado por la posición visible del token/frase (no por el reproductor).
    LaunchedEffect(listState, prefs.autoScroll, prefs.reduceMotion) {
        if (!prefs.autoScroll) return@LaunchedEffect
        snapshotFlow { anchor.focusBounds }.filterNotNull().collect { focus ->
            val c = container ?: return@collect
            if (listState.isScrollInProgress) return@collect
            val margin = c.height * 0.15f
            if (focus.top < c.top + margin || focus.bottom > c.bottom - margin) {
                val delta = focus.top - (c.top + c.height * 0.3f)
                if (prefs.reduceMotion) listState.scrollBy(delta) else listState.animateScrollBy(delta)
            }
        }
    }
    // En modos por frase, la frase activa puede no estar compuesta todavía: desplazar al ítem.
    LaunchedEffect(playback.sentenceIndex, playback.passageId, perSentence, prefs.autoScroll) {
        if (!perSentence || !prefs.autoScroll || !isCurrent) return@LaunchedEffect
        val index = firstSentenceItem + playback.sentenceIndex
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            if (prefs.reduceMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
        }
    }
    LaunchedEffect(passage.id) { listState.scrollToItem(0) }

    fun tokenText(start: Int, end: Int, sentenceIdForAction: String, modifier: Modifier = Modifier): @Composable () -> Unit = {
        TokenText(
            passageText = passage.text,
            segmentStart = start,
            segmentEnd = end,
            tokens = passage.tokens.filter { it.rangeStart >= start && it.rangeEnd <= end },
            activeWord = activeWord,
            activeSentence = activeSentence,
            highlightStyle = prefs.highlightStyle,
            colors = colors,
            textStyle = textStyle,
            anchor = anchor,
            onTap = { vm.onTokenTap(it.id) },
            onLongPress = { vm.onTokenLongPress(it.id) },
            accessibilityActionLabel = "Traducir frase",
            onAccessibilityAction = { vm.onSentenceAction(sentenceIdForAction) },
            modifier = modifier,
        )
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { container = it.boundsInRoot() }) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                val chapterTranslation = ui.chapter?.titleTranslations?.let { LanguageTags.lookup(it, prefs.translationLanguage) }
                Column {
                    Text(
                        "Pasaje ${ui.passageIndex + 1} de ${ui.totalPassages}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (chapterTranslation != null && prefs.displayMode != DisplayMode.TEXT_ONLY) {
                        Text(chapterTranslation, style = translationStyle)
                    }
                }
            }
            if (showImage) {
                item(key = "image-${passage.id}") {
                    PassageImage(ui.image, passage, large = prefs.displayMode == DisplayMode.ILLUSTRATED)
                }
            }
            when (prefs.displayMode) {
                DisplayMode.TEXT_ONLY, DisplayMode.ILLUSTRATED -> item(key = "text-${passage.id}") {
                    tokenText(0, passage.text.length, passage.sentences.first().id)()
                }
                DisplayMode.TRANSLATION_BELOW, DisplayMode.TRANSLATION_ON_TAP -> sentenceItems(
                    passage = passage,
                    showTranslation = { s -> prefs.displayMode == DisplayMode.TRANSLATION_BELOW || s.id in ui.revealedSentences },
                    translations = ui.sentenceTranslations,
                    target = prefs.translationLanguage,
                    translationStyle = translationStyle,
                    onToggle = vm::toggleRevealed,
                    tapMode = prefs.displayMode == DisplayMode.TRANSLATION_ON_TAP,
                    content = { s -> tokenText(s.rangeStart, s.rangeEnd, s.id)() },
                )
                DisplayMode.BILINGUAL_BLOCKS -> {
                    item(key = "orig-${passage.id}") {
                        BlockCard { tokenText(0, passage.text.length, passage.sentences.first().id)() }
                    }
                    item(key = "trans-${passage.id}") {
                        BlockCard(tinted = true) {
                            val block = passage.sentences.map { ui.sentenceTranslations[it.id] }
                            if (block.all { it is TranslationResult.Translated }) {
                                Text(block.joinToString(" ") { (it as TranslationResult.Translated).text }, style = translationStyle.copy(fontStyle = FontStyle.Normal))
                            } else {
                                val reason = block.firstNotNullOfOrNull { it as? TranslationResult.Unavailable }?.reason
                                    ?: TranslationResult.Reason.NO_ENTRY
                                Text(Labels.unavailable(reason, prefs.translationLanguage), style = translationStyle)
                            }
                        }
                    }
                }
            }
            item(key = "nav") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = vm::previousPassage, enabled = ui.hasPrevious) {
                        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null)
                        Text("Anterior")
                    }
                    OutlinedButton(onClick = vm::nextPassage, enabled = ui.hasNext) {
                        Text("Siguiente")
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
                    }
                }
            }
            if (!ui.hasNext) {
                item(key = "credits") { Credits(ui) }
            }
        }
        if (prefs.fingerPointerEnabled) {
            FingerPointerOverlay(
                target = anchor.tokenBounds,
                container = container,
                reduceMotion = prefs.reduceMotion,
                fill = colors.pointer,
                outline = colors.pointerOutline,
            )
        }
    }
}

private fun LazyListScope.sentenceItems(
    passage: Passage,
    showTranslation: (Sentence) -> Boolean,
    translations: Map<String, TranslationResult>,
    target: String,
    translationStyle: TextStyle,
    onToggle: (String) -> Unit,
    tapMode: Boolean,
    content: @Composable (Sentence) -> Unit,
) {
    itemsIndexed(passage.sentences, key = { _, s -> "${passage.id}-${s.id}" }) { _, s ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content(s)
            if (showTranslation(s)) {
                val text = when (val t = translations[s.id]) {
                    is TranslationResult.Translated -> t.text
                    is TranslationResult.Unavailable -> Labels.unavailable(t.reason, target)
                    null -> "…"
                }
                Text(
                    text,
                    style = translationStyle,
                    modifier = if (tapMode) Modifier.clickable { onToggle(s.id) } else Modifier,
                )
            }
        }
    }
}

@Composable
private fun PassageImage(image: ImageBitmap?, passage: Passage, large: Boolean) {
    val media = passage.image ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (large) 4f / 3f else 16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (image != null) {
                Image(bitmap = image, contentDescription = media.alt, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        media.credit?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BlockCard(tinted: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (tinted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Credits(ui: ReaderUiState) {
    val book = ui.book ?: return
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Créditos", style = MaterialTheme.typography.titleSmall)
        Text(book.sourceAttribution, style = MaterialTheme.typography.bodySmall)
        if (book.authors.isNotEmpty()) Text("Autoría: ${book.authors.joinToString()}", style = MaterialTheme.typography.bodySmall)
        book.adaptationCredit?.let { Text("Adaptación: $it", style = MaterialTheme.typography.bodySmall) }
        Text("Licencia: ${book.license.name ?: book.license.id}", style = MaterialTheme.typography.bodySmall)
    }
}
