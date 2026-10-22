package com.linguareader.ui.reader

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.model.Book
import com.linguareader.core.model.ScreenOrientation
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.PlaybackStatus
import com.linguareader.data.library.LibraryState
import com.linguareader.engine.audio.ReadingService
import com.linguareader.engine.translation.ModelDownload
import com.linguareader.ui.common.Labels
import com.linguareader.ui.common.Languages
import com.linguareader.ui.reader.paging.PagedBook
import com.linguareader.ui.reader.paging.TextPos
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    startInOriginal: Boolean = false,
    vm: ReaderViewModel = hiltViewModel(),
    pdfVm: OriginalPdfViewModel = hiltViewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val pdfAvailable by pdfVm.available.collectAsStateWithLifecycle()
    // «Ver original»: las páginas del PDF en lugar del texto paginado, con la misma voz, traducción y progreso.
    var originalMode by rememberSaveable { mutableStateOf(startInOriginal) }
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val anchor by vm.anchor.collectAsStateWithLifecycle()
    val translations by vm.translations.collectAsStateWithLifecycle()
    val images by vm.images.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val vocabulary by vm.bookVocabulary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.catalog_share_book)
    val scope = rememberCoroutineScope()

    // Pantalla completa por defecto: los controles salen al tocar el texto (y, sobre una palabra, su minitarjeta).
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    // Caja de la palabra tocada y zonas de pantalla (coordenadas raíz) para colocar la minitarjeta.
    var tappedWord by remember { mutableStateOf<Rect?>(null) }
    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    var textArea by remember { mutableStateOf(Rect.Zero) }
    var topBarHeight by remember { mutableIntStateOf(0) }
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf(ReaderSheet.NONE) }
    var paged by remember { mutableStateOf<PagedBook?>(null) }
    val pager = rememberPagerState { paged?.size ?: 0 }
    val navigator = remember(pager) { PageNavigator(pager) { prefs.reduceMotion } }
    val currentPage = paged?.pages?.getOrNull(pager.currentPage)
    val miniCard = ui.wordCard?.takeIf { !it.expanded }

    // La minitarjeta apunta a una palabra de esta página: al cambiar de página deja de tener sentido.
    LaunchedEffect(pager.currentPage) { if (miniCard != null) vm.dismissCards() }
    // Tras girar la pantalla se pierde la caja de la palabra (no se guarda): la minitarjeta ya no se puede colocar.
    LaunchedEffect(miniCard != null, tappedWord == null) { if (miniCard != null && tappedWord == null) vm.dismissCards() }
    // «Atrás» con la minitarjeta abierta la cierra, como un toque en el texto, en vez de salir del libro.
    BackHandler(enabled = miniCard != null) {
        controlsVisible = false
        vm.dismissCards()
    }

    ImmersiveMode(showSystemBars = controlsVisible)
    ReadingServiceEffect(playback, prefs, ui.book?.title.orEmpty())
    KeepScreenOn(prefs.keepScreenOn)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onGloballyPositioned { rootBounds = it.boundsInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { textArea = it.boundsInRoot() }) {
                val book = ui.book
                when {
                    ui.error != null -> Text(ui.error!!.asString(), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                    // Sin la posición guardada aún no se pagina: se abriría en la página 1 y se perdería el punto de lectura.
                    book == null || anchor == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    originalMode -> ReaderPdfContent(
                        pdf = pdfVm,
                        book = book,
                        playback = playback,
                        prefs = prefs,
                        tappedTokenId = ui.wordCard?.tokenId,
                        anchor = anchor,
                        jumps = vm.jumps,
                        onTap = { hit ->
                            if (controlsVisible || ui.wordCard != null) {
                                controlsVisible = false
                                vm.dismissCards()
                            } else {
                                controlsVisible = true
                                if (hit != null) {
                                    tappedWord = hit.bounds
                                    vm.onTokenTap(hit.token.id)
                                }
                            }
                        },
                        onUserMoved = { pos -> vm.onPageSettled(pos, setOf(pos.passage), user = true) },
                        onShowText = { originalMode = false },
                        indicatorPadding = 16.dp,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
                    )
                    else -> PagedReaderContent(
                        book = book,
                        prefs = prefs,
                        playback = playback,
                        anchor = anchor,
                        translations = translations,
                        images = images,
                        jumps = vm.jumps,
                        navigator = navigator,
                        paged = paged,
                        onPaged = { paged = it },
                        onSettled = { page, user ->
                            vm.onPageSettled(page.start, page.allItems.map { it.passage }.toSet(), user)
                        },
                        // Un toque abre controles (y la minitarjeta si cae en una palabra); el siguiente lo cierra todo.
                        onTap = { hit ->
                            if (controlsVisible || ui.wordCard != null) {
                                controlsVisible = false
                                vm.dismissCards()
                            } else {
                                controlsVisible = true
                                if (hit != null) {
                                    tappedWord = hit.bounds
                                    vm.onTokenTap(hit.token.id)
                                }
                            }
                        },
                        onUserDrag = vm::pause,
                        onSentenceAction = vm::onSentenceAction,
                        // Márgenes estables: no cambian al mostrar u ocultar las barras del sistema (no se re-pagina).
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                            .padding(horizontal = 24.dp)
                            .padding(top = 16.dp, bottom = 12.dp)
                            .then(if (prefs.ttsMode) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)),
                    )
                }
                // Barra inferior (sobre el texto, encima del reproductor si lo hay).
                SlideFromEdge(visible = controlsVisible && paged != null && !originalMode, fromBottom = true, modifier = Modifier.align(Alignment.BottomCenter)) {
                    val book = paged
                    if (book != null) Box(Modifier.onSizeChanged { bottomBarHeight = it.height }) {
                        ReaderBottomBar(
                            paged = book,
                            page = pager.currentPage,
                            orientation = prefs.orientation,
                            bookmarked = currentPage != null && ui.bookmarks.any { b ->
                                val pos = TextPos(b.passageIndex, b.charOffset)
                                pos >= currentPage.start && pos < book.endOf(pager.currentPage)
                            },
                            withNavigationInset = !prefs.ttsMode,
                            onSeek = { target -> scope.launch { navigator.goTo(target, user = true, animate = false) } },
                            onOrientation = { vm.updatePreferences { it.copy(orientation = it.orientation.next()) } },
                            onBookmark = {
                                val page = currentPage ?: return@ReaderBottomBar
                                val end = book.endOf(pager.currentPage)
                                val existing = ui.bookmarks.firstOrNull { b ->
                                    val pos = TextPos(b.passageIndex, b.charOffset)
                                    pos >= page.start && pos < end
                                }
                                if (existing != null) vm.deleteBookmark(existing.id) else vm.addBookmark(page.start)
                            },
                        )
                    }
                }
            }
            if (prefs.ttsMode) {
                PlayerControls(
                    playback = playback,
                    sentenceCount = ui.passage?.sentences?.size ?: 0,
                    speed = prefs.speechRate,
                    onPlayPause = vm::togglePlay,
                    onPrevious = vm::previousSentence,
                    onRepeat = vm::repeatSentence,
                    onNext = vm::nextSentence,
                    onSpeed = { rate -> vm.updatePreferences { it.copy(speechRate = rate) } },
                    compact = true,
                    voices = ui.voices,
                    selectedVoice = vm.selectedVoice(),
                    automaticVoice = !vm.isSupertonicActive(),
                    onVoice = vm::setVoice,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(Modifier.onSizeChanged { topBarHeight = it.height }) {
                ReaderTopBar(
                    book = ui.book,
                    chapterTitle = currentPage?.takeIf { !originalMode }?.let { p -> ui.book?.chapters?.getOrNull(p.chapter)?.title },
                    chapterPage = paged?.takeIf { !originalMode }?.let { pb ->
                        val range = pb.chapterRange(pager.currentPage)
                        if (range.isEmpty()) null else (pager.currentPage - range.first + 1) to (range.last - range.first + 1)
                    },
                    ttsMode = prefs.ttsMode,
                    library = library,
                    hasIndex = true,
                    original = if (pdfAvailable || originalMode) originalMode else null,
                    onToggleOriginal = {
                        originalMode = !originalMode
                        vm.dismissCards()
                    },
                    onBack = onBack,
                    onToggleTts = {
                        val enable = !prefs.ttsMode
                        vm.updatePreferences { it.copy(ttsMode = enable, ttsModeChosen = true) }
                        if (enable) currentPage?.let { vm.playFrom(it.start) } else vm.pause()
                    },
                    onSearch = { sheet = ReaderSheet.SEARCH },
                    onIndex = { sheet = ReaderSheet.INDEX },
                    onSettings = { sheet = ReaderSheet.SETTINGS },
                    onShare = {
                        scope.launch {
                            val uri = vm.exportForSharing() ?: return@launch
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, ui.book?.title)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        }
                    },
                    onShelf = vm::setInShelf,
                    onNewCollection = { sheet = ReaderSheet.NEW_COLLECTION },
                    onTrash = { sheet = ReaderSheet.TRASH },
                    onAbout = { sheet = ReaderSheet.ABOUT },
                )
                AudioBanner(playback, onRetry = vm::retryAudio, onOpenSettings = onOpenSettings, ttsMode = prefs.ttsMode)
                val translationDownloads by vm.translationDownloads.collectAsStateWithLifecycle()
                TranslationModelBanner(ui.missingTranslationModels, translationDownloads, onDownload = vm::downloadTranslationModels)
            }
        }

        val word = tappedWord
        if (miniCard != null && word != null) {
            // Entre las barras de controles, que se muestran a la vez que la tarjeta.
            val origin = rootBounds.topLeft
            WordPopup(
                card = miniCard,
                word = word.translate(-origin),
                top = maxOf(textArea.top - origin.y, topBarHeight.toFloat()),
                bottom = textArea.bottom - origin.y - bottomBarHeight,
                onMoreInfo = vm::expandWordCard,
                onSave = vm::saveWord,
                onReadFromHere = {
                    controlsVisible = false
                    vm.readFromToken(miniCard.tokenId)
                },
            )
        }

        if (ui.preparing) PreparingOverlay(reduceMotion = prefs.reduceMotion, title = ui.book?.title.orEmpty())
    }

    ui.wordCard?.takeIf { it.expanded }?.let { card ->
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
    val book = ui.book
    when (sheet) {
        ReaderSheet.NONE -> Unit
        ReaderSheet.SETTINGS -> ReaderSettingsSheet(
            prefs = prefs,
            voices = ui.voices,
            selectedVoice = vm.selectedVoice(),
            onUpdate = vm::updatePreferences,
            onVoice = vm::setVoice,
            onDismiss = { sheet = ReaderSheet.NONE },
        )
        ReaderSheet.INDEX -> if (book != null) {
            val pb = paged
            IndexSheet(
                book = book,
                currentChapter = currentPage?.chapter,
                chapterPages = pb?.pages?.withIndex()?.groupBy { it.value.chapter }?.mapValues { it.value.first().index }.orEmpty(),
                pageOf = { passage, offset -> pb?.pageOf(TextPos(passage, offset)) },
                bookmarks = ui.bookmarks,
                vocabulary = vocabulary,
                translationLanguage = prefs.translationLanguage,
                onSelectChapter = { c ->
                    book.chapters.getOrNull(c)?.passages?.firstOrNull()?.let { vm.goToPassage(it.id) }
                    sheet = ReaderSheet.NONE
                },
                onSelectBookmark = { b -> vm.goToBookmark(b); sheet = ReaderSheet.NONE },
                onDeleteBookmark = vm::deleteBookmark,
                onSelectWord = { w -> vm.goToContext(w); sheet = ReaderSheet.NONE },
                onDismiss = { sheet = ReaderSheet.NONE },
            )
        }
        ReaderSheet.SEARCH -> SearchSheet(
            hits = ui.searchHits,
            searching = ui.searching,
            onQuery = vm::search,
            onSelect = { hit -> vm.goToSearchHit(hit); sheet = ReaderSheet.NONE },
            onDismiss = { vm.clearSearch(); sheet = ReaderSheet.NONE },
        )
        ReaderSheet.ABOUT -> if (book != null) {
            AboutSheet(
                book = book,
                imported = vm.isImported,
                progress = paged?.let { (pager.currentPage + 1f) / it.size.coerceAtLeast(1) },
                pageCount = paged?.size,
                shelves = library.shelves.filter { book.id in it.bookIds }.map { Labels.shelf(it) },
                onDismiss = { sheet = ReaderSheet.NONE },
            )
        }
        ReaderSheet.NEW_COLLECTION -> NewCollectionDialog(
            onCreate = { name -> vm.createCollection(name); sheet = ReaderSheet.NONE },
            onDismiss = { sheet = ReaderSheet.NONE },
        )
        ReaderSheet.TRASH -> AlertDialog(
            onDismissRequest = { sheet = ReaderSheet.NONE },
            title = { Text(stringResource(R.string.catalog_move_to_trash)) },
            text = { Text(stringResource(R.string.reader_trash_message, book?.title.orEmpty())) },
            confirmButton = {
                TextButton(onClick = { sheet = ReaderSheet.NONE; vm.moveToTrash(onBack) }) { Text(stringResource(R.string.catalog_move_to_trash)) }
            },
            dismissButton = { TextButton(onClick = { sheet = ReaderSheet.NONE }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun SlideFromEdge(visible: Boolean, fromBottom: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { if (fromBottom) it else -it } + fadeIn(),
        exit = slideOutVertically { if (fromBottom) it else -it } + fadeOut(),
        modifier = modifier,
    ) { content() }
}

private enum class ReaderSheet { NONE, SETTINGS, INDEX, SEARCH, ABOUT, NEW_COLLECTION, TRASH }

private fun ScreenOrientation.next() = when (this) {
    ScreenOrientation.PORTRAIT -> ScreenOrientation.LANDSCAPE
    ScreenOrientation.LANDSCAPE -> ScreenOrientation.AUTO
    ScreenOrientation.AUTO -> ScreenOrientation.PORTRAIT
}

/** Oculta las barras del sistema mientras se lee; reaparecen con los controles (o deslizando desde el borde). */
@Composable
private fun ImmersiveMode(showSystemBars: Boolean) {
    val view = LocalView.current
    DisposableEffect(showSystemBars) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showSystemBars) controller?.show(WindowInsetsCompat.Type.systemBars())
        else controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

/**
 * Mientras el lector está abierto (leyendo o escuchando) la pantalla no se apaga por inactividad.
 * El usuario puede apagarla igualmente con el botón de encendido; al salir del lector vuelve el comportamiento normal.
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Lectura con la pantalla apagada: mientras suena (modo TTS y ajuste activado) corre el servicio en primer plano.
 * La primera vez pide el permiso de notificaciones (Android 13+); sin él el servicio funciona igual, sin aviso visible.
 */
@Composable
private fun ReadingServiceEffect(playback: PlaybackState, prefs: UserPreferences, title: String) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val active = playback.isPlaying && prefs.ttsMode && prefs.readWithScreenOff
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ReadingService.start(context, title)
    }
    LaunchedEffect(prefs.readWithScreenOff, prefs.ttsMode) {
        if (!prefs.readWithScreenOff || !prefs.ttsMode) ReadingService.stop(context)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderTopBar(
    book: Book?,
    chapterTitle: String?,
    chapterPage: Pair<Int, Int>?,
    ttsMode: Boolean,
    library: LibraryState,
    hasIndex: Boolean,
    /** null: sin original que ver; true: se está viendo el PDF; false: el texto. */
    original: Boolean?,
    onToggleOriginal: () -> Unit,
    onBack: () -> Unit,
    onToggleTts: () -> Unit,
    onSearch: () -> Unit,
    onIndex: () -> Unit,
    onSettings: () -> Unit,
    onShare: () -> Unit,
    onShelf: (Long, Boolean) -> Unit,
    onNewCollection: () -> Unit,
    onTrash: () -> Unit,
    onAbout: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                Spacer(Modifier.weight(1f))
                if (original != null) {
                    IconButton(onClick = onToggleOriginal) {
                        Icon(
                            painterResource(if (original) R.drawable.ic_menu_book else R.drawable.ic_find_in_page),
                            contentDescription = stringResource(if (original) R.string.reader_view_as_text else R.string.reader_view_original_pdf),
                        )
                    }
                }
                // Orden: navegar (índice, buscar), escuchar, ajustar, más.
                if (hasIndex) {
                    IconButton(onClick = onIndex) { Icon(painterResource(R.drawable.ic_list), contentDescription = stringResource(R.string.reader_index)) }
                }
                IconButton(onClick = onSearch) { Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.reader_search_book)) }
                IconButton(onClick = onToggleTts) {
                    Icon(
                        painterResource(if (ttsMode) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
                        contentDescription = stringResource(if (ttsMode) R.string.reader_tts_off else R.string.reader_tts_on),
                    )
                }
                IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.reader_settings)) }
                OverflowMenu(
                    ttsMode = ttsMode,
                    library = library,
                    bookId = book?.id,
                    onShare = onShare,
                    onShelf = onShelf,
                    onNewCollection = onNewCollection,
                    onTrash = onTrash,
                    onAbout = onAbout,
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(book?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!book?.authors.isNullOrEmpty()) {
                    Text(book!!.authors.joinToString(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val chapterLine = listOfNotNull(
                    chapterTitle?.takeIf { it.isNotBlank() },
                    chapterPage?.let { (n, total) -> stringResource(R.string.reader_chapter_page, n, total) },
                ).joinToString(" - ")
                if (chapterLine.isNotEmpty()) {
                    Text(chapterLine, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Menú ⋮: compartir, Agregar ▸ (estanterías y colecciones), papelera y detalles. */
@Composable
private fun OverflowMenu(
    ttsMode: Boolean,
    library: LibraryState,
    bookId: String?,
    onShare: () -> Unit,
    onShelf: (Long, Boolean) -> Unit,
    onNewCollection: () -> Unit,
    onTrash: () -> Unit,
    onAbout: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true; addMenu = false }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.common_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!addMenu) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_share_file)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                    onClick = { open = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_add_to)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                    onClick = { addMenu = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.catalog_move_to_trash)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    onClick = { open = false; onTrash() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_about)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
                    onClick = { open = false; onAbout() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_add_to), fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.reader_back_to_menu)) },
                    onClick = { addMenu = false },
                )
                HorizontalDivider()
                library.shelves.forEach { shelf ->
                    val member = bookId != null && bookId in shelf.bookIds
                    DropdownMenuItem(
                        text = { Text(Labels.shelf(shelf)) },
                        leadingIcon = { Checkbox(checked = member, onCheckedChange = null) },
                        onClick = { onShelf(shelf.id, !member) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_new_collection_menu)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    onClick = { open = false; onNewCollection() },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBottomBar(
    paged: PagedBook,
    page: Int,
    orientation: ScreenOrientation,
    bookmarked: Boolean,
    withNavigationInset: Boolean,
    onSeek: (Int) -> Unit,
    onOrientation: () -> Unit,
    onBookmark: () -> Unit,
) {
    // Mientras se arrastra se muestra la página de destino; al soltar se salta.
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(page.toFloat()) }
    if (!dragging) value = page.toFloat()
    val shown = value.toInt() + 1
    val pageDescription = stringResource(R.string.reader_page_of, shown, paged.size)
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 4.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (withNavigationInset) Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility) else Modifier)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                Text(
                    stringResource(R.string.reader_page_short, shown, paged.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { contentDescription = pageDescription },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = onOrientation) {
                    Icon(
                        painterResource(R.drawable.ic_screen_rotation),
                        contentDescription = stringResource(R.string.reader_orientation, Labels.orientation(orientation)),
                    )
                }
                IconButton(onClick = onBookmark) {
                    Icon(
                        painterResource(if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border),
                        contentDescription = stringResource(if (bookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add),
                    )
                }
            }
            if (paged.size > 1) {
                Slider(
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onPrimary,
                        activeTrackColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
                    ),
                    value = value,
                    onValueChange = { dragging = true; value = it },
                    onValueChangeFinished = { dragging = false; onSeek(value.toInt()) },
                    valueRange = 0f..(paged.size - 1).toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    hits: List<SearchHit>?,
    searching: Boolean,
    onQuery: (String) -> Unit,
    onSelect: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(300)
        onQuery(query)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.reader_search_book)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQuery(query) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            when {
                searching -> LinearProgressIndicator(Modifier.fillMaxWidth())
                hits == null -> Text(stringResource(R.string.reader_search_hint), style = MaterialTheme.typography.bodySmall)
                hits.isEmpty() -> Text(stringResource(R.string.reader_search_no_results), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text(
                        if (hits.size >= 200) stringResource(R.string.reader_search_too_many, 200)
                        else pluralStringResource(R.plurals.reader_search_results, hits.size, hits.size),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                        items(hits) { hit ->
                            Column(Modifier.fillMaxWidth().clickable { onSelect(hit) }.padding(vertical = 8.dp)) {
                                if (hit.chapterTitle.isNotBlank()) Text(hit.chapterTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    buildAnnotatedString {
                                        append("…" + hit.before)
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.secondaryContainer)) { append(hit.match) }
                                        append(hit.after + "…")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** «Acerca del documento»: todos los datos del libro. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheet(book: Book, imported: Boolean, progress: Float?, pageCount: Int?, shelves: List<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(book.title, style = MaterialTheme.typography.headlineSmall)
            book.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            val number = NumberFormat.getIntegerInstance()
            Detail(stringResource(R.string.about_authors), book.authors.joinToString().ifBlank { "—" })
            Detail(stringResource(R.string.about_language), Languages.name(book.learningLanguage))
            Detail(stringResource(R.string.about_level), book.cefrLevel.name)
            book.genre?.let { Detail(stringResource(R.string.about_genre), it) }
            Detail(stringResource(R.string.about_origin), stringResource(if (imported) R.string.about_origin_imported else R.string.catalog_included_in_app))
            Detail(stringResource(R.string.about_chapters), number.format(book.chapters.size))
            Detail(stringResource(R.string.about_passages), number.format(book.passages.size))
            pageCount?.let { Detail(stringResource(R.string.about_pages), number.format(it)) }
            Detail(stringResource(R.string.about_words), number.format(book.wordCount))
            Detail(stringResource(R.string.about_listening_time), stringResource(R.string.catalog_minutes, Book.estimateMinutes(book.wordCount, book.cefrLevel)))
            progress?.let { Detail(stringResource(R.string.about_progress), stringResource(R.string.common_percent, (it * 100).toInt())) }
            if (book.fullyTranslatedLanguages.isNotEmpty()) {
                Detail(stringResource(R.string.about_translations), book.fullyTranslatedLanguages.sorted().joinToString { Languages.name(it) })
            }
            if (book.glossary.isNotEmpty()) Detail(stringResource(R.string.about_glossary_entries), number.format(book.glossary.size))
            if (shelves.isNotEmpty()) Detail(stringResource(R.string.about_shelves), shelves.joinToString())
            HorizontalDivider()
            book.adaptationCredit?.let { Detail(stringResource(R.string.about_adaptation), it) }
            Detail(stringResource(R.string.about_license), book.license.name ?: book.license.id)
            book.license.url?.let { Detail(stringResource(R.string.about_license_link), it) }
            Detail(stringResource(R.string.about_source), book.sourceAttribution)
            Detail(stringResource(R.string.about_identifier), book.id)
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun NewCollectionDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collection_new_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.collection_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.collection_create_and_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Falta el modelo de traducción de algún idioma: se ofrece descargarlo (nunca se descarga sin avisar). */
@Composable
private fun TranslationModelBanner(missing: List<String>, downloads: Map<String, ModelDownload>, onDownload: () -> Unit) {
    if (missing.isEmpty()) return
    val names = missing.joinToString(stringResource(R.string.common_and_separator)) { Languages.nameInSentence(it) }
    val running = missing.any { downloads[it] == ModelDownload.Running }
    val error = missing.firstNotNullOfOrNull { (downloads[it] as? ModelDownload.Failed)?.message }?.asString()
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.reader_translator_missing, names, 40 * missing.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            when {
                running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onDownload) { Text(stringResource(if (error != null) R.string.reader_retry_download else R.string.common_download)) }
                }
            }
        }
    }
}

@Composable
private fun AudioBanner(playback: PlaybackState, onRetry: () -> Unit, onOpenSettings: () -> Unit, ttsMode: Boolean) {
    if (!ttsMode) return
    val context = LocalContext.current
    val readiness = playback.readiness
    val message = when {
        readiness is EngineReadiness.Unavailable -> Labels.audioUnavailable(readiness.reason, readiness.languageTag)
        playback.status == PlaybackStatus.ERROR -> stringResource(R.string.reader_tts_error_code, playback.errorCode ?: "?")
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (readiness is EngineReadiness.Unavailable && readiness.reason.isModelProblem()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.reader_open_settings)) }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                }
            } else if (readiness is EngineReadiness.Unavailable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }) { Text(stringResource(R.string.reader_install_voice)) }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                }
            }
        }
    }
}

private fun com.linguareader.core.engine.EngineUnavailableReason.isModelProblem() =
    this == com.linguareader.core.engine.EngineUnavailableReason.MODEL_NOT_INSTALLED ||
        this == com.linguareader.core.engine.EngineUnavailableReason.OUT_OF_MEMORY
