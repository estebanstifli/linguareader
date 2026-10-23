package com.linguareader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.linguareader.R
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Paso de la lectura que sigue la mano: una palabra que suena o la pausa antes de la palabra [tokenId]. */
data class PointerStep(val tokenId: String, val gap: Boolean)

/** Ancho/alto del dibujo de la mano y posición de la yema del índice dentro de él (fracciones). */
private const val HAND_ASPECT = 544f / 1100f
private const val TIP_X = 0.06f
private const val TIP_Y = 0.006f

/** Cuánto se adelanta la yema a la voz. */
private const val ANTICIPATION_MS = 120f
/** Rigidez del seguimiento (rad/s): más alto = más pegado al objetivo. Amortiguamiento crítico, sin rebote. */
private const val OMEGA_X = 20f
private const val OMEGA_Y = 15f

/**
 * Mano decorativa que señala con el índice, desde abajo, la palabra que se lee, como el dedo de quien lee en voz alta.
 *
 * Algoritmo: un "cursor de lectura" avanza de forma continua por la línea a la velocidad medida de la voz
 * (píxeles por milisegundo, media móvil de las palabras ya leídas) y [ANTICIPATION_MS] por delante de ella, sin
 * pasar del comienzo de la palabra siguiente. Al acabar la palabra que cierra una línea se adelanta a la línea
 * siguiente; en la pausa entre frases va a la primera palabra de la frase siguiente. La mano no salta al cursor:
 * lo sigue con un muelle críticamente amortiguado calculado en cada fotograma, así el movimiento es continuo
 * aunque los eventos de palabra lleguen a saltos. La posición solo afecta a la colocación (sin recomponer).
 *
 * - [target] es la palabra a señalar y [lead] la siguiente (cajas en coordenadas raíz); [step] cambia con cada
 *   palabra o pausa entre frases.
 * - No tiene pointerInput: nunca intercepta toques. Está oculta para TalkBack. Con [reduceMotion] salta sin animar.
 */
@Composable
fun FingerPointerOverlay(
    target: Rect?,
    lead: Rect?,
    step: PointerStep?,
    playing: Boolean,
    container: Rect?,
    reduceMotion: Boolean,
) {
    if (container == null) return
    val density = LocalDensity.current
    val handHeight = 528.dp
    val handHeightPx = with(density) { handHeight.toPx() }
    val handWidthPx = handHeightPx * HAND_ASPECT
    val gapPx = with(density) { 2.dp.toPx() }

    // Última caja conocida: se mantiene un momento si la siguiente aún no se ve (p. ej., al pasar página).
    var shown by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(target) {
        if (target != null) shown = target else { delay(1500); shown = null }
    }
    val motion = remember(density) { HandMotion(initialSpeed = with(density) { 0.14.dp.toPx() }) }
    val inputs by rememberUpdatedState(Inputs(shown, lead, step, container, reduceMotion, gapPx))
    val box = shown

    LaunchedEffect(playing, box, step) {
        var last = 0L
        while (true) {
            val settled = withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000f).coerceAtMost(50f)
                last = now
                motion.frame(inputs, dt, playing)
            }
            if (settled && !playing) break // en pausa y quieta: no gastar fotogramas
        }
    }
    if (box == null || !motion.ready) return
    if (box.bottom <= container.top || box.top >= container.bottom) return
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Image(
            painter = painterResource(R.drawable.reading_hand),
            contentDescription = null,
            modifier = Modifier
                .offset { IntOffset((motion.x - handWidthPx * TIP_X).roundToInt(), (motion.y - handHeightPx * TIP_Y).roundToInt()) }
                .height(handHeight)
                .width(handHeight * HAND_ASPECT)
                .alpha(0.92f)
                .clearAndSetSemantics { },
        )
    }
}

private class Inputs(
    val box: Rect?,
    val lead: Rect?,
    val step: PointerStep?,
    val container: Rect,
    val reduceMotion: Boolean,
    val gapPx: Float,
)

/** Estado del seguimiento: posición (observable, en coordenadas del contenedor), velocidad y ritmo de la voz. */
private class HandMotion(initialSpeed: Float) {
    var x by mutableFloatStateOf(Float.NaN)
        private set
    var y by mutableFloatStateOf(Float.NaN)
        private set
    /** Ya tiene posición (estado observable que no cambia en cada fotograma, a diferencia de [x] e [y]). */
    var ready by mutableStateOf(false)
        private set
    private var vx = 0f
    private var vy = 0f

