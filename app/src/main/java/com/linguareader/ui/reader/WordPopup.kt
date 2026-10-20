package com.linguareader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.linguareader.R
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.translation.WordLookup
import com.linguareader.ui.common.Labels
import kotlin.math.roundToInt

/**
 * Minitarjeta de traducción junto a la palabra tocada: debajo o encima, donde haya más espacio dentro de
 * [top]..[bottom] (px, mismas coordenadas que [word]), sin salirse de los márgenes laterales.
 */
@Composable
fun WordPopup(
    card: WordCardState,
    word: Rect,
    top: Float,
    bottom: Float,
    onMoreInfo: () -> Unit,
    onSave: () -> Unit,
    onReadFromHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(content = { MiniWordCard(card, onMoreInfo, onSave, onReadFromHere) }, modifier = modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val gap = 6.dp.roundToPx()
        val card = measurables.first().measure(Constraints(maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)))
        val above = word.top - top
        val below = bottom - word.bottom
        val y = if (below >= above) word.bottom + gap else word.top - gap - card.height
        val x = word.center.x - card.width / 2f
        layout(constraints.maxWidth, constraints.maxHeight) {
            card.place(
                x.roundToInt().coerceIn(margin, maxOf(margin, constraints.maxWidth - margin - card.width)),
                y.roundToInt().coerceIn(top.roundToInt(), maxOf(top.roundToInt(), bottom.roundToInt() - card.height)),
            )
        }
    }
}

@Composable
private fun MiniWordCard(card: WordCardState, onMoreInfo: () -> Unit, onSave: () -> Unit, onReadFromHere: () -> Unit) {
    val lookup = card.lookup
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        // Los toques sobre la tarjeta no llegan a la página (que la cerraría).
        modifier = Modifier.widthIn(min = 160.dp, max = 300.dp).pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (lookup == null) {
                CircularProgressIndicator(Modifier.padding(4.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                WordTranslation(lookup, card.targetLanguage)
                (lookup.sentenceTranslation as? TranslationResult.Translated)?.let { sentence ->
                    Text(
                        excerptAround(sentence.text, lookup.positionInSentence()),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Row {
                IconButton(onClick = onMoreInfo) {
                    Icon(painterResource(R.drawable.ic_info), contentDescription = "Más información")
                }
                IconButton(onClick = onSave, enabled = lookup != null && !card.saved) {
                    Icon(
                        painterResource(R.drawable.ic_star),
                        contentDescription = if (card.saved) "Guardada en vocabulario" else "Guardar en vocabulario",
                        tint = if (card.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onReadFromHere) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = "Reproducir desde aquí")
                }
            }
        }
    }
}

/** Traducción en contexto: la de la expresión que contiene la palabra (phrasal verb, contracción…) o la de la palabra. */
@Composable
private fun WordTranslation(lookup: WordLookup, target: String) {
    val expression = lookup.expression?.takeIf { it.isMultiToken }
    val result = expression?.let { lookup.expressionTranslation } ?: lookup.word
    Column(Modifier.padding(end = 8.dp)) {
        if (expression != null) {
            Text("«${expression.surface}»", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (result) {
            is TranslationResult.Translated -> Text(
                result.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            is TranslationResult.Unavailable -> Text(
                Labels.unavailable(result.reason, target),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Posición relativa (0..1) de la palabra en su frase: guía qué trozo de la traducción de la frase enseñar. */
private fun WordLookup.positionInSentence(): Float {
    val length = (sentence.rangeEnd - sentence.rangeStart).coerceAtLeast(1)
    return ((token.rangeStart - sentence.rangeStart).toFloat() / length).coerceIn(0f, 1f)
}

/**
 * Trozo de [text] de unos [max] caracteres centrado en la posición relativa [fraction], cortado por palabras y con
 * «…» en los extremos recortados. Si cabe entero, se devuelve tal cual.
 */
internal fun excerptAround(text: String, fraction: Float, max: Int = 70): String {
    val t = text.trim()
    if (t.length <= max) return t
    val center = (fraction.coerceIn(0f, 1f) * t.length).roundToInt()
    var start = (center - max / 2).coerceIn(0, t.length - max)
    var end = start + max
    if (start > 0) t.indexOf(' ', start).takeIf { it in start until center }?.let { start = it + 1 }
    if (end < t.length) t.lastIndexOf(' ', end).takeIf { it > center && it > start }?.let { end = it }
    val body = t.substring(start, end).trim().trimEnd(',', ';', ':')
    return (if (start > 0) "…" else "") + body + (if (end < t.length) "…" else "")
}
