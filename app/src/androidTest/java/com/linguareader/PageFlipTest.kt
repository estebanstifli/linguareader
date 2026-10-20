package com.linguareader

import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.content.BookImportService
import com.linguareader.core.reader.TtsEngineSelector
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.ScanSettingsRepository
import com.linguareader.di.EngineSettingsHolder
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.engine.supertonic.SupertonicModelDir
import com.linguareader.engine.supertonic.SupertonicModelManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Supertonic real: al terminar una página, la lectura pasa sola a la siguiente **sin** la pantalla
 * «Preparando la lectura…» (que solo es para el arranque). Se omite si el modelo no está instalado.
 *
 * La caché de audio se vacía antes: la página 2 está fría, el peor caso. En un emulador la síntesis va más
 * despacio que el audio (RTF > 1), así que además comprueba que la lectura sigue sin pantalla de espera.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class PageFlipTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var preferences: UserPreferencesRepository
    @Inject lateinit var scanSettings: ScanSettingsRepository
    @Inject lateinit var selector: TtsEngineSelector
    @Inject lateinit var models: SupertonicModelManager
    @Inject lateinit var importer: BookImportService
    @Inject lateinit var engineSettings: EngineSettingsHolder

    @Before
    fun setUp() {
        // Antes de la inyección: la caché de audio es un singleton que lee su carpeta al crearse.
        File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "audio-cache").deleteRecursively()
        hilt.inject()
        grantNotifications()
        assumeTrue("Modelo Supertonic no instalado en ${models.modelDir}", SupertonicModelDir(models.modelDir).isComplete())
        runBlocking {
            skipOnboarding(preferences, scanSettings, UserPreferences(translationSetupDone = true, readingLanguage = "en", autoContinue = true))
            withContext(Dispatchers.Main) { selector.selectEngine(SupertonicModel.ENGINE_ID) }
        }
    }

    private val PARAGRAPH_BREAK = "\n" + "\n"

    /** Libro importado de 3 páginas de 5 frases largas (~11 s de audio cada una): el productor no va por delante. */
    private fun importLongPages(): String {
        val text = (1..3).joinToString(PARAGRAPH_BREAK) { page ->
            (1..5).joinToString(" ") { s ->
                "In the quiet town number $page, sentence $s describes a long walk beside the river while the sun slowly set behind the hills and the birds returned home."
            }
        }
        val target = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val uri = android.net.Uri.fromFile(File(target.cacheDir, "long-pages.txt").apply { writeText(text) })
        return runBlocking { importer.commit(importer.read(uri), "Long Pages", "", "en", CefrLevel.B1) }
    }

    private fun overlayVisible() =
        compose.onAllNodes(hasText("Preparando la lectura", substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** La pantalla de preparación sale ~300 ms DESPUÉS de mostrar la página nueva: se vigila unos segundos más. */
    private fun watchOverlay(ms: Long = 8_000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (overlayVisible()) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun generatingVisible() =
        compose.onAllNodes(hasText("Generando audio", substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun pageLabelVisible(n: Int) =
        compose.onAllNodes(hasText("Pasaje $n de", substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun slowGenerationFlipsToTheNextPageWithoutThePreparingScreen() {
        // Más pasos de difusión = más calidad y más lento: la síntesis (RTF ≈ 1,7) no puede ir por delante de la
        // lectura, así que la página siguiente llega fría al cambiar de página (el caso que antes mostraba la pantalla).
        engineSettings.supertonicSteps = 12
        val id = importLongPages()
        try {
            compose.waitUntilAtLeastOneExists(hasScrollToNodeAction(), 15_000)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Long Pages"))
            openFromCatalog(compose, "Long Pages")
            compose.waitUntilAtLeastOneExists(hasText("Pasaje 1 de 3", substring = true), 30_000)
            val startDeadline = System.currentTimeMillis() + 240_000
            while (overlayVisible() && System.currentTimeMillis() < startDeadline) Thread.sleep(100)
            assertFalse("el arranque no terminó", overlayVisible())

            compose.onNodeWithContentDescription("Reproducir").performClick()
            var overlaySeen = false
            val flipDeadline = System.currentTimeMillis() + 420_000
            while (!pageLabelVisible(2) && System.currentTimeMillis() < flipDeadline) {
                if (overlayVisible()) overlaySeen = true
                Thread.sleep(100)
            }
            overlaySeen = overlaySeen || watchOverlay()
            Log.i("PageFlipTest", "lento: página 2 mostrada=${pageLabelVisible(2)} overlayVisto=$overlaySeen")
            assertTrue("la lectura no pasó sola a la página 2", pageLabelVisible(2))
            assertFalse("«Preparando la lectura» salió al cambiar de página", overlaySeen)

            // La lectura sigue en marcha (botón de pausa) y la voz arranca sola con la primera frase de la página 2.
            compose.onNodeWithContentDescription("Pausar").assertExists()
            val voiceDeadline = System.currentTimeMillis() + 120_000
            while (generatingVisible() && System.currentTimeMillis() < voiceDeadline) Thread.sleep(200)
            assertFalse("la voz de la página 2 no llegó a arrancar", generatingVisible())
            compose.onNodeWithContentDescription("Pausar").assertExists()
        } finally {
            engineSettings.supertonicSteps = SupertonicEngine.DEFAULT_STEPS
            runBlocking { importer.delete(id) }
        }
    }

    @Test
    fun readingFlipsToTheNextPageWithoutThePreparingScreen() {
        val id = importLongPages()
        try {
            readsIntoPageTwo()
        } finally {
            runBlocking { importer.delete(id) }
        }
    }

    private fun readsIntoPageTwo() {
        compose.waitUntilAtLeastOneExists(hasScrollToNodeAction(), 15_000)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Long Pages"))
        openFromCatalog(compose, "Long Pages")

        // Arranque: aquí SÍ puede salir la pantalla (primera vez, modelo sin cargar). Se espera a que acabe.
        compose.waitUntilAtLeastOneExists(hasText("Pasaje 1 de", substring = true), 30_000)
        val startDeadline = System.currentTimeMillis() + 180_000
        while (overlayVisible() && System.currentTimeMillis() < startDeadline) Thread.sleep(100)
        assertFalse("el arranque no terminó", overlayVisible())

        compose.onNodeWithContentDescription("Reproducir").performClick()

        // La lectura avanza sola a la página 2. En ningún momento debe salir la pantalla de preparación.
        var overlaySeen = false
        val flipDeadline = System.currentTimeMillis() + 300_000
        while (!pageLabelVisible(2) && System.currentTimeMillis() < flipDeadline) {
            if (overlayVisible()) overlaySeen = true
            Thread.sleep(100)
        }
        overlaySeen = overlaySeen || watchOverlay()
        Log.i("PageFlipTest", "página 2 mostrada=${pageLabelVisible(2)} overlayVisto=$overlaySeen")
        assertTrue("la lectura no pasó sola a la página 2", pageLabelVisible(2))
        assertFalse("«Preparando la lectura» salió al cambiar de página", overlaySeen)

        // Y sigue leyendo: el botón es de pausa (no se quedó parada).
        compose.waitUntilAtLeastOneExists(hasText("Pasaje 2 de", substring = true), 5_000)
        compose.onNodeWithContentDescription("Pausar").assertExists()
    }
}
