package com.linguareader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import com.linguareader.core.reader.HighlightGranularity
import com.linguareader.core.reader.PlaybackStatus
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.reader.TtsEngineSelector
import com.linguareader.data.content.BookImportService
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.ScanSettingsRepository
import com.linguareader.engine.supertonic.SupertonicModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** Actual Supertonic + Media3 + reader Compose UI; only the database/system-TTS test doubles remain. */
@OptIn(ExperimentalTestApi::class)
class SupertonicReaderUiTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val preferences: UserPreferencesRepository by inject()
    private val scan: ScanSettingsRepository by inject()
    private val importer: BookImportService by inject()
    private val selector: TtsEngineSelector by inject()
    private val reader: ReaderAudioController by inject()
    private var bookId: String? = null
    private var savedPreferences: UserPreferences? = null

    @Before fun prepare() {
        grantNotifications()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            savedPreferences = preferences.preferences.first()
            skipOnboarding(preferences, scan, UserPreferences(ttsEngineId = SupertonicModel.ENGINE_ID,
                supertonicSteps = 5, supertonicLicenseAccepted = true, readingLanguage = "es",
                translationLanguage = "en", autoContinue = false, onboardingSummaryShown = true))
            selector.selectEngine(SupertonicModel.ENGINE_ID)
            val file = File(context.cacheDir, "internal-timing-reader.txt").apply { writeText(TEXT) }
            bookId = importer.commit(importer.read(Uri.fromFile(file)), TITLE, "", "es", CefrLevel.A2)
        }
    }

    @After fun cleanup() {
        runBlocking {
            reader.stop()
            bookId?.let { importer.delete(it) }
            savedPreferences?.let { original -> preferences.update { original } }
        }
    }

    @Test fun realAudioDrivesWordHighlightPauseResumeAndSentenceSeek() {
        compose.waitUntilAtLeastOneExists(hasScrollToNodeAction(), 15_000)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(TITLE))
        openFromCatalog(compose, TITLE)
        compose.waitUntilAtLeastOneExists(hasText("Ana lee", substring = true), 15_000)
        // The reader text appears before availability/loading enables the play button.
        // A semantics click on a disabled button is silently ignored by Compose.
        compose.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.player_play)) and isEnabled(), 90_000)
        compose.waitUntil(90_000) {
            compose.onAllNodesWithText(str(R.string.preparing_title)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription(str(R.string.player_play)).performClick()
        val observed = mutableSetOf<String>()
        try {
            compose.waitUntil(60_000) {
                val state = reader.playbackState.value
                assertNotEquals("Playback failed: $state", PlaybackStatus.ERROR, state.status)
                state.activeTokenId?.let(observed::add)
                state.engineId == SupertonicModel.ENGINE_ID && state.granularity == HighlightGranularity.WORD && observed.size >= 4
            }
        } catch (error: Throwable) {
            throw AssertionError("Observed ${observed.size} tokens; last playback state=${reader.playbackState.value}", error)
        }
        // Stop the real playback clock before asking Compose for an idle semantics snapshot;
        // otherwise continuous word updates can delay that snapshot until the clip has finished.
        runBlocking { reader.pause() }
        compose.waitUntil(5_000) { reader.playbackState.value.status == PlaybackStatus.PAUSED }
        val paused = reader.playbackState.value.resumeOffset
        compose.waitUntilAtLeastOneExists(hasText(str(R.string.player_sync_word), substring = true), 5_000)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(target.filesDir, "internal-timing-test").apply { mkdirs() }
        File(folder, "reader-semantics.txt").writeText(compose.onRoot().printToString())
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, "reader-word.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        Thread.sleep(250)
        assertEquals(paused, reader.playbackState.value.resumeOffset)
        runBlocking { reader.play() }
        compose.waitUntil(10_000) { reader.playbackState.value.isPlaying }
        runBlocking { reader.seekToSentence(1); reader.play() }
        compose.waitUntil(20_000) {
            val state = reader.playbackState.value
            state.sentenceIndex == 1 && state.granularity == HighlightGranularity.WORD && state.activeTokenId != null
        }
        File(folder, "reader-ui.txt").writeText("PASS: real Supertonic/Media3; ${observed.size} distinct highlighted tokens; pause/resume; seek to second sentence.\n")
    }

    private companion object {
        const val TITLE = "Prueba de sincronía interna"
        const val TEXT = "Ana lee un libro junto a la ventana mientras su hermano prepara el desayuno en la cocina. Después los dos salen al jardín para escuchar a los pájaros y disfrutar de la mañana."
    }
}
