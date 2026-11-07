package com.linguareader.engine.supertonic

import com.linguareader.core.platform.isLetterOrDigitCodePoint
import com.linguareader.core.platform.isMarkCodePoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A candidate from Supertonic's own cross-attention. Confidence is not a calibrated probability. */
data class SupertonicWordTiming(
    val start: Int,
    val end: Int,
    val startSample: Int,
    val endSample: Int,
    val confidence: Float,
)

/**
 * Frozen lab recipe: block 21, heads 2/7, conditional branch, steps 1–4 of five.
 * One compact [latent,text] map is averaged during inference. No ASR, extra model or per-head
 * capture is retained. Unicode source spans are UTF-16 offsets of the requested text.
 */
internal object SupertonicWordTimings {
    const val VERSION = "attention-v1-unicode"
    const val STEPS = 5
    private const val MAX_CELLS = 2_000_000
    private const val EDGE_DB_SCALE = 0.05623413251903491 // 10 ^ (-25 / 20)

    fun decode(
        attention: FloatArray,
        frames: Int,
        encoded: SupertonicTextProcessor.Encoded,
        samples: FloatArray,
        sampleRate: Int,
        hopSamples: Int,
        checkCancelled: () -> Unit = {},
    ): List<SupertonicWordTiming> {
        val window = max(1, sampleRate / 100)
        val rms = FloatArray((samples.size + window - 1) / window)
        for (frame in rms.indices) {
            var sum = 0.0
            for (i in frame * window until min(samples.size, (frame + 1) * window)) sum += samples[i].toDouble() * samples[i]
            // Final window has zero padding, as in the reference implementation.
            rms[frame] = sqrt(sum / window).toFloat()
        }
        return decodeEnvelope(attention, frames, encoded, samples.size, sampleRate, hopSamples, rms, checkCancelled)
    }

