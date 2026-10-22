package com.linguareader.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linguareader.R
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** Llevar a la vista el punto ([x], [y] en fracciones) de la página [page]; [onlyIfHidden]: no mover si ya se ve. */
data class PdfScrollRequest(val page: Int, val x: Float, val y: Float, val onlyIfHidden: Boolean, val animate: Boolean = true)

/** Convierte una caja en fracciones de una página a coordenadas raíz (para colocar tarjetas junto a la palabra). */
fun interface PageGeometry {
    fun rootRect(left: Float, top: Float, right: Float, bottom: Float): Rect
}

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Páginas de un PDF en scroll vertical continuo, con zoom con dos dedos (o doble toque) y
 * desplazamiento horizontal cuando la página es más ancha que la pantalla.
 *
 * @param pageOverlay se dibuja sobre cada página (resaltado de la lectura), con el tamaño de la página.
 * @param onPageTap toque simple en una página: posición en fracciones y geometría para convertir cajas.
 * @param onUserTouch el usuario tocó o arrastró (para dejar de seguir a la voz un momento).
 */
@OptIn(FlowPreview::class)
@Composable
fun PdfPagesView(
    session: PdfDocumentSession,
    initialPage: Int,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollRequests: Flow<PdfScrollRequest> = emptyFlow(),
    onPageTap: ((page: Int, x: Float, y: Float, geometry: PageGeometry) -> Unit)? = null,
    onUserTouch: () -> Unit = {},
    pageOverlay: (@Composable BoxScope.(page: Int) -> Unit)? = null,
    indicatorPadding: Dp = 16.dp,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
        val hScroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        // Zoom aplicado a la maquetación (páginas más anchas y re-dibujadas nítidas)…
        var zoom by rememberSaveable { mutableFloatStateOf(1f) }
        // …y zoom provisional mientras dura el gesto (solo escala la imagen: fluido).
        var gestureZoom by remember { mutableFloatStateOf(1f) }
        var pivot by remember { mutableStateOf(Offset.Zero) }
        val viewport = remember { arrayOfNulls<LayoutCoordinates>(1) }
        val touched by rememberUpdatedState(onUserTouch)

        /** Fija el zoom [target] manteniendo bajo [focus] (coordenadas de pantalla) el mismo punto del documento. */
        fun commit(target: Float, focus: Offset) {
            val next = target.coerceIn(1f, MAX_ZOOM)
            val factor = next / zoom
            if (abs(factor - 1f) < 0.01f) {
                gestureZoom = 1f
                return
            }
            val first = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            val newX = (hScroll.value + focus.x) * factor - focus.x
            val newY = (offset + focus.y) * factor - focus.y
            zoom = next
            scope.launch {
                // Esperar a que la nueva maquetación exista antes de desplazar (si no, el scroll se recorta).
                val expectedMax = (viewportWidth * (next - 1f)).roundToInt()
                withTimeoutOrNull(500) { snapshotFlow { hScroll.maxValue }.first { abs(it - expectedMax) <= 2 } }
                listState.scrollToItem(first, 0)
                listState.scrollBy(newY)
                hScroll.scrollTo(newX.roundToInt().coerceIn(0, hScroll.maxValue))
                gestureZoom = 1f
            }
        }

        val pageCount = session.pageCount
        val currentPage by remember {
            derivedStateOf {
                // La página que ocupa el centro de la pantalla.
                val info = listState.layoutInfo
                val center = info.viewportEndOffset / 2
                info.visibleItemsInfo.firstOrNull { center in it.offset..(it.offset + it.size) }?.index ?: listState.firstVisibleItemIndex
            }
        }
        val pageChanged by rememberUpdatedState(onPage)
        LaunchedEffect(listState) {
            snapshotFlow { currentPage }.debounce(500).collect { pageChanged(it) }
        }
        LaunchedEffect(scrollRequests) {
            scrollRequests.collect { req ->
                if (req.page !in 0 until pageCount) return@collect
                val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == req.page }
                val height = listState.layoutInfo.viewportSize.height.toFloat()
                val y = visible?.let { it.offset + req.y * it.size }
                if (!req.onlyIfHidden || y == null || y < height * 0.1f || y > height * 0.8f) {
                    val pageHeight = viewportWidth * zoom * session.pageAspects[req.page]
                    // La línea queda a un tercio de la pantalla: se ve lo que viene.
                    val offset = (req.y * pageHeight - height * 0.3f).roundToInt().coerceAtLeast(0)
                    if (req.animate && visible != null) listState.animateScrollBy(visible.offset + req.y * visible.size - height * 0.3f)
                    else listState.scrollToItem(req.page, offset)
                }
                if (zoom > 1f) {
                    val x = req.x * viewportWidth * zoom
                    if (x < hScroll.value + viewportWidth * 0.1f || x > hScroll.value + viewportWidth * 0.85f) {
                        val target = (x - viewportWidth * 0.2f).roundToInt().coerceIn(0, hScroll.maxValue)
                        if (req.animate) hScroll.animateScrollTo(target) else hScroll.scrollTo(target)
                    }
                }
            }
        }

        val pageWidthDp = with(density) { (viewportWidth * zoom).toDp() }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { viewport[0] = it }
                .pointerInput(Unit) {
                    // Pellizco: se intercepta antes que el scroll (pase Initial) solo cuando hay dos dedos;
                    // con uno, la lista y el desplazamiento horizontal funcionan como siempre (con inercia).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touched()
                        var pinching = false
                        var accumulated = 1f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                if (!pinching) {
                                    pinching = true
                                    pivot = event.calculateCentroid(useCurrent = true)
                                }
                                accumulated *= event.calculateZoom()
                                gestureZoom = (zoom * accumulated).coerceIn(0.5f, MAX_ZOOM) / zoom
                                event.changes.forEach { it.consume() }
                            } else if (pinching) {
                                // Al levantar un dedo, el que queda no debe desplazar de golpe.
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) commit(zoom * gestureZoom, pivot)
                    }
                }
                .graphicsLayer {
                    scaleX = gestureZoom
                    scaleY = gestureZoom
                    transformOrigin = TransformOrigin(
                        (pivot.x / viewportWidth).coerceIn(0f, 1f),
                        (pivot.y / viewportHeight).coerceIn(0f, 1f),
                    )
                }
                .horizontalScroll(hScroll, enabled = zoom > 1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(pageWidthDp).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pageCount, key = { it }) { index ->
                    PdfPage(
                        session = session,
                        index = index,
                        pageCount = pageCount,
                        widthPx = (viewportWidth * zoom).roundToInt(),
                        onTap = onPageTap?.let { tap -> { x, y, geometry -> tap(index, x, y, geometry) } },
                        onDoubleTap = { coords, local ->
                            val focus = viewport[0]?.takeIf { it.isAttached && coords.isAttached }?.localPositionOf(coords, local) ?: local
                            commit(if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM, focus)
                        },
                        overlay = pageOverlay,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f / session.pageAspects[index]),
                    )
                }
            }
        }

        PageIndicator(
            current = currentPage,
            count = pageCount,
            onJump = { page -> scope.launch { listState.scrollToItem(page) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = indicatorPadding),
        )
    }
}

