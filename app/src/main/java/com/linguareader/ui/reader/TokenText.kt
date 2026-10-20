package com.linguareader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.linguareader.core.model.HighlightStyle
import com.linguareader.core.model.Token
import com.linguareader.core.text.OffsetIndex
import com.linguareader.ui.theme.ReaderColors

/**
 * Posición en pantalla (coordenadas raíz) del token/frase activos. Lo publica [TokenText] y lo
 * consumen el dedo indicador y el auto-scroll: ninguno de los dos depende del reproductor.
 */
@Stable
class ActiveTokenAnchor {
    /** Caja de la palabra activa (solo con resaltado por palabra). */
    var tokenBounds by mutableStateOf<Rect?>(null)
        private set
    /** Caja de lo que hay que mantener visible: palabra activa o, si no, la frase activa. */
    var focusBounds by mutableStateOf<Rect?>(null)
        private set
    private var owner: Any? = null

    fun report(owner: Any, token: Rect?, focus: Rect?) {
        this.owner = owner
        tokenBounds = token
        focusBounds = focus
    }

    fun release(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            tokenBounds = null
            focusBounds = null
        }
    }
}

/** Token tocado y su caja en pantalla (coordenadas raíz), para colocar la minitarjeta junto a él. */
data class TokenHit(val token: Token, val bounds: Rect)

/**
 * Localiza el token bajo una posición de pantalla (coordenadas raíz) entre todos los [TokenText] visibles.
 * La página lo usa para los toques sin que cada texto los capture (deslizar pasa página).
 */
@Stable
class TokenHitRegistry {
    private val testers = LinkedHashMap<Any, (Offset) -> TokenHit?>()
    fun register(owner: Any, tester: (Offset) -> TokenHit?) { testers[owner] = tester }
    fun unregister(owner: Any) { testers.remove(owner) }
    fun hitAt(rootPosition: Offset): TokenHit? = testers.values.firstNotNullOfOrNull { it(rootPosition) }
}

/**
 * Fragmento del texto del pasaje [segmentStart, segmentEnd) con tokens pulsables.
 * Los rangos de entrada son absolutos sobre el texto del pasaje.
 * El resaltado nunca cambia el ancho de los glifos (sin negrita) para que el texto no "baile".
 */
