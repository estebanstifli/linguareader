package com.linguareader

import android.app.Application
import com.linguareader.di.TtsEngineCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LinguaReaderApp : Application() {

    @Inject lateinit var ttsCoordinator: TtsEngineCoordinator
    /** Se instancia al arrancar para que descargue el paquete de voces cuando el modelo quede instalado. */
    @Inject lateinit var voicePacks: com.linguareader.engine.packs.VoicePackManager

    override fun onCreate() {
        super.onCreate()
        ttsCoordinator.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ttsCoordinator.onTrimMemory(level)
    }
}