    internal fun decodeEnvelope(
        attention: FloatArray,
        frames: Int,
        encoded: SupertonicTextProcessor.Encoded,
        sampleCount: Int,
        sampleRate: Int,
        hopSamples: Int,
        rms: FloatArray,
        checkCancelled: () -> Unit = {},
    ): List<SupertonicWordTiming> {
        val words = encoded.words
        val chars = encoded.ids.size
        if (!encoded.timingSafe || words.isEmpty() || frames <= 0 || chars <= 0 || sampleCount <= 0 ||
            sampleRate <= 0 || hopSamples <= 0 || frames.toLong() * chars != attention.size.toLong() ||
            encoded.sourceStarts.size != chars || encoded.sourceEnds.size != chars || encoded.codePoints.size != chars ||
            attention.any { !it.isFinite() || it < 0 } || attention.none { it > 0f } ||
            rms.any { !it.isFinite() || it < 0 }) return emptyList()

        val columns = Array(words.size) { ArrayList<Int>() }
        for (c in 0 until chars) {
            if (encoded.sourceStarts[c] < 0 || !(isLetterOrDigitCodePoint(encoded.codePoints[c]) || isMarkCodePoint(encoded.codePoints[c]))) continue
            var match = -1
            for (i in words.indices) {
                if (encoded.sourceStarts[c] < words[i].end && encoded.sourceEnds[c] > words[i].start) {
                    if (match >= 0) return emptyList() // A normalized expansion crosses word ownership.
                    match = i
                }
            }
            if (match >= 0) columns[match].add(c)
        }
        if (columns.any { it.isEmpty() } || frames.toLong() * words.size > MAX_CELLS) return emptyList()
        val scores = DoubleArray(frames * words.size)
        var totalWordMass = 0.0
        for (frame in 0 until frames) {
            if (frame % 128 == 0) checkCancelled()
            var rowSum = 0.0
            for (word in words.indices) {
                var sum = 0.0
                for (column in columns[word]) sum += attention[frame * chars + column]
                val value = sum / sqrt(columns[word].size.toDouble())
                scores[frame * words.size + word] = value; rowSum += value
            }
            totalWordMass += rowSum
            for (word in words.indices) scores[frame * words.size + word] /= max(rowSum, 1e-12)
        }
        if (totalWordMass <= 0.0) return emptyList()
        val duration = sampleCount.toDouble() / sampleRate
        val hop = hopSamples.toDouble() / sampleRate
        val window = max(1, sampleRate / 100)
        val threshold = max(1e-5, (rms.maxOrNull() ?: 0f) * EDGE_DB_SCALE)
        val active = BooleanArray(rms.size) { rms[it] > threshold }
        val first = active.indexOfFirst { it }; val last = active.indexOfLast { it }
        if (first < 0) return emptyList() // A silent clip cannot establish spoken word boundaries.
        fun energyTime(index: Int) = (index + .5) * window / sampleRate
        val edgeStart = max(0.0, energyTime(first) - .005)
        val edgeEnd = min(duration, energyTime(last) + .005)
        val gridHop = max(hop / 4, 1e-4)
        val count = ceil((edgeEnd - edgeStart) / gridHop).toInt()
        if (count < words.size || count.toLong() * words.size > MAX_CELLS) return emptyList()

        fun interpolate(frame: Int, word: Int): Double {
            val at = (edgeStart + frame * gridHop) / hop - .5
            val left = floor(at).toInt()
            if (left < 0) return scores[word]
            if (left >= frames - 1) return scores[(frames - 1) * words.size + word]
            val fraction = at - left
            return scores[left * words.size + word] * (1 - fraction) + scores[(left + 1) * words.size + word] * fraction
        }
        val back = ByteArray(count * words.size)
        var previous = DoubleArray(words.size) { Double.NEGATIVE_INFINITY }
        var next = DoubleArray(words.size)
        previous[0] = ln(max(interpolate(0, 0), 1e-12))
        for (frame in 1 until count) {
            if (frame % 128 == 0) checkCancelled()
            for (word in words.indices) {
                val advance = if (word > 0) previous[word - 1] else Double.NEGATIVE_INFINITY
                val take = advance > previous[word]
                back[frame * words.size + word] = if (take) 1 else 0
                next[word] = max(previous[word], advance) + ln(max(interpolate(frame, word), 1e-12))
            }
            val old = previous; previous = next; next = old
        }
        val path = IntArray(count); path[count - 1] = words.lastIndex
        for (frame in count - 1 downTo 1) path[frame - 1] = path[frame] - back[frame * words.size + path[frame]]
        if (path[0] != 0) return emptyList()
        val begin = IntArray(words.size) { -1 }; val finish = IntArray(words.size); val confidence = DoubleArray(words.size)
        for (frame in path.indices) {
            val word = path[frame]
            if (begin[word] < 0) begin[word] = frame
            finish[word] = frame
            confidence[word] += interpolate(frame, word)
        }
        val output = ArrayList<SupertonicWordTiming>(words.size)
        var previousEnd = 0
        for (word in words.indices) {
            if (begin[word] < 0) return emptyList()
            var start = edgeStart + begin[word] * gridHop
            var end = edgeStart + finish[word] * gridHop + hop / 4
            var firstInside = -1; var lastInside = -1
            for (e in active.indices) {
                if (active[e] && energyTime(e) >= start && energyTime(e) <= end) {
                    if (firstInside < 0) firstInside = e
                    lastInside = e
                }
            }
            if (firstInside >= 0) {
                start = max(start, energyTime(firstInside) - .015)
                end = min(end, energyTime(lastInside) + .015)
            }
            start = (start - .040).coerceIn(0.0, duration)
            end = (end - .040).coerceIn(start, duration)
            val from = (start * sampleRate).roundToInt().coerceIn(0, sampleCount)
            val to = (end * sampleRate).roundToInt().coerceIn(from, sampleCount)
            if (from < previousEnd || to <= from) return emptyList()
            output += SupertonicWordTiming(words[word].start, words[word].end, from, to,
                (confidence[word] / (finish[word] - begin[word] + 1)).toFloat().coerceIn(0f, 1f))
            previousEnd = to
        }
        return output
    }
}
