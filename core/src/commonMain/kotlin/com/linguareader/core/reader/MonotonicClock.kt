package com.linguareader.core.reader

import kotlin.time.TimeSource

/** Reloj monótono en ms (sustituye a `System.nanoTime()`); cada llamada crea un origen propio. */
internal fun monotonicClockMs(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
