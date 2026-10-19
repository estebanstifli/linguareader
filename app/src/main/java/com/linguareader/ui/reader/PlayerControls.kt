package com.linguareader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import com.linguareader.core.engine.VoiceInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    /** Versión baja (≈40 % menos de alto) para el lector a pantalla completa. */
    compact: Boolean = false,
    /** Voces del motor activo: el icono de voz solo sale si hay más de una entre las que elegir. */
    voices: List<VoiceInfo> = emptyList(),
    selectedVoice: String? = null,
    /** El motor sabe elegir la voz solo (Android TTS): se ofrece «Automática». */
    automaticVoice: Boolean = true,
    onVoice: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var voiceSheet by remember { mutableStateOf(false) }
    if (voiceSheet) VoicePickerSheet(voices, selectedVoice, automaticVoice, onSelect = { onVoice(it); voiceSheet = false }, onDismiss = { voiceSheet = false })
    val audioEnabled = playback.isAudioAvailable && playback.status != PlaybackStatus.PREPARING
    val small = if (compact) Modifier.size(36.dp) else Modifier
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        // En el lector a pantalla completa el margen inferior no varía al ocultar la barra de navegación (no re-pagina).
        val insets = if (compact) Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility) else Modifier.navigationBarsPadding()
        Column(insets.padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (voices.size > 1) {
                    IconButton(onClick = { voiceSheet = true }, modifier = small) {
                        Icon(painterResource(R.drawable.ic_record_voice_over), contentDescription = "Elegir voz")
                    }
                }
                IconButton(onClick = onPrevious, modifier = small) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Frase anterior")
                }
                IconButton(onClick = onRepeat, enabled = audioEnabled, modifier = small) {
                    Icon(painterResource(R.drawable.ic_replay), contentDescription = "Repetir frase")
                }
                FilledIconButton(onClick = onPlayPause, enabled = audioEnabled, modifier = Modifier.size(if (compact) 40.dp else 64.dp)) {
                    Icon(
                        painterResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (playback.isPlaying) "Pausar" else "Reproducir",
                        modifier = Modifier.size(if (compact) 24.dp else 36.dp),
                    )
                }
                IconButton(onClick = onNext, modifier = small) {
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
                if (compact) sync else "Frase ${(playback.sentenceIndex + 1).coerceAtMost(sentenceCount.coerceAtLeast(1))} de $sentenceCount · $sync",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Lista de voces del motor activo; «Automática» deja que la app elija la mejor voz sin conexión. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(voices: List<VoiceInfo>, selected: String?, automatic: Boolean, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Voz", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (automatic) item(key = "auto") { VoiceRow("Automática", "La mejor voz instalada sin conexión", selected == null) { onSelect(null) } }
            items(voices, key = { it.id }) { v ->
                VoiceRow(
                    v.displayName,
                    if (v.requiresNetwork) "Necesita conexión" else null,
                    selected == v.id,
                ) { onSelect(v.id) }
            }
        }
    }
}

@Composable
private fun VoiceRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun fmt(v: Float) = String.format(Locale.ROOT, if (v * 100 % 10 == 0f) "%.1f" else "%.2f", v)
