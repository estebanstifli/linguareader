package com.linguareader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linguareader.R
import com.linguareader.core.reader.HighlightGranularity
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.PlaybackStatus
import java.util.Locale

private val SPEEDS = listOf(0.5f, 0.75f, 0.9f, 1.0f, 1.25f, 1.5f)

@Composable
fun PlayerControls(
    playback: PlaybackState,
    sentenceCount: Int,
    speed: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onRepeat: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    val audioEnabled = playback.isAudioAvailable && playback.status != PlaybackStatus.PREPARING
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Frase anterior")
                }
                IconButton(onClick = onRepeat, enabled = audioEnabled) {
                    Icon(painterResource(R.drawable.ic_replay), contentDescription = "Repetir frase")
                }
                FilledIconButton(onClick = onPlayPause, enabled = audioEnabled, modifier = Modifier.size(64.dp)) {
                    Icon(
                        painterResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (playback.isPlaying) "Pausar" else "Reproducir",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Frase siguiente")
                }
                if (playback.supportsSpeed) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(
                            onClick = { open = true },
                            modifier = Modifier.semantics { contentDescription = "Velocidad ${fmt(speed)}" },
                        ) { Text("${fmt(speed)}×") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            SPEEDS.forEach { s ->
                                DropdownMenuItem(text = { Text("${fmt(s)}×") }, onClick = { onSpeed(s); open = false })
                            }
                        }
                    }
                }
            }
            val sync = when {
                !playback.isAudioAvailable -> "Sin audio"
                playback.buffering && playback.isPlaying -> "Generando audio…"
                playback.engineLacksWordTimings && playback.granularity == HighlightGranularity.WORD ->
                    "Voz sintética (Supertonic) · sincronía por palabra (alineación forzada)"
                playback.engineLacksWordTimings && (playback.status == PlaybackStatus.PLAYING || playback.status == PlaybackStatus.PAUSED) ->
                    "Voz sintética (Supertonic) · sincronía por frase: el motor no da tiempos por palabra"
                playback.granularity == HighlightGranularity.WORD -> "Sincronía por palabra (eventos del motor)"
                playback.status == PlaybackStatus.PLAYING || playback.status == PlaybackStatus.PAUSED ->
                    if (playback.wordEventsObserved) "Sincronía por frase en esta frase" else "Sincronía por frase (el motor no envía tiempos por palabra)"
                else -> "Listo"
            }
            Text(
                "Frase ${(playback.sentenceIndex + 1).coerceAtMost(sentenceCount.coerceAtLeast(1))} de $sentenceCount · $sync",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private fun fmt(v: Float) = String.format(Locale.ROOT, if (v * 100 % 10 == 0f) "%.1f" else "%.2f", v)
