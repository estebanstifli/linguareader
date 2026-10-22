package com.linguareader.shared

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.reader.AudioFocusGate
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.reader.StreamingReaderAudioController
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * Punto de entrada de la app iOS (Swift) al código compartido. Swift llama a [start] una vez con la carpeta de
 * datos de la app y después usa [books], [audio], [preferences] e [importFile]; los Flows se observan con
 * [watchCatalog] y [watchPlayback].
 *
 * El grafo es el mismo Koin que en Android con adaptadores de Apple: base Room con el SQLite empaquetado,
 * preferencias en DataStore (`createWithPath`), libros importados en `<dir>/imported/<id>/manifest.json`,
 * voz del sistema con AVSpeechSynthesizer y el `StreamingReaderAudioController` de `core`.
 */
object LinguaShared {

    private var koinInstance: Koin? = null
    private val koin: Koin get() = koinInstance ?: error("LinguaShared.start() must be called first")

    /** [appSupportDir]: carpeta persistente de la app (Application Support, excluida de la copia de seguridad). */
    fun start(appSupportDir: String) {
        if (koinInstance != null) return
        val root = appSupportDir.toPath()
        val fs = FileSystem.SYSTEM
        fs.createDirectories(root / "imported")
        koinInstance = startKoin {
            modules(
                module {
                    single<FileSystem> { fs }
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
                    single<AppDatabase> {
                        Room.databaseBuilder<AppDatabase>(name = (root / "linguareader.db").toString())
                            .setDriver(BundledSQLiteDriver())
                            .setQueryCoroutineContext(Dispatchers.IO)
                            .build()
                    }
                    single { get<AppDatabase>().progressDao() }
                    single { get<AppDatabase>().bookmarkDao() }
                    single { get<AppDatabase>().vocabularyDao() }
                    single {
                        UserPreferencesRepository(
                            PreferenceDataStoreFactory.createWithPath(scope = get()) { root / "user_preferences.preferences_pb" },
                            deviceLanguage = { NSLocale.preferredLanguages.firstOrNull()?.toString()?.substringBefore('-') ?: "en" },
                        )
                    }
                    single { DirectoryBookRepository(fs, root / "imported") } bind BookRepository::class
                    single { BookImporter(fs, root / "imported", get()) }
                    single<StreamingSpeechEngine> { AppleSpeechEngine() }
                    single<ReaderAudioController> { StreamingReaderAudioController(get(), AudioFocusGate.AlwaysGranted, Dispatchers.Main) }
                },
            )
        }.koin
    }

    val books: BookRepository get() = koin.get()
    val audio: ReaderAudioController get() = koin.get()
    val preferences: UserPreferencesRepository get() = koin.get()
    val speech: StreamingSpeechEngine get() = koin.get()
    val importer: BookImporter get() = koin.get()

    /** true si no hay ningún libro importado todavía (la app iOS importa entonces la muestra incluida). */
    fun isLibraryEmpty(): Boolean = koin.get<DirectoryBookRepository>().isEmpty()

    /** Importa un fichero (EPUB, TXT, FB2…) a la biblioteca y devuelve el id del libro. */
    @Throws(Throwable::class)
    suspend fun importFile(path: String, displayName: String, language: String?, level: CefrLevel?): String =
        importer.importFile(path, displayName, language, level)

    fun watchCatalog(block: (List<BookSummary>) -> Unit): FlowWatcher =
        watch(books.observeCatalog(CatalogFilter()), block)

    fun watchPlayback(block: (PlaybackState) -> Unit): FlowWatcher = watch(audio.playbackState, block)

    /** Versión del código compartido que ve la app (para la pantalla «Acerca de» y los logs de Device Farm). */
    const val VERSION = "0.2.0-ios"
}

internal val Path.string: String get() = toString()
