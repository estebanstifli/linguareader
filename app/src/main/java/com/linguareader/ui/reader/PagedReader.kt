package com.linguareader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import com.linguareader.core.model.Book
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.Token
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.reader.HighlightGranularity
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.PlaybackStatus
import com.linguareader.ui.reader.paging.BookBlocks
import com.linguareader.ui.reader.paging.Page
import com.linguareader.ui.reader.paging.PageGeometry
import com.linguareader.ui.reader.paging.PageItem
import com.linguareader.ui.reader.paging.PageStyles
import com.linguareader.ui.reader.paging.PagedBook
import com.linguareader.ui.reader.paging.TextPos
import com.linguareader.ui.reader.paging.paginate
import com.linguareader.ui.theme.LocalReaderColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Coordina el carrusel de páginas: distingue los cambios de página del usuario (deslizar, tocar un borde, la barra)
 * de los automáticos (seguir la voz, saltos desde el índice, volver a paginar), que no deben mover la lectura.
 */
class PageNavigator(val pager: PagerState, private val reduceMotion: () -> Boolean) {
    /** Página a la que se está yendo por programa: al asentarse ahí, no cuenta como acción del usuario. */
    var programmaticTarget: Int? = null

    suspend fun goTo(page: Int, user: Boolean, animate: Boolean = true) {
        if (page !in 0 until pager.pageCount) return
        if (!user) programmaticTarget = page
        if (animate && !reduceMotion()) pager.animateScrollToPage(page) else pager.scrollToPage(page)
    }
}

/**
 * Libro paginado a pantalla completa. Pagina con el tamaño real disponible y vuelve a paginar (manteniendo la
 * posición del texto) al cambiar tamaño de letra, interlineado, modo, orientación o traducciones.
 * Toques: borde izquierdo/derecho = página anterior/siguiente; centro = mostrar u ocultar controles;
 * pulsación larga sobre una palabra = ficha de traducción.
 */
@Composable
fun PagedReaderContent(
    book: Book,
    prefs: UserPreferences,
    playback: PlaybackState,
    anchor: TextPos?,
    translations: Map<String, String>,
    images: Map<Int, ImageBitmap>,
    jumps: Flow<TextPos>,
    navigator: PageNavigator,
    paged: PagedBook?,
    onPaged: (PagedBook) -> Unit,
    onSettled: (Page, user: Boolean) -> Unit,
    onToggleControls: () -> Unit,
    onLongPressToken: (Token) -> Unit,
    onSentenceAction: (passage: Int, sentenceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalReaderColors.current
    val styles = PageStyles.of(
        fontScale = prefs.fontScale,
        lineHeight = prefs.lineHeight,
        languageTag = book.learningLanguage,
        text = MaterialTheme.colorScheme.onBackground,
        translation = colors.translation,
        justification = prefs.textJustification,
    )
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current
    val measurer = remember(fontResolver, density, layoutDirection) { TextMeasurer(fontResolver, density, layoutDirection, cacheSize = 0) }
    val pager = navigator.pager
    val currentAnchor by rememberUpdatedState(anchor)
    val currentPaged by rememberUpdatedState(paged)
    val currentOnSettled by rememberUpdatedState(onSettled)
    val usesTranslations = prefs.displayMode == DisplayMode.TRANSLATION_BELOW || prefs.displayMode == DisplayMode.BILINGUAL_BLOCKS
    val layoutTranslations = if (usesTranslations) translations else emptyMap()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val geometry = with(density) {
            PageGeometry(
                widthPx = maxWidth.roundToPx(),
                heightPx = maxHeight.roundToPx(),
                blockSpacingPx = 14.dp.toPx(),
                titleSpacingPx = 28.dp.toPx(),
            )
        }
        val showImages = prefs.showIllustrations

        // Paginar (y volver a paginar) anclado a la posición del texto que se está leyendo.
        LaunchedEffect(book, geometry, styles, prefs.displayMode, showImages, layoutTranslations) {
            if (paged != null) delay(250) // agrupa cambios seguidos (sliders, traducciones que llegan)
            val blocks = BookBlocks.of(book, prefs.displayMode, layoutTranslations, showImages) { large ->
                (geometry.widthPx / if (large) 4f / 3f else 16f / 9f).toInt()
            }
            val result = paginate(book, blocks, styles, geometry, measurer)
            onPaged(result)
            val target = currentAnchor?.let(result::pageOf) ?: 0
            navigator.programmaticTarget = target
            // Esperar a que el pager conozca el nuevo número de páginas antes de saltar.
            snapshotFlow { pager.pageCount }.first { it >= result.size }
            pager.scrollToPage(target)
            currentOnSettled(result.pages[target], false)
        }

        if (paged == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@BoxWithConstraints
        }

        // Página asentada: avisa al ViewModel (y si fue el usuario, mueve la lectura a esa página).
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                val book2 = currentPaged ?: return@collect
                val p = book2.pages.getOrNull(page) ?: return@collect
                val user = navigator.programmaticTarget != page
                navigator.programmaticTarget = null
                currentOnSettled(p, user)
            }
        }

        // Saltos explícitos: índice, marcadores, búsqueda.
        LaunchedEffect(jumps) {
            jumps.collect { pos -> currentPaged?.let { navigator.goTo(it.pageOf(pos), user = false, animate = false) } }
        }

        // La página sigue a la voz: palabra activa o, si no hay tiempos por palabra, la frase.
        val following = prefs.autoScroll && playback.isPlaying
        LaunchedEffect(following, playback.passageId, playback.sentenceIndex, playback.activeTokenId, paged) {
            if (!following) return@LaunchedEffect
            val passage = book.passages.firstOrNull { it.id == playback.passageId } ?: return@LaunchedEffect
            val offset = playback.activeTokenId?.let(passage::tokenById)?.rangeStart
                ?: passage.sentences.getOrNull(playback.sentenceIndex)?.rangeStart ?: return@LaunchedEffect
            val target = paged.pageOf(TextPos(passage.index, offset))
            if (target != pager.currentPage && !pager.isScrollInProgress) navigator.goTo(target, user = false)
        }

        val hits = remember { TokenHitRegistry() }
        val anchorBox = remember { ActiveTokenAnchor() }
        var origin by remember { mutableStateOf(Offset.Zero) }
        var container by remember { mutableStateOf<Rect?>(null) }
        val scope = rememberCoroutineScope()
        val width = constraints.maxWidth

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.boundsInRoot().topLeft; container = it.boundsInRoot() }
                .pointerInput(width) {
                    detectTapGestures(
                        // Las páginas solo se pasan deslizando; un toque en los lados no hace nada.
                        onTap = { pos -> if (pos.x in width * SIDE..width * (1 - SIDE)) onToggleControls() },
                        onLongPress = { pos -> hits.tokenAt(origin + pos)?.let(onLongPressToken) },
                    )
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Página siguiente") { scope.launchPage(navigator, pager.currentPage + 1); true },
                        CustomAccessibilityAction("Página anterior") { scope.launchPage(navigator, pager.currentPage - 1); true },
                        CustomAccessibilityAction("Mostrar u ocultar controles") { onToggleControls(); true },
                    )
                },
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { it }) { index ->
                val page = paged.pages.getOrNull(index) ?: return@HorizontalPager
                PageView(page, book, styles, geometry, prefs, playback, images, hits, anchorBox, onSentenceAction)
            }
            if (prefs.fingerPointerEnabled) {
                FingerPointerOverlay(
                    target = anchorBox.tokenBounds,
                    container = container,
                    reduceMotion = prefs.reduceMotion,
                    fill = colors.pointer,
                    outline = colors.pointerOutline,
                )
            }
        }
    }
}

