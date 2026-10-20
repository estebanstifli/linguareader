package com.linguareader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /**
     * Volviendo a paginar: las páginas por las que pase el carrusel no son del usuario. Al girar la pantalla, el
     * carrusel recupera su número de página de la paginación anterior, que en la nueva es otro sitio del libro.
     */
    var repaginating = false

    suspend fun goTo(page: Int, user: Boolean, animate: Boolean = true) {
        if (page !in 0 until pager.pageCount) return
        if (!user) programmaticTarget = page
        if (animate && !reduceMotion()) pager.animateScrollToPage(page) else pager.scrollToPage(page)
    }
}

/**
 * Libro paginado a pantalla completa. Pagina con el tamaño real disponible y vuelve a paginar (manteniendo la
 * posición del texto) al cambiar tamaño de letra, interlineado, modo, orientación o traducciones.
 * Las páginas se pasan deslizando (arrastrar pausa la lectura). Un toque, o una pulsación larga, en cualquier
 * punto llega a [onTap] con la palabra tocada si la hay (controles y minitarjeta de traducción).
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
    onTap: (TokenHit?) -> Unit,
    onUserDrag: () -> Unit,
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
    val usesTranslations = prefs.displayMode == DisplayMode.TRANSLATION_BELOW
    val layoutTranslations = if (usesTranslations) translations else emptyMap()

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Doble página en horizontal cuando cada cara tiene un ancho cómodo (tablets; móviles grandes en horizontal).
        val spread = prefs.doublePage && maxWidth > maxHeight && maxWidth >= 840.dp
        val geometry = with(density) {
            val gutter = if (spread) 56.dp.roundToPx() else 0
            PageGeometry(
                widthPx = if (spread) (maxWidth.roundToPx() - gutter) / 2 else maxWidth.roundToPx(),
                heightPx = maxHeight.roundToPx(),
                blockSpacingPx = 14.dp.toPx(),
                titleSpacingPx = 28.dp.toPx(),
                spread = spread,
                gutterPx = gutter,
            )
        }
        val showImages = prefs.showIllustrations

        // Paginar (y volver a paginar) anclado a la posición del texto que se está leyendo.
        LaunchedEffect(book, geometry, styles, prefs.displayMode, showImages, layoutTranslations) {
            navigator.repaginating = true
            try {
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
            } finally {
                navigator.repaginating = false
            }
        }

        if (paged == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@BoxWithConstraints
        }

        // Página asentada: avisa al ViewModel (y si fue el usuario, mueve la lectura a esa página).
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                if (navigator.repaginating) return@collect
                val book2 = currentPaged ?: return@collect
                val p = book2.pages.getOrNull(page) ?: return@collect
                val user = navigator.programmaticTarget != page
                navigator.programmaticTarget = null
                currentOnSettled(p, user)
            }
        }

        // El usuario empieza a arrastrar la página: se pausa la lectura, como al pulsar pausa.
        val currentOnUserDrag by rememberUpdatedState(onUserDrag)
        LaunchedEffect(pager) {
            pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) currentOnUserDrag() }
        }

        // Saltos explícitos: índice, marcadores, búsqueda.
        LaunchedEffect(jumps) {
            jumps.collect { pos -> currentPaged?.let { navigator.goTo(it.pageOf(pos), user = false, animate = false) } }
        }

        // La página sigue a la voz: palabra activa o, si no hay tiempos por palabra, la frase.
        // En el silencio final de una frase no hay palabra activa: se sigue en la última palabra leída de esa frase
        // (no al inicio de la frase), para no volver atrás si la frase empezaba en la página anterior.
        // El paso de página se lanza fuera del efecto: el efecto se reinicia con cada palabra y, si la animación
        // corriera dentro, se cancelaría a medias y dejaría asomar el borde de la página anterior.
        val following = prefs.autoScroll && playback.isPlaying
        val lastFollowed = remember { arrayOfNulls<TextPos>(1) }
        val followScope = rememberCoroutineScope()
        val followJob = remember { arrayOfNulls<Job>(1) }
        LaunchedEffect(following, playback.passageId, playback.sentenceIndex, playback.activeTokenId, paged) {
            if (!following) return@LaunchedEffect
            val passage = book.passages.firstOrNull { it.id == playback.passageId } ?: return@LaunchedEffect
            val sentence = passage.sentences.getOrNull(playback.sentenceIndex) ?: return@LaunchedEffect
            val tokenStart = playback.activeTokenId?.let(passage::tokenById)?.rangeStart
            val offset = if (tokenStart != null) {
                tokenStart.also { lastFollowed[0] = TextPos(passage.index, it) }
            } else {
                lastFollowed[0]?.takeIf { it.passage == passage.index && it.offset in sentence.rangeStart until sentence.rangeEnd }
                    ?.offset ?: sentence.rangeStart
            }
            val target = paged.pageOf(TextPos(passage.index, offset))
            if (target == pager.currentPage || pager.isScrollInProgress || followJob[0]?.isActive == true) return@LaunchedEffect
            followJob[0] = followScope.launch { navigator.goTo(target, user = false) }
        }

        // Red de seguridad: si un desplazamiento se interrumpe (sin el dedo en la pantalla) y la página queda
        // a medio pasar, se completa hasta la página más cercana.
        LaunchedEffect(pager) {
            snapshotFlow { pager.isScrollInProgress to pager.currentPageOffsetFraction }
                .collect { (scrolling, fraction) ->
                    if (scrolling || abs(fraction) < 0.001f || followJob[0]?.isActive == true) return@collect
                    delay(100) // deja terminar el ajuste propio del pager tras soltar el dedo
                    if (!pager.isScrollInProgress && abs(pager.currentPageOffsetFraction) >= 0.001f) {
                        navigator.goTo(pager.currentPage, user = false)
                    }
                }
        }

        val hits = remember { TokenHitRegistry() }
        val anchorBox = remember { ActiveTokenAnchor() }
        var origin by remember { mutableStateOf(Offset.Zero) }
        var container by remember { mutableStateOf<Rect?>(null) }
        val scope = rememberCoroutineScope()
        val currentOnTap by rememberUpdatedState(onTap)

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.boundsInRoot().topLeft; container = it.boundsInRoot() }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Las páginas solo se pasan deslizando; toque y pulsación larga hacen lo mismo.
                        onTap = { pos -> currentOnTap(hits.hitAt(origin + pos)) },
                        onLongPress = { pos -> currentOnTap(hits.hitAt(origin + pos)) },
                    )
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Página siguiente") { scope.launchPage(navigator, pager.currentPage + 1); true },
                        CustomAccessibilityAction("Página anterior") { scope.launchPage(navigator, pager.currentPage - 1); true },
                        CustomAccessibilityAction("Mostrar u ocultar controles") { currentOnTap(null); true },
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
    if (!geometry.spread) {
        PageColumn(page.items, book, styles, geometry, prefs, playback, images, hits, anchor, onSentenceAction, Modifier.fillMaxSize())
        return
    }
    // Libro abierto: cada cara con el ancho exacto con el que se paginó.
    val width = with(LocalDensity.current) { geometry.widthPx.toDp() }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
        PageColumn(page.items, book, styles, geometry, prefs, playback, images, hits, anchor, onSentenceAction, Modifier.width(width).fillMaxHeight())
        PageColumn(page.facing, book, styles, geometry, prefs, playback, images, hits, anchor, onSentenceAction, Modifier.width(width).fillMaxHeight())
    }
}

@Composable
private fun PageColumn(
    items: List<PageItem>,
    book: Book,
    styles: PageStyles,
    geometry: PageGeometry,
    prefs: UserPreferences,
    playback: PlaybackState,
    images: Map<Int, ImageBitmap>,
    hits: TokenHitRegistry,
    anchor: ActiveTokenAnchor,
    onSentenceAction: (Int, String) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalReaderColors.current
    val density = LocalDensity.current
    val isCurrent = playback.status == PlaybackStatus.PLAYING || playback.status == PlaybackStatus.PAUSED
    Column(modifier) {
        items.forEachIndexed { i, item ->
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
            if (i < items.lastIndex) {
                val spacing = if (item is PageItem.Title) geometry.titleSpacingPx else geometry.blockSpacingPx
                Spacer(Modifier.height(with(density) { spacing.toDp() }))
            }
        }
    }
}
