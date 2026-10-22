package com.linguareader

import android.app.Application
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.CatalogFilter
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.DocumentScanner
import com.linguareader.di.TtsEngineCoordinator
import com.linguareader.di.appModules
import com.linguareader.engine.packs.VoicePackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.module.Module
import java.util.concurrent.atomic.AtomicBoolean

open class LinguaReaderApp : Application() {

    // Todo perezoso: construir el motor de voz o abrir la base de datos en el hilo principal retrasa el primer frame.
    private val ttsCoordinator: TtsEngineCoordinator by inject()
    /** Se instancia al arrancar para que descargue el paquete de voces cuando el modelo quede instalado. */
    private val voicePacks: VoicePackManager by inject()
    private val preferences: UserPreferencesRepository by inject()
    private val database: AppDatabase by inject()
    private val books: BookRepository by inject()
    private val documentScanner: DocumentScanner by inject()
    private val scope: CoroutineScope by inject()

    /** Módulos que se superponen a los de la app (los tests instrumentados: motor de voz falso, base en memoria). */
    protected open fun overrideModules(): List<Module> = emptyList()

    override fun onCreate() {
        super.onCreate()
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidLogger(Level.ERROR)
                androidContext(this@LinguaReaderApp)
                allowOverride(true)
                modules(appModules + overrideModules())
            }
        }
        // En paralelo con la creación de la actividad y la primera composición: cuando la biblioteca pide
        // preferencias, base de datos y resúmenes de libros, ya están en memoria (antes llegaban 2 s tarde).
        scope.launch(Dispatchers.IO) { preferences.preferences.first() }
        scope.launch(Dispatchers.IO) { database.openHelper.writableDatabase }
        scope.launch(Dispatchers.IO) { books.observeCatalog(CatalogFilter()).first() }
    }

    private val deferredStarted = AtomicBoolean(false)

    /**
     * Lo pesado del arranque (motor de voz, paquetes de voces, búsqueda automática de documentos), una vez dibujado el
     * primer frame. Construirlo antes, aunque sea en segundo plano, retiene los cerrojos del grafo mientras la
     * actividad espera los suyos para inyectarse: el primer frame se retrasaba segundos (y con el sistema cargado, ANR).
     */
    open fun startDeferredServices() {
        if (!deferredStarted.compareAndSet(false, true)) return
        scope.launch {
            ttsCoordinator.start()
            voicePacks
            // Ajustes › Examinar archivos › «Buscar al abrir la app».
            documentScanner.startIfDue()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Sin arrancar aún no hay modelos que liberar (y construir el motor aquí sería justo lo que se evita).
        if (deferredStarted.get()) ttsCoordinator.onTrimMemory(level)
    }
}
