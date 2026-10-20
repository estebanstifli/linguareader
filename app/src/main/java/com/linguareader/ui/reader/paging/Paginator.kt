package com.linguareader.ui.reader.paging

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.linguareader.core.model.Book
import com.linguareader.core.model.TextJustification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estilos del lector paginado. Medir y dibujar usan **exactamente** estos estilos y el mismo texto (el bloque entero,
 * ver [PageItem]): por eso se puede justificar y guionar sin que nada se descuadre. Altura de línea fija sin recorte.
 */
@Immutable
data class PageStyles(
    val reading: TextStyle,
    val translation: TextStyle,
    val title: TextStyle,
) {
    companion object {
        @Composable
        fun of(
            fontScale: Float,
            lineHeight: Float,
            languageTag: String,
            text: Color,
            translation: Color,
            justification: TextJustification = TextJustification.JUSTIFIED_HYPHENATED,
        ): PageStyles {
            val justified = justification != TextJustification.LEFT
            val align = if (justified) TextAlign.Justify else TextAlign.Start
            val hyphens = if (justification == TextJustification.JUSTIFIED_HYPHENATED) Hyphens.Auto else Hyphens.None
            // Con justificado, el salto «de párrafo» reparte mejor los espacios entre líneas (menos ríos).
            val breaks = if (justified) LineBreak.Paragraph else LineBreak.Simple
            val fixedLines = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
            val locale = LocaleList(languageTag)
            val base = MaterialTheme.typography.bodyLarge
            return PageStyles(
                reading = base.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = (20 * fontScale).sp,
                    lineHeight = (20 * fontScale * lineHeight).sp,
                    color = text,
                    textAlign = align,
                    hyphens = hyphens,
                    lineBreak = breaks,
                    lineHeightStyle = fixedLines,
                    localeList = locale,
                ),
                translation = base.copy(
                    fontSize = (16 * fontScale).sp,
                    lineHeight = (16 * fontScale * lineHeight).sp,
                    color = translation,
                    fontStyle = FontStyle.Italic,
                    textAlign = align,
                    hyphens = hyphens,
                    lineBreak = breaks,
                    lineHeightStyle = fixedLines,
                ),
                title = base.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = (24 * fontScale).sp,
                    lineHeight = (24 * fontScale * 1.3f).sp,
                    color = text,
                    textAlign = TextAlign.Center,
                    lineBreak = LineBreak.Simple,
                    lineHeightStyle = fixedLines,
                    localeList = locale,
                ),
            )
        }
    }
}

/** Geometría de la página en px. */
@Immutable
data class PageGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val blockSpacingPx: Float,
    val titleSpacingPx: Float,
    /** Doble página: [widthPx] es el ancho de cada cara y las páginas se juntan de dos en dos ([spreads]). */
    val spread: Boolean = false,
    /** Separación entre las dos caras (solo en doble página). */
    val gutterPx: Int = 0,
)

private class LayoutMetrics(private val r: TextLayoutResult) : MeasuredText {
    override val lineCount: Int get() = r.lineCount
    override fun lineTop(line: Int) = r.getLineTop(line)
    override fun lineBottom(line: Int) = r.getLineBottom(line)
    override fun lineStart(line: Int) = r.getLineStart(line)
    override fun lineEnd(line: Int) = r.getLineEnd(line)
}

/** Mide cada bloque una sola vez y corta el libro en páginas (fuera del hilo principal). */
suspend fun paginate(
    book: Book,
    blocks: List<LayoutBlock>,
    styles: PageStyles,
    geometry: PageGeometry,
    measurer: TextMeasurer,
): PagedBook = withContext(Dispatchers.Default) {
    val constraints = Constraints(maxWidth = geometry.widthPx)
    fun measure(text: String, style: TextStyle): MeasuredText? =
        if (text.isEmpty()) null else LayoutMetrics(measurer.measure(text, style, constraints = constraints))
    val pages = PageBuilder.build(
        blocks = blocks,
        measure = { b ->
            when (b) {
                is LayoutBlock.Original -> measure(book.passages[b.passage].text.substring(b.start, b.end), styles.reading)
                is LayoutBlock.Translation -> measure(b.text, styles.translation)
                is LayoutBlock.ChapterTitle -> measure(b.text, styles.title)
                is LayoutBlock.Image -> null
            }
        },
        // Margen de seguridad de 2 px por redondeos entre medir y dibujar.
        pageHeight = geometry.heightPx - 2f,
        blockSpacing = geometry.blockSpacingPx,
        titleSpacing = geometry.titleSpacingPx,
    )
    PagedBook(if (geometry.spread) spreads(pages) else pages)
}
