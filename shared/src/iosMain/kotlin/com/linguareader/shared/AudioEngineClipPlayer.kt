package com.linguareader.shared

import com.linguareader.core.reader.ClipEvent
import com.linguareader.core.reader.ClipPlayer
import com.linguareader.core.reader.ClipPosition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioUnitTimePitch
import platform.AVFAudio.setActive
import platform.Foundation.NSURL

/**
 * [ClipPlayer] sobre `AVAudioEngine` + `AVAudioPlayerNode`: cola de clips WAV (uno por frase) programados como
 * búferes PCM, sin huecos entre ellos; los silencios son búferes de ceros. La velocidad se cambia con
 * `AVAudioUnitTimePitch` (conserva el tono). La posición se calcula con el tiempo del nodo (exacta en muestras).
 * Los ficheros se leen con `AVAudioFile` y se remuestrean al formato del motor (44,1 kHz mono). Debe usarse desde
 * el hilo principal, como el de Android.
 */
@OptIn(ExperimentalForeignApi::class)
class AudioEngineClipPlayer : ClipPlayer {

    private val _events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ClipEvent> = _events

    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private val timePitch = AVAudioUnitTimePitch()
    private val format = AVAudioFormat(standardFormatWithSampleRate = SAMPLE_RATE.toDouble(), channels = 1u)

    private class Item(val tag: Int?, val buffer: AVAudioPCMBuffer, val startFrame: Long) {
        val frames: Long get() = buffer.frameLength.toLong()
        var done = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayList<Item>()
    private var scheduledFrames = 0L
    private var playing = false
    private var queueEndedSent = true
    private var speed = 1f
    private var generation = 0

    init {
        engine.attachNode(node)
        engine.attachNode(timePitch)
        engine.connect(node, to = timePitch, format = format)
        engine.connect(timePitch, to = engine.mainMixerNode, format = format)
        engine.prepare()
    }

    private fun ensureEngine(): Boolean {
        if (engine.running) return true
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }
        return engine.startAndReturnError(null)
    }

    override fun enqueue(tag: Int, path: String) {
        val buffer = load(path) ?: run {
            Diagnostics.log(TAG, "cannot read clip $tag: $path")
            _events.tryEmit(ClipEvent.Error("No se pudo leer $path"))
            return
        }
        append(Item(tag, buffer, scheduledFrames))
    }

