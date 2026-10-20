package com.linguareader

import android.app.Application
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.CatalogFilter
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.DocumentScanner
import com.linguareader.di.ApplicationScope
import com.linguareader.di.TtsEngineCoordinator
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltAndroidApp
class LinguaReaderApp : Application() {

    // Todo perezoso: construir el motor de voz o abrir la base de datos en el hilo principal retrasa el primer frame.
    @Inject lateinit var ttsCoordinator: Lazy<TtsEngineCoordinator>
    /** Se instancia al arrancar para que descargue el paquete de voces cuando el modelo quede instalado. */
    @Inject lateinit var voicePacks: Lazy<com.linguareader.engine.packs.VoicePackManager>
    @Inject lateinit var preferences: Lazy<UserPreferencesRepository>
    @Inject lateinit var database: Lazy<AppDatabase>
    @Inject lateinit var books: Lazy<BookRepository>
    @Inject lateinit var documentScanner: Lazy<DocumentScanner>
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // En paralelo con la creación de la actividad y la primera composición: cuando la biblioteca pide
        // preferencias, base de datos y resúmenes de libros, ya están en memoria (antes llegaban 2 s tarde).
        scope.launch(Dispatchers.IO) { preferences.get().preferences.first() }
        scope.launch(Dispatchers.IO) { database.get().openHelper.writableDatabase }
        scope.launch(Dispatchers.IO) { books.get().observeCatalog(CatalogFilter()).first() }
    }

    private val deferredStarted = AtomicBoolean(false)

    /**
     * Lo pesado del arranque (motor de voz, paquetes de voces, búsqueda automática de documentos), una vez dibujado el
     * primer frame. Construirlo antes, aunque sea en segundo plano, retiene los cerrojos de Hilt mientras la actividad
     * espera los suyos para inyectarse: el primer frame se retrasaba segundos (y con el sistema cargado, ANR).
     */
    fun startDeferredServices() {
        if (!deferredStarted.compareAndSet(false, true)) return
        scope.launch {
            ttsCoordinator.get().start()
            voicePacks.get()
            // Ajustes › Examinar archivos › «Buscar al abrir la app».
            documentScanner.get().startIfDue()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Sin arrancar aún no hay modelos que liberar (y construir el motor aquí sería justo lo que se evita).
        if (deferredStarted.get()) ttsCoordinator.get().onTrimMemory(level)
    }
}
