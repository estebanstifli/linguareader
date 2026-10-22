package com.linguareader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.core.model.Book
import com.linguareader.core.model.HighlightStyle
import com.linguareader.core.model.Passage
import com.linguareader.core.model.Token
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.reader.HighlightGranularity
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.PlaybackStatus
import com.linguareader.data.content.PdfLayoutState
import com.linguareader.data.content.PdfTextLayout
import com.linguareader.importer.layout.TokenBox
import com.linguareader.ui.reader.paging.TextPos
import com.linguareader.ui.viewer.PdfMessage
import com.linguareader.ui.viewer.PdfPagesView
import com.linguareader.ui.viewer.PdfScrollRequest
import com.linguareader.ui.viewer.PdfUnavailable
import com.linguareader.ui.viewer.PdfViewerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Tras tocar o arrastrar, la vista deja de seguir a la voz este tiempo: el usuario está mirando otra cosa. */
private const val FOLLOW_PAUSE_MS = 4_000L

/**
 * El PDF original dentro del lector: las páginas tal cual, con la frase y la palabra que suenan
 * resaltadas encima, tocar una palabra para traducirla y la vista siguiendo a la voz. Todo lo
 * demás (reproductor, tarjetas, progreso) es del lector, como en el modo texto.
 *
 * @param onTap toque en una palabra (con su caja en coordenadas raíz) o fuera de ellas (null).
 * @param onUserMoved el usuario se desplazó a otra página con la lectura parada: se lee desde ahí.
 */
