package com.linguareader.shared

import com.linguareader.core.reader.AudioFocusGate
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.setActive
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue

/**
 * Foco de audio en iOS: la sesión en categoría `playback`; una interrupción (llamada, otra app) o desconectar los
 * auriculares pausa la lectura, como espera el sistema.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleAudioFocusGate : AudioFocusGate {

    private var onLoss: (() -> Unit)? = null
    private val observers = ArrayList<Any>()

    init {
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(AVAudioSessionInterruptionNotification, `object` = null, queue = NSOperationQueue.mainQueue) { note ->
            val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeBegan) onLoss?.invoke()
        }
        observers += center.addObserverForName(AVAudioSessionRouteChangeNotification, `object` = null, queue = NSOperationQueue.mainQueue) { note ->
            val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) onLoss?.invoke()
        }
    }

    override fun request(onLoss: () -> Unit): Boolean {
        this.onLoss = onLoss
        return runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }.getOrDefault(false)
    }

    override fun abandon() {
        onLoss = null
    }
}