/** Fracción del ancho de cada lado donde un toque no hace nada (el centro muestra u oculta los controles). */
private const val SIDE = 0.25f

private fun CoroutineScope.launchPage(navigator: PageNavigator, page: Int) {
    launch { navigator.goTo(page, user = true) }
}

@Composable
private fun PageView(
    page: Page,
    book: Book,
    styles: PageStyles,
    geometry: PageGeometry,
    prefs: UserPreferences,
    playback: PlaybackState,
    images: Map<Int, ImageBitmap>,
    hits: TokenHitRegistry,
    anchor: ActiveTokenAnchor,
    onSentenceAction: (Int, String) -> Unit,
) {
    val colors = LocalReaderColors.current
    val density = LocalDensity.current
    val isCurrent = playback.status == PlaybackStatus.PLAYING || playback.status == PlaybackStatus.PAUSED
    Column(Modifier.fillMaxSize()) {
        page.items.forEachIndexed { i, item ->
            val passage = book.passages[item.passage]
            when (item) {
                is PageItem.Title -> Text(item.text, style = styles.title, modifier = Modifier.fillMaxWidth())
                is PageItem.Image -> {
                    val bitmap = images[item.passage]
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { item.heightPx.toDp() })
                            .clip(RoundedCornerShape(12.dp))
                            // Fondo solo mientras carga: con la imagen entera, los márgenes son el papel de la página.
                            .background(if (bitmap == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background),
                    ) {
                        if (bitmap != null) {
                            // Entera (sin recortar): las láminas de los libros suelen llevar texto o pie dentro.
                            Image(bitmap, contentDescription = passage.image?.alt, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                is PageItem.Original -> {
                    // Resaltado honesto: solo en el pasaje que suena, palabra con tiempos fiables, si no la frase.
                    val playingHere = isCurrent && playback.passageId == passage.id && playback.granularity != HighlightGranularity.NONE
                    val activeSentence = passage.sentences.getOrNull(playback.sentenceIndex)?.takeIf { playingHere }?.let { it.rangeStart until it.rangeEnd }
                    val activeWord = playback.activeTokenId?.takeIf { playingHere && playback.granularity == HighlightGranularity.WORD }
                        ?.let(passage::tokenById)?.let { it.rangeStart until it.rangeEnd }
                    val firstSentence = passage.sentences.firstOrNull { item.start < it.rangeEnd } ?: passage.sentences.first()
                    TokenText(
                        passageText = passage.text,
                        segmentStart = item.blockStart,
                        segmentEnd = item.blockEnd,
                        tokens = passage.tokens.filter { it.rangeStart >= item.blockStart && it.rangeEnd <= item.blockEnd },
                        activeWord = activeWord,
                        activeSentence = activeSentence,
                        highlightStyle = prefs.highlightStyle,
                        colors = colors,
                        textStyle = styles.reading,
                        anchor = anchor,
                        onTap = {},
                        onLongPress = {},
                        accessibilityActionLabel = "Traducir frase",
                        onAccessibilityAction = { onSentenceAction(passage.index, firstSentence.id) },
                        modifier = Modifier.fillMaxWidth(),
                        hitRegistry = hits,
                        visibleLines = item.firstLine..item.lastLine,
                    )
                }
                is PageItem.Translation -> {
                    val layout = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
                    Text(
                        item.text,
                        style = styles.translation,
                        onTextLayout = { layout[0] = it },
                        modifier = Modifier.fillMaxWidth().clipToLines(layout, item.firstLine..item.lastLine),
                    )
                }
            }
            if (i < page.items.lastIndex) {
                val spacing = if (item is PageItem.Title) geometry.titleSpacingPx else geometry.blockSpacingPx
                Spacer(Modifier.height(with(density) { spacing.toDp() }))
            }
        }
    }
}
