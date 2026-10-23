package com.linguareader.shared

import com.linguareader.core.reader.ClipEvent
import com.linguareader.core.reader.ClipPlayer
import com.linguareader.core.reader.ClipPosition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSURL
import platform.darwin.NSObject

/**
 * [ClipPlayer] para pistas largas (narración pregrabada en MP3): `AVAudioPlayer`, que decodifica en streaming y
 * permite saltar por tiempo. Un solo clip a la vez (el controlador narrado encola la pista del pasaje).
 */
@OptIn(ExperimentalForeignApi::class)
class TrackClipPlayer : ClipPlayer {

    private val _events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ClipEvent> = _events

    private var player: AVAudioPlayer? = null
    private var tag: Int? = null
    private var speed = 1f

    private val delegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            _events.tryEmit(ClipEvent.QueueEnded)
        }
    }

    override fun enqueue(tag: Int, path: String) {
        val url = if ("://" in path) NSURL(string = path) else NSURL.fileURLWithPath(path)
        val p = AVAudioPlayer(contentsOfURL = url, error = null) ?: run {
            _events.tryEmit(ClipEvent.Error("No se pudo abrir $path"))
            return
        }
        player?.stop()
        p.delegate = delegate
        p.enableRate = true
        p.rate = speed
        p.prepareToPlay()
        player = p
        this.tag = tag
    }

    override fun enqueueSilence(durationMs: Long) = Unit

    override fun clear() {
        player?.stop()
        player = null
        tag = null
    }

    override fun play() {
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }
        val p = player ?: return
        if (p.play()) tag?.let { _events.tryEmit(ClipEvent.Started(it)) }
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekToTag(tag: Int): Boolean = seekTo(tag, 0)

    override fun seekTo(tag: Int, positionMs: Long): Boolean {
        if (this.tag != tag) return false
        player?.currentTime = positionMs.coerceAtLeast(0) / 1000.0
        return true
    }

    override fun position(): ClipPosition? {
        val p = player ?: return null
        val t = tag ?: return null
        return ClipPosition(t, (p.currentTime * 1000).toLong())
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
        player?.rate = speed
    }

    override fun release() = clear()
}
