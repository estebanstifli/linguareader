package com.linguareader.shared

import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.StreamingSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Observa un Flow desde Swift: [block] se llama en el hilo principal con cada valor hasta [close].
 * (Sin SKIE, Swift no ve los Flows de Kotlin; este envoltorio es lo mínimo para la primera app.)
 */
class FlowWatcher internal constructor(private val scope: CoroutineScope) {
    fun close() = scope.cancel()
}

internal fun <T> watch(flow: Flow<T>, block: (T) -> Unit): FlowWatcher {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch { flow.collect { block(it) } }
    return FlowWatcher(scope)
}

/** Eventos del motor de voz (inicio, rangos de palabra, fin) para los tests y la UI. */
fun StreamingSpeechEngine.watchEvents(block: (SpeechEvent) -> Unit): FlowWatcher = watch(events, block)
