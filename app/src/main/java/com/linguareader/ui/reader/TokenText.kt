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
import androidx.compose.ui.unit.dp
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
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val owner = remember { Any() }
    val slop = with(LocalDensity.current) { 6.dp.toPx() }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentTokens by rememberUpdatedState(tokens)

    fun boundsOf(range: IntRange, l: TextLayoutResult): Rect =
        l.getPathForRange(range.first, range.last + 1).getBounds()

    fun report() {
        val l = layout
        val c = coordinates[0]
        if (l == null || c == null || !c.isAttached || l.layoutInput.text.length != segment.length) return
        if (localWord == null && localSentence == null) {
            anchor.release(owner)
            return
        }
        val origin = c.positionInRoot()
        val token = localWord?.let { boundsOf(it, l).translate(origin) }
        val focus = token ?: localSentence?.let { boundsOf(it, l).translate(origin) }
        anchor.report(owner, token, focus)
    }

    fun tokenAt(position: Offset): Token? {
        val l = layout ?: return null
        val local = l.getOffsetForPosition(position)
        val candidates = listOfNotNull(
            OffsetIndex.tokenAt(currentTokens, segmentStart + local),
            OffsetIndex.tokenAt(currentTokens, segmentStart + local - 1),
        )
        return candidates.firstOrNull { t ->
            val box = l.getPathForRange(t.rangeStart - segmentStart, t.rangeEnd - segmentStart).getBounds()
            box.inflate(slop).contains(position)
        }
    }

    LaunchedEffect(localWord, localSentence, layout) { report() }
    DisposableEffect(Unit) { onDispose { anchor.release(owner) } }

    Text(
        text = annotated,
        style = textStyle,
        onTextLayout = { layout = it },
        modifier = modifier
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
            .pointerInput(segmentStart, segmentEnd) {
                detectTapGestures(
                    onTap = { pos -> tokenAt(pos)?.let(currentOnTap) },
                    onLongPress = { pos -> tokenAt(pos)?.let(currentOnLongPress) },
                )
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(accessibilityActionLabel) { onAccessibilityAction(); true },
                )
            },
    )
}

private fun IntRange.toLocal(segmentStart: Int, segmentEnd: Int): IntRange? {
    val start = maxOf(first, segmentStart)
    val endExclusive = minOf(last + 1, segmentEnd)
    return if (start < endExclusive) (start - segmentStart) until (endExclusive - segmentStart) else null
}