    override fun enqueueSilence(durationMs: Long) {
        if (durationMs <= 0) return
        val frames = (durationMs * SAMPLE_RATE / 1000).toUInt()
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frames) ?: return
        buffer.frameLength = frames
        append(Item(null, buffer, scheduledFrames))
    }

    private fun append(item: Item) {
        val wasEmpty = queue.none { !it.done }
        queue.add(item)
        scheduledFrames += item.frames
        schedule(item, generation)
        queueEndedSent = false
        // Si la cola se había agotado mientras seguía «reproduciendo», el clip nuevo empieza ahora.
        if (wasEmpty && playing) item.tag?.let { _events.tryEmit(ClipEvent.Started(it)) }
    }

    private fun schedule(item: Item, gen: Int) {
        node.scheduleBuffer(item.buffer, atTime = null, options = 0u, completionCallbackType = AVAudioPlayerNodeCompletionDataPlayedBack) { _ ->
            // El callback llega en un hilo de audio: el estado se toca en el principal.
            scope.launch { onPlayed(item, gen) }
        }
    }

    private fun onPlayed(item: Item, gen: Int) {
        if (gen != generation) return
        item.done = true
        val next = queue.firstOrNull { !it.done }
        if (next == null) {
            if (!queueEndedSent) {
                queueEndedSent = true
                _events.tryEmit(ClipEvent.QueueEnded)
            }
        } else {
            next.tag?.let { _events.tryEmit(ClipEvent.Started(it)) }
        }
    }

    override fun clear() {
        generation++
        node.stop()
        queue.clear()
        scheduledFrames = 0
        queueEndedSent = true
    }

    override fun play() {
        if (!ensureEngine()) {
            Diagnostics.log(TAG, "AVAudioEngine did not start")
            _events.tryEmit(ClipEvent.Error("AVAudioEngine no arranca"))
            return
        }
        val first = queue.firstOrNull { !it.done }
        val wasPlaying = playing
        playing = true
        node.play()
        if (!wasPlaying && first?.tag != null && node.lastRenderTime == null) _events.tryEmit(ClipEvent.Started(first.tag))
        else if (!wasPlaying && first?.tag != null) _events.tryEmit(ClipEvent.Started(first.tag))
    }

    override fun pause() {
        playing = false
        node.pause()
    }

    override fun seekToTag(tag: Int): Boolean = seekTo(tag, 0)

    override fun seekTo(tag: Int, positionMs: Long): Boolean {
        val index = queue.indexOfFirst { it.tag == tag }
        if (index < 0) return false
        val wasPlaying = playing
        generation++
        node.stop()
        val remaining = queue.drop(index)
        queue.clear()
        scheduledFrames = 0
        val skipFrames = (positionMs.coerceAtLeast(0) * SAMPLE_RATE / 1000)
        remaining.forEachIndexed { i, old ->
            val buffer = if (i == 0 && skipFrames > 0 && skipFrames < old.frames) slice(old.buffer, skipFrames.toInt()) else old.buffer
            val item = Item(old.tag, buffer, scheduledFrames)
            queue.add(item)
            scheduledFrames += item.frames
            schedule(item, generation)
        }
        queueEndedSent = false
        if (wasPlaying) {
            node.play()
            queue.firstOrNull()?.tag?.let { _events.tryEmit(ClipEvent.Started(it)) }
        }
        return true
    }

    override fun position(): ClipPosition? {
        val current = queue.firstOrNull { !it.done } ?: return null
        val tag = current.tag ?: return null
        val nodeTime = node.lastRenderTime ?: return ClipPosition(tag, 0)
        val playerTime = node.playerTimeForNodeTime(nodeTime) ?: return ClipPosition(tag, 0)
        val sample = playerTime.sampleTime - current.startFrame
        return ClipPosition(tag, (sample.coerceAtLeast(0) * 1000 / SAMPLE_RATE))
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
        timePitch.rate = speed.coerceIn(0.25f, 4f)
    }

    override fun release() {
        clear()
        engine.stop()
    }

    /** Lee un fichero de audio (WAV generado o cualquier formato que entienda AVAudioFile) al formato del motor. */
    private fun load(path: String): AVAudioPCMBuffer? {
        val url = if ("://" in path) NSURL(string = path) else NSURL.fileURLWithPath(path)
        val file = AVAudioFile(forReading = url, error = null) ?: return null
        val sourceFormat = file.processingFormat
        val frames = file.length.toUInt()
        val source = AVAudioPCMBuffer(pCMFormat = sourceFormat, frameCapacity = frames) ?: return null
        if (!file.readIntoBuffer(source, error = null)) return null
        val n = source.frameLength.toInt()
        val channel = source.floatChannelData?.get(0) ?: return null
        val sourceRate = sourceFormat.sampleRate
        if (sourceRate.toInt() == SAMPLE_RATE) {
            val out = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = n.toUInt()) ?: return null
            out.frameLength = n.toUInt()
            val dst = out.floatChannelData!![0]!!
            for (i in 0 until n) dst[i] = channel[i]
            return out
        }
        // Remuestreo lineal al formato del motor (los WAV del TTS suelen venir a 22,05 kHz).
        val ratio = sourceRate / SAMPLE_RATE
        val outFrames = (n / ratio).toInt().coerceAtLeast(1)
        val out = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = outFrames.toUInt()) ?: return null
        out.frameLength = outFrames.toUInt()
        val dst = out.floatChannelData!![0]!!
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, n - 1)
            val next = (idx + 1).coerceAtMost(n - 1)
            val frac = (pos - idx).toFloat()
            dst[i] = channel[idx] * (1 - frac) + channel[next] * frac
        }
        return out
    }

    private fun slice(buffer: AVAudioPCMBuffer, from: Int): AVAudioPCMBuffer {
        val n = buffer.frameLength.toInt() - from
        val out = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = n.toUInt())!!
        out.frameLength = n.toUInt()
        val src = buffer.floatChannelData!![0]!!
        val dst = out.floatChannelData!![0]!!
        for (i in 0 until n) dst[i] = src[from + i]
        return out
    }

    private companion object {
        const val TAG = "ClipPlayer"
        const val SAMPLE_RATE = 44_100
    }
}