@Composable
private fun PdfPage(
    session: PdfDocumentSession,
    index: Int,
    pageCount: Int,
    widthPx: Int,
    onTap: ((x: Float, y: Float, geometry: PageGeometry) -> Unit)?,
    onDoubleTap: (LayoutCoordinates, Offset) -> Unit,
    overlay: (@Composable BoxScope.(page: Int) -> Unit)?,
    modifier: Modifier,
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(session.cached(index)) }
    LaunchedEffect(index, widthPx) {
        // Tras un cambio de zoom, se espera un instante: así no se re-dibuja en cada paso de un doble toque.
        if (bitmap != null) delay(150)
        session.render(index, widthPx)?.let { bitmap = it }
    }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val tap by rememberUpdatedState(onTap)
    val pageDescription = stringResource(R.string.reader_page_of, index + 1, pageCount)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    // El papel es siempre blanco, también en tema oscuro (los colores del documento son los suyos).
    Box(
        modifier
            .background(Color.White)
            .onGloballyPositioned { coords[0] = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val c = coords[0] ?: return@detectTapGestures
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        tap?.invoke(pos.x / w, pos.y / h) { l, t, r, b ->
                            if (!c.isAttached) Rect.Zero
                            else Rect(c.localToRoot(Offset(l * w, t * h)), c.localToRoot(Offset(r * w, b * h)))
                        }
                    },
                    onDoubleTap = { pos -> coords[0]?.let { doubleTap(it, pos) } },
                )
            }
            .semantics { contentDescription = pageDescription },
    ) {
        bitmap?.let { bmp ->
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        overlay?.invoke(this, index)
    }
}

/** «12 / 300»; al tocarlo, una barra para saltar de página en documentos largos. */
@Composable
private fun PageIndicator(current: Int, count: Int, onJump: (Int) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var target by remember { mutableFloatStateOf(0f) }
    val goToPage = stringResource(R.string.pdf_go_to_page)
    val pageDescription = stringResource(R.string.pdf_page_jump_description, current + 1, count)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        if (expanded && count > 1) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${target.roundToInt() + 1} / $count", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = target,
                    onValueChange = { target = it },
                    onValueChangeFinished = { onJump(target.roundToInt()) },
                    valueRange = 0f..(count - 1).toFloat(),
                    modifier = Modifier.width(220.dp).padding(horizontal = 8.dp).semantics { contentDescription = goToPage },
                )
                TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.common_close), color = MaterialTheme.colorScheme.inverseOnSurface) }
            }
        } else {
            TextButton(onClick = { target = current.toFloat(); expanded = true }) {
                Text(
                    "${current + 1} / $count",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.semantics { contentDescription = pageDescription },
                )
            }
        }
    }
}
