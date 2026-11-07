package com.linguareader.shared

/**
 * Resultado de «Test the neural voice» en Ajustes: tiempos de carga y de síntesis, audio generado, factor de tiempo
 * real (RTF < 1 = más rápido que el audio), memoria del proceso (`phys_footprint`) e hilos. -1 = sin dato.
 */
data class NeuralVoiceTest(
    val loadMs: Long = -1,
    val synthMs: Long = 0,
    val audioMs: Long = 0,
    val steps: Int = 0,
    val memoryMb: Long = -1,
    val threads: Int = 0,
    val wavPath: String? = null,
    val error: String? = null,
) {
    val rtf: Double get() = if (audioMs > 0) synthMs.toDouble() / audioMs else 0.0
}
