package com.linguareader.ui.reader

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linguareader.R

/**
 * Pantalla «Preparando la lectura…»: cubre el lector mientras el motor genera las primeras frases.
 * Barras de sonido animadas (quietas con «reducir movimiento»). Consume los toques para no dejar
 * pulsar el texto aún no listo.
 */
@Composable
fun PreparingOverlay(reduceMotion: Boolean, title: String) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            SoundBars(animate = !reduceMotion)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.preparing_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.preparing_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun SoundBars(animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "bars") // i18n-ignore: etiqueta de depuración
    val color = MaterialTheme.colorScheme.primary
    val bars = List(5) { i ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 520 + i * 90, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$i", // i18n-ignore: etiqueta de depuración
        )
    }
    Row(Modifier.height(72.dp).width(120.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        bars.forEachIndexed { i, state ->
            val level = if (animate) state.value else 0.5f
            Canvas(Modifier.size(width = 14.dp, height = 72.dp)) {
                val h = size.height * level
                drawRoundRect(color, topLeft = Offset(0f, (size.height - h) / 2), size = Size(size.width, h), cornerRadius = CornerRadius(size.width / 2))
            }
        }
    }
}
