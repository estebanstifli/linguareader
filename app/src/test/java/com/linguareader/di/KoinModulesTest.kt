package com.linguareader.di

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify

private val RUNTIME_TYPES = listOf(
    Context::class, SavedStateHandle::class,
    androidx.datastore.core.DataStore::class, Function0::class, Function1::class, List::class, Map::class, String::class,
    okio.Path::class, kotlinx.coroutines.CoroutineDispatcher::class,
    okio.FileSystem::class, Int::class, Long::class, Boolean::class, Function2::class,
    com.linguareader.engine.supertonic.MemoryProbe::class, com.linguareader.core.audio.FileSpeechSynthesizer::class,
    com.linguareader.core.reader.NarratedReaderAudioController::class, com.linguareader.core.engine.TtsEngine::class,
    com.linguareader.importer.DocumentExtractor::class,
)

/**
 * Lo que Hilt comprobaba al compilar: que cada constructor del grafo tiene registradas sus dependencias.
 * `Context` y `SavedStateHandle` los aporta Koin en tiempo de ejecución (androidContext, koinViewModel); el resto
 * son parámetros de las definiciones escritas a mano en AppModule (lambdas, listas, rutas), que no salen del grafo.
 */
class KoinModulesTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `every dependency of every definition is bound`() {
        module { includes(appModules) }.verify(extraTypes = RUNTIME_TYPES)
    }
}
