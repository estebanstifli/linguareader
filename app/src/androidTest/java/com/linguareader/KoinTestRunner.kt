package com.linguareader

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Arranca la app de pruebas: el grafo real con los módulos de [TestModules.kt] superpuestos. */
class KoinTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestApp::class.java.name, context)
}

class TestApp : LinguaReaderApp() {
    override fun overrideModules() = listOf(testModule)

    /** Los tests no arrancan los servicios diferidos (motor de voz, paquetes, escaneo); lo que necesitan lo piden al grafo. */
    override fun startDeferredServices() {}
}