@Composable
fun ReaderPdfContent(
    pdf: OriginalPdfViewModel,
    book: Book,
    playback: PlaybackState,
    prefs: UserPreferences,
    tappedTokenId: String?,
    anchor: TextPos?,
    jumps: Flow<TextPos>,
    onTap: (TokenHit?) -> Unit,
    onUserMoved: (TextPos) -> Unit,
    onShowText: () -> Unit,
    indicatorPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { pdf.open() }
    val document by pdf.document.collectAsStateWithLifecycle()
    val layoutState by pdf.layout.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        when (val doc = document) {
            PdfViewerState.Opening -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is PdfViewerState.Unavailable -> PdfUnavailable(onRelocate = pdf::relocate, onOpenReader = onShowText, readerLabel = stringResource(R.string.reader_view_as_text))
            is PdfViewerState.Failed -> PdfMessage(doc.message.asString())
            is PdfViewerState.Ready -> {
                val layout = (layoutState as? PdfLayoutState.Ready)?.layout
                Pages(doc, book, layout, playback, prefs, tappedTokenId, anchor, jumps, onTap, onUserMoved, indicatorPadding)
                LayoutStatus(layoutState, layout, book, onRetry = pdf::retryLayout, onShowText = onShowText, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun Pages(
    doc: PdfViewerState.Ready,
    book: Book,
    layout: PdfTextLayout?,
    playback: PlaybackState,
    prefs: UserPreferences,
    tappedTokenId: String?,
    anchor: TextPos?,
    jumps: Flow<TextPos>,
    onTap: (TokenHit?) -> Unit,
    onUserMoved: (TextPos) -> Unit,
    indicatorPadding: Dp,
) {
    val tokens = remember(book) { TokenIndex(book) }
    val requests = remember { MutableSharedFlow<PdfScrollRequest>(extraBufferCapacity = 4) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    val latestPlayback by rememberUpdatedState(playback)
    val latestAnchor by rememberUpdatedState(anchor)

    fun request(tokenId: String?, onlyIfHidden: Boolean, animate: Boolean) {
        val box = tokenId?.let { layout?.tokens?.get(it)?.firstOrNull() } ?: return
        requests.tryEmit(PdfScrollRequest(box.page, box.left, box.top, onlyIfHidden, animate && !prefs.reduceMotion))
    }

    // Al abrir (o en cuanto se conocen las posiciones), al punto de lectura.
    LaunchedEffect(layout != null) {
        if (layout != null) request(latestAnchor?.let(tokens::at)?.id, onlyIfHidden = false, animate = false)
    }
    // Índice, marcadores, búsqueda.
    LaunchedEffect(layout) {
        if (layout != null) jumps.collect { pos -> request(tokens.at(pos)?.id, onlyIfHidden = false, animate = false) }
    }
    // Seguir a la voz: la palabra (o la frase, si el motor no da palabras) que suena, salvo si el usuario está mirando otra cosa.
    val following = playback.activeTokenId ?: tokens.sentenceStart(playback)
    LaunchedEffect(following, layout) {
        if (layout == null || !prefs.autoScroll || !latestPlayback.isPlaying) return@LaunchedEffect
        if (System.currentTimeMillis() - lastTouch < FOLLOW_PAUSE_MS) return@LaunchedEffect
        request(following, onlyIfHidden = true, animate = true)
    }

    val highlight = remember(playback.passageId, playback.sentenceIndex, playback.activeTokenId, playback.granularity, playback.status, layout) {
        Highlight.of(playback, tokens, layout)
    }
    val tapped = tappedTokenId?.let { layout?.tokens?.get(it) }.orEmpty()
    val style = prefs.highlightStyle

    PdfPagesView(
        session = doc.session,
        initialPage = 0,
        indicatorPadding = indicatorPadding,
        onPage = { page ->
            // Desplazamiento del usuario con la lectura parada: «reproducir» empezará en esa página (como al pasar
            // página en el modo texto). Si el punto de lectura ya está en ella, no se mueve.
            if (layout == null || latestPlayback.isPlaying || System.currentTimeMillis() - lastTouch > FOLLOW_PAUSE_MS) return@PdfPagesView
            val current = latestAnchor?.let(tokens::at)?.id?.let { layout.tokens[it] }?.firstOrNull()?.page
            if (current == page) return@PdfPagesView
            val first = layout.firstTokenOn(page)?.let(tokens::byId) ?: return@PdfPagesView
            onUserMoved(TextPos(first.first.index, first.second.rangeStart))
        },
        scrollRequests = requests,
        onUserTouch = { lastTouch = System.currentTimeMillis() },
        onPageTap = { page, x, y, geometry ->
            val id = layout?.hit(page, x, y)
            val entry = id?.let(tokens::byId)
            val box = id?.let { layout.tokens[it] }?.firstOrNull { it.page == page }
            if (entry != null && box != null) onTap(TokenHit(entry.second, geometry.rootRect(box.left, box.top, box.right, box.bottom)))
            else onTap(null)
        },
        pageOverlay = { page ->
            val sentence = highlight.sentence[page]
            val word = highlight.word[page]
            val mine = tapped.filter { it.page == page }
            if (sentence != null || word != null || mine.isNotEmpty()) {
                Canvas(Modifier.matchParentSize()) {
                    sentence?.forEach { drawBox(it, SENTENCE_COLOR, HighlightStyle.BACKGROUND) }
                    word?.forEach { drawBox(it, if (style == HighlightStyle.MARKER) MARKER_COLOR else WORD_COLOR, style) }
                    mine.forEach { drawBox(it, TAPPED_COLOR, outline = true) }
                }
            }
        },
    )
}

// Colores pensados para el papel blanco del PDF (no dependen del tema): se mezclan multiplicando, así el texto sigue negro.
private val SENTENCE_COLOR = Color(0xFFD6E8F5)
private val WORD_COLOR = Color(0xFF9CCBE8)
private val MARKER_COLOR = Color(0xFFFFE066)
private val TAPPED_COLOR = Color(0xFF2D5F73)

private fun DrawScope.drawBox(box: TokenBox, color: Color, style: HighlightStyle = HighlightStyle.BACKGROUND, outline: Boolean = false) {
    val pad = size.width * 0.003f
    val topLeft = Offset(box.left * size.width - pad, box.top * size.height)
    val boxSize = Size((box.right - box.left) * size.width + 2 * pad, (box.bottom - box.top) * size.height)
    when {
        outline -> drawRoundRect(color, topLeft, boxSize, androidx.compose.ui.geometry.CornerRadius(pad * 2), style = Stroke(width = pad * 1.5f))
        style == HighlightStyle.UNDERLINE -> drawRect(
            color = TAPPED_COLOR,
            topLeft = Offset(topLeft.x, topLeft.y + boxSize.height - pad * 1.2f),
            size = Size(boxSize.width, pad * 1.2f),
        )
        else -> drawRect(color, topLeft, boxSize, blendMode = BlendMode.Multiply)
    }
}

/** Cajas a resaltar por página: la frase que suena y la palabra activa. */
private class Highlight(val sentence: Map<Int, List<TokenBox>>, val word: Map<Int, List<TokenBox>>) {
    companion object {
        private val NONE = Highlight(emptyMap(), emptyMap())

        fun of(playback: PlaybackState, tokens: TokenIndex, layout: PdfTextLayout?): Highlight {
            if (layout == null || playback.granularity == HighlightGranularity.NONE) return NONE
            if (playback.status == PlaybackStatus.IDLE || playback.status == PlaybackStatus.COMPLETED) return NONE
            val passage = playback.passageId?.let(tokens::passage) ?: return NONE
            val sentence = passage.sentences.getOrNull(playback.sentenceIndex) ?: return NONE
            val sentenceBoxes = lines(passage.tokensOf(sentence).flatMap { layout.tokens[it.id].orEmpty() }).groupBy { it.page }
            val wordBoxes = if (playback.granularity == HighlightGranularity.WORD) {
                playback.activeTokenId?.let { layout.tokens[it] }.orEmpty().groupBy { it.page }
            } else {
                emptyMap()
            }
            return Highlight(sentenceBoxes, wordBoxes)
        }
    }
}

/**
 * Une las cajas seguidas de una misma línea en una sola banda: resaltar palabra a palabra deja
 * huecos y solapes (más oscuros) entre ellas.
 */
private fun lines(boxes: List<TokenBox>): List<TokenBox> {
    val out = ArrayList<TokenBox>(boxes.size)
    for (b in boxes) {
        val last = out.lastOrNull()
        val height = b.bottom - b.top
        if (last != null && last.page == b.page && kotlin.math.abs(last.top - b.top) < height * 0.5f &&
            b.left >= last.left && b.left - last.right < 0.05f
        ) {
            out[out.lastIndex] = TokenBox(b.page, last.left, minOf(last.top, b.top), maxOf(last.right, b.right), maxOf(last.bottom, b.bottom))
        } else {
            out += b
        }
    }
    return out
}

/** Búsquedas de tokens y pasajes del libro por id o posición. */
private class TokenIndex(private val book: Book) {
    private val passagesById = book.passages.associateBy { it.id }

    fun passage(id: String): Passage? = passagesById[id]

    /** Pasaje y token de un id `p12:t34`. */
    fun byId(tokenId: String): Pair<Passage, Token>? {
        val passage = passagesById[tokenId.substringBeforeLast(":t")] ?: return null
        return passage.tokenById(tokenId)?.let { passage to it }
    }

    /** El token en (o justo después de) una posición de lectura. */
    fun at(pos: TextPos): Token? {
        val passage = book.passages.getOrNull(pos.passage) ?: return null
        return passage.tokens.firstOrNull { it.rangeEnd > pos.offset } ?: passage.tokens.lastOrNull()
    }

    /** Primer token de la frase que suena (motores que solo resaltan por frase). */
    fun sentenceStart(playback: PlaybackState): String? {
        val passage = playback.passageId?.let(passagesById::get) ?: return null
        val sentence = passage.sentences.getOrNull(playback.sentenceIndex) ?: return null
        return passage.tokens.firstOrNull { it.sentenceId == sentence.id }?.id
    }
}

/** Aviso mientras se sitúa el texto sobre las páginas, si falla o si el PDF apenas coincide con el texto. */
@Composable
private fun LayoutStatus(
    state: PdfLayoutState,
    layout: PdfTextLayout?,
    book: Book,
    onRetry: () -> Unit,
    onShowText: () -> Unit,
    modifier: Modifier,
) {
    val coverage = remember(layout, book) { layout?.coverage(book) }
    val message: Int
    var action: Pair<Int, () -> Unit>? = null
    var progress: Float? = null
    when {
        state is PdfLayoutState.Computing -> {
            message = R.string.pdf_layout_preparing
            progress = state.progress
        }
        state is PdfLayoutState.Failed -> {
            message = R.string.pdf_layout_failed
            action = R.string.common_retry to onRetry
        }
        coverage != null && coverage < 0.5f -> {
            message = R.string.pdf_layout_low_coverage
            action = R.string.reader_view_as_text to onShowText
        }
        else -> return
    }
    Surface(
        modifier = modifier.padding(12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
            action?.let { (label, run) -> TextButton(onClick = run, modifier = Modifier.align(Alignment.End)) { Text(stringResource(label)) } }
        }
    }
}