    /** Velocidad de lectura estimada, en píxeles por milisegundo. */
    private var speed = initialSpeed
    private var step: PointerStep? = null
    /** Reloj de reproducción (solo avanza sonando) y momento en que empezó la palabra actual. */
    private var clock = 0f
    private var wordStart = 0f
    private var lastWordTime = 0f
    /** Caja de la palabra actual; y la de la anterior mientras la nueva aún no se ha publicado. */
    private var wordBox: Rect? = null
    private var staleBox: Rect? = null
    private var goalX = Float.NaN
    private var goalY = Float.NaN

    /** Avanza un fotograma de [dt] ms. Devuelve true si la mano está quieta en su objetivo. */
    fun frame(input: Inputs, dt: Float, playing: Boolean): Boolean {
        val box = input.box ?: return true
        if (playing) clock += dt
        onStep(input.step)
        if (box != staleBox) {
            staleBox?.let { measure(it, box) }
            staleBox = null
            wordBox = box
            goal(input, box)
        }
        if (x.isNaN() || input.reduceMotion) {
            x = goalX; y = goalY; vx = 0f; vy = 0f
            ready = true
            return true
        }
        // Muelle críticamente amortiguado (Euler semiimplícito): x'' = ω²(objetivo − x) − 2ω·x'.
        val s = dt / 1000f
        vx += (OMEGA_X * OMEGA_X * (goalX - x) - 2f * OMEGA_X * vx) * s
        vy += (OMEGA_Y * OMEGA_Y * (goalY - y) - 2f * OMEGA_Y * vy) * s
        x += vx * s
        y += vy * s
        return abs(goalX - x) < 0.5f && abs(goalY - y) < 0.5f && abs(vx) < 5f && abs(vy) < 5f
    }

    private fun onStep(next: PointerStep?) {
        if (next == step) return
        val wasWord = step?.gap == false
        val isWord = next?.gap == false
        // Ritmo solo entre palabras seguidas; en la pausa entre frases no se mide.
        lastWordTime = if (wasWord && isWord) clock - wordStart else 0f
        // Hasta que llegue la caja nueva, el objetivo no se mueve (no volver a la palabra anterior). De la pausa a
        // su primera palabra la caja es la misma.
        if (!(step?.gap == true && isWord)) staleBox = wordBox
        step = next
        wordStart = clock
    }

    /** Ritmo de la voz: distancia entre el comienzo de la palabra anterior y el de la nueva, entre lo que tardó. */
    private fun measure(previous: Rect, current: Rect) {
        val distance = current.left - previous.left
        val sameLine = abs(current.bottom - previous.bottom) < current.height * 0.5f
        if (sameLine && distance > 0f && lastWordTime in 60f..1500f) speed = speed * 0.7f + distance / lastWordTime * 0.3f
    }

    /** Objetivo de la yema según la palabra, la siguiente y el tiempo que lleva sonando. */
    private fun goal(input: Inputs, box: Rect) {
        val c = input.container
        val lineY = box.bottom - c.top + input.gapPx
        val startX = box.left - c.left + min(box.width * 0.15f, 6f)
        if (step?.gap != false) { goalX = startX; goalY = lineY; return } // pausa: a la frase siguiente
        val travel = speed * (clock - wordStart + ANTICIPATION_MS)
        val lead = input.lead
        val leadSameLine = lead != null && abs(lead.bottom - box.bottom) < box.height * 0.5f && lead.left > box.left
        when {
            leadSameLine -> {
                val limit = lead!!.left - c.left + min(lead.width * 0.15f, 6f)
                goalX = startX + soften(travel, limit - startX); goalY = lineY
            }
            lead != null && startX + travel > box.right - c.left -> {
                // La palabra siguiente empieza otra línea: el dedo se adelanta a ella.
                goalX = lead.left - c.left + min(lead.width * 0.15f, 6f); goalY = lead.bottom - c.top + input.gapPx
            }
            else -> { goalX = startX + soften(travel, box.right - c.left - startX); goalY = lineY }
        }
    }

    /**
     * Recorrido [travel] limitado a [span] sin frenazo: lineal hasta el 60 % y después se acerca al límite cada vez
     * más despacio, como un dedo que espera a la palabra siguiente si la voz se demora (comas, palabras largas).
     */
    private fun soften(travel: Float, span: Float): Float {
        if (span <= 0f) return 0f
        val knee = span * 0.6f
        if (travel <= knee) return travel
        val rest = span - knee
        return knee + rest * (1f - exp(-(travel - knee) / rest))
    }
}
