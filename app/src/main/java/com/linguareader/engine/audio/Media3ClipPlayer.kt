package com.linguareader.engine.audio

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.linguareader.core.reader.ClipEvent
import com.linguareader.core.reader.ClipPlayer
import com.linguareader.core.reader.ClipPosition
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * [ClipPlayer] sobre Media3/ExoPlayer: cola de WAV generados (un clip por frase) reproducidos
 * seguidos. Gestiona el foco de audio si [handleAudioFocus]. Debe usarse desde el hilo principal.
 */
class Media3ClipPlayer(context: Context, handleAudioFocus: Boolean) : ClipPlayer {

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            handleAudioFocus,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ClipEvent> = _events

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val tag = mediaItem?.mediaId?.toIntOrNull()
                Log.d(TAG, if (tag != null) "suena frase $tag" else "suena pausa")
                tag?.let { _events.tryEmit(ClipEvent.Started(it)) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) _events.tryEmit(ClipEvent.QueueEnded)
            }

            override fun onPlayerError(error: PlaybackException) {
                _events.tryEmit(ClipEvent.Error(error.errorCodeName))
            }
        })
    }

    override fun enqueue(tag: Int, path: String) = append {
        val ms = (File(path).length() - 44) * 1000 / (2 * 44_100)
        val ahead = (player.mediaItemCount - player.currentMediaItemIndex - 1).coerceAtLeast(0)
        Log.d(TAG, "encolada frase $tag (${ms} ms de audio; elementos por delante en cola: $ahead)")
        player.addMediaItem(MediaItem.Builder().setMediaId(tag.toString()).setUri(Uri.fromFile(File(path))).build())
    }

    /** Pausa como silencio en la propia cola de ExoPlayer: precisa y sin grabarla en el audio. */
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun enqueueSilence(durationMs: Long) {
        if (durationMs <= 0) return
        append { player.addMediaSource(SilenceMediaSource(durationMs * 1000)) }
    }

    private inline fun append(add: () -> Unit) {
        val ended = player.playbackState == Player.STATE_ENDED
        add()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        // Si la síntesis iba por detrás y la cola se había agotado, continuar con lo nuevo.
        if (ended) player.seekTo(player.mediaItemCount - 1, 0)
    }

    override fun clear() {
        player.stop()
        player.clearMediaItems()
    }

    override fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    override fun pause() = player.pause()

    override fun seekToTag(tag: Int): Boolean {
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == tag.toString() }
            ?: return false
        player.seekTo(index, 0)
        return true
    }

    override fun position(): ClipPosition? {
        val tag = player.currentMediaItem?.mediaId?.toIntOrNull() ?: return null
        return ClipPosition(tag, player.currentPosition)
    }

    override fun release() = player.release()

    private companion object {
        const val TAG = "ClipPlayer"
    }
}