@Composable
fun TokenText(
    passageText: String,
    segmentStart: Int,
    segmentEnd: Int,
    tokens: List<Token>,
    activeWord: IntRange?,
    activeSentence: IntRange?,
    highlightStyle: HighlightStyle,
    colors: ReaderColors,
    textStyle: TextStyle,
    anchor: ActiveTokenAnchor,
    onTap: (Token) -> Unit,
    onLongPress: (Token) -> Unit,
    accessibilityActionLabel: String,
    onAccessibilityAction: () -> Unit,
    modifier: Modifier = Modifier,
    /** Si se da, el texto no captura toques: la página pregunta qué token hay bajo el dedo. */
    hitRegistry: TokenHitRegistry? = null,
    /** Solo se ven estas líneas del texto (paginación); el resto se maqueta igual pero queda recortado. */
    visibleLines: IntRange? = null,
) {
    val segment = remember(passageText, segmentStart, segmentEnd) { passageText.substring(segmentStart, segmentEnd) }
    val localWord = activeWord?.toLocal(segmentStart, segmentEnd)
    val localSentence = activeSentence?.toLocal(segmentStart, segmentEnd)

    val annotated = remember(segment, localWord, localSentence, highlightStyle, colors) {
        buildAnnotatedString {
            append(segment)
            localSentence?.let { addStyle(SpanStyle(background = colors.sentenceHighlight), it.first, it.last + 1) }
            localWord?.let {
                val style = when (highlightStyle) {
                    HighlightStyle.BACKGROUND -> SpanStyle(background = colors.wordHighlight, color = colors.onWordHighlight)
                    HighlightStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline, color = colors.underline)
                    HighlightStyle.MARKER -> null // se dibuja detrás (drawBehind)
                }
                style?.let { s -> addStyle(s, it.first, it.last + 1) }
            }
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val layoutNow = remember { arrayOfNulls<TextLayoutResult>(1) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val owner = remember { Any() }
    val slop = with(LocalDensity.current) { 6.dp.toPx() }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentTokens by rememberUpdatedState(tokens)

    fun boundsOf(range: IntRange, l: TextLayoutResult): Rect =
        l.getPathForRange(range.first, range.last + 1).getBounds()

    /** El carácter [local] cae en una línea visible (siempre, si no se recorta). */
    fun visible(local: Int, l: TextLayoutResult): Boolean =
        visibleLines == null || l.getLineForOffset(local.coerceIn(0, maxOf(0, l.layoutInput.text.length - 1))) in visibleLines

    fun report() {
        val l = layout
        val c = coordinates[0]
        if (l == null || c == null || !c.isAttached || l.layoutInput.text.length != segment.length) return
        if (localWord == null && localSentence == null) {
            anchor.release(owner)
            return
        }
        val origin = c.positionInRoot()
        val token = localWord?.takeIf { visible(it.first, l) }?.let { boundsOf(it, l).translate(origin) }
        val focus = token ?: localSentence?.takeIf { visible(it.first, l) }?.let { boundsOf(it, l).translate(origin) }
        anchor.report(owner, token, focus)
    }

    /** Token bajo [position] (coordenadas locales del texto) y su caja local. */
    fun hitAt(position: Offset): Pair<Token, Rect>? {
        val l = layout ?: return null
        val local = l.getOffsetForPosition(position)
        val candidates = listOfNotNull(
            OffsetIndex.tokenAt(currentTokens, segmentStart + local),
            OffsetIndex.tokenAt(currentTokens, segmentStart + local - 1),
        )
        return candidates.firstNotNullOfOrNull { t ->
            if (!visible(t.rangeStart - segmentStart, l)) return@firstNotNullOfOrNull null
            val box = l.getPathForRange(t.rangeStart - segmentStart, t.rangeEnd - segmentStart).getBounds()
            if (box.inflate(slop).contains(position)) t to box else null
        }
    }

    LaunchedEffect(localWord, localSentence, layout) { report() }
    DisposableEffect(Unit) { onDispose { anchor.release(owner) } }
    DisposableEffect(hitRegistry) {
        hitRegistry?.register(owner) { root ->
            val c = coordinates[0]
            if (c == null || !c.isAttached) return@register null
            val origin = c.positionInRoot()
            hitAt(root - origin)?.let { (token, box) -> TokenHit(token, box.translate(origin)) }
        }
        onDispose { hitRegistry?.unregister(owner) }
    }

    Text(
        text = annotated,
        style = textStyle,
        onTextLayout = { layoutNow[0] = it; layout = it },
        modifier = modifier
            .then(if (visibleLines != null) Modifier.clipToLines(layoutNow, visibleLines) else Modifier)
            .onGloballyPositioned {
                coordinates[0] = it
                report()
            }
            .drawBehind {
                val l = layout
                if (highlightStyle == HighlightStyle.MARKER && localWord != null && l != null &&
                    l.layoutInput.text.length == segment.length
                ) {
                    val r = boundsOf(localWord, l)
                    val top = r.top + r.height * 0.5f
                    drawRoundRect(
                        color = colors.marker,
                        topLeft = Offset(r.left - 2f, top),
                        size = Size(r.width + 4f, r.bottom - top),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                }
            }
            .then(
                if (hitRegistry != null) Modifier
                else Modifier.pointerInput(segmentStart, segmentEnd) {
                    detectTapGestures(
                        onTap = { pos -> hitAt(pos)?.first?.let(currentOnTap) },
                        onLongPress = { pos -> hitAt(pos)?.first?.let(currentOnLongPress) },
                    )
                },
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(accessibilityActionLabel) { onAccessibilityAction(); true },
                )
            },
    )
}

/**
 * Muestra solo las líneas [lines] de un texto maquetado entero: mide el texto sin límite de alto, se queda con la
 * altura de esas líneas y lo desplaza hacia arriba; lo demás queda recortado. [layout] lo rellena `onTextLayout`
 * durante la medida del propio texto, así que está listo al colocar.
 */
fun Modifier.clipToLines(layout: Array<TextLayoutResult?>, lines: IntRange): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val l = layout[0]
        if (l == null || lines.last >= l.lineCount) {
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val top = l.getLineTop(lines.first)
            val bottom = l.getLineBottom(lines.last)
            layout(placeable.width, ceil(bottom - top).toInt()) { placeable.place(0, -top.roundToInt()) }
        }
    }

private fun IntRange.toLocal(segmentStart: Int, segmentEnd: Int): IntRange? {
    val start = maxOf(first, segmentStart)
    val endExclusive = minOf(last + 1, segmentEnd)
    return if (start < endExclusive) (start - segmentStart) until (endExclusive - segmentStart) else null
}
