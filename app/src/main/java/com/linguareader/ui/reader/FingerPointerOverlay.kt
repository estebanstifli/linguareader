package com.linguareader.ui.reader

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Dedo decorativo que apunta (desde abajo) a la palabra activa.
 *
 * - Solo depende de [target] (caja del token visible en coordenadas raíz), no del reproductor.
 * - No tiene pointerInput: nunca intercepta toques. Está oculto para TalkBack.
 * - Se dibuja bajo la palabra, semitransparente, para no taparla.
 * - Con [reduceMotion] se mueve sin animación.
 */
@Composable
fun FingerPointerOverlay(
    target: Rect?,
    container: Rect?,
    reduceMotion: Boolean,
    fill: androidx.compose.ui.graphics.Color,
    outline: androidx.compose.ui.graphics.Color,
) {
    if (target == null || container == null) return
    val density = LocalDensity.current
    val width = with(density) { 14.dp.toPx() }
    val gap = with(density) { 1.dp.toPx() }
    val desired = Offset(target.center.x - container.left - width / 2f, target.bottom - container.top + gap)
    val visible = target.bottom > container.top && target.top < container.bottom
    val position by animateOffsetAsState(
        targetValue = desired,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "finger",
    )
    if (!visible) return
    Canvas(
        modifier = Modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .size(14.dp, 20.dp)
            .alpha(0.75f)
            .clearAndSetSemantics { },
    ) {
        drawFinger(fill, outline)
    }
}

/** Mano estilizada con el índice hacia arriba (dibujo propio). */
private fun DrawScope.drawFinger(fill: androidx.compose.ui.graphics.Color, outline: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.07f)
    val finger = Rect(Offset(w * 0.36f, 0f), Size(w * 0.28f, h * 0.55f))
    val palm = Rect(Offset(w * 0.12f, h * 0.42f), Size(w * 0.76f, h * 0.56f))
    val radiusFinger = CornerRadius(finger.width / 2f, finger.width / 2f)
    val radiusPalm = CornerRadius(w * 0.22f, w * 0.22f)
    drawRoundRect(fill, palm.topLeft, palm.size, radiusPalm)
    drawRoundRect(outline, palm.topLeft, palm.size, radiusPalm, style = stroke)
    drawRoundRect(fill, finger.topLeft, finger.size, radiusFinger)
    drawRoundRect(outline, finger.topLeft, finger.size, radiusFinger, style = stroke)
    // nudillos
    for (i in 1..2) {
        val x = palm.left + palm.width * (0.25f * i + 0.25f)
        drawLine(outline, Offset(x, palm.top + palm.height * 0.15f), Offset(x, palm.top + palm.height * 0.45f), strokeWidth = w * 0.05f)
    }
}
