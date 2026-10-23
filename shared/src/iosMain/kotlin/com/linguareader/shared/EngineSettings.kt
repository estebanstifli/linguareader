package com.linguareader.shared

import com.linguareader.core.reader.SpeechPauses

/** Ajustes de motor que se leen en caliente desde los hilos de audio (los rellena LinguaShared desde las preferencias). */
class EngineSettings {
    var pauses: SpeechPauses = SpeechPauses()
    var audioCacheBytes: Long = 256L * 1024 * 1024
    var autoContinue: Boolean = true
    var ttsPitch: Float = 1.0f
}
