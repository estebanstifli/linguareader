package com.linguareader

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.catalog.OnlineBookImporter
import com.linguareader.data.catalog.OnlineCatalog
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.ScanSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** Live Cloudflare + Gutenberg smoke test: no mocked catalog, download, extractor or reader. */
@OptIn(ExperimentalTestApi::class)
class OnlineLibraryFlowTest : KoinComponent {
    @get:Rule(order = 0) val english = object : ExternalResource() {
        override fun before() {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd locale set-app-locales com.lingoaudiobook.app --locales en")).use { it.readBytes() }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val preferences: UserPreferencesRepository by inject()
    private val scan: ScanSettingsRepository by inject()
    private val provider: OnlineCatalog by inject()
    private val importer: OnlineBookImporter by inject()
    private val repository: BookRepository by inject()

    @Before fun setup() = runBlocking {
        skipOnboarding(preferences, scan, UserPreferences(readingLanguage = "en", translationLanguage = "es"))
    }

    @Test fun browseFilterConfirmImportAndReadRealBook() {
        compose.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.common_menu)), 15_000)
        compose.onNodeWithContentDescription(str(R.string.common_menu)).performClick()
        compose.onNode(hasText(str(R.string.section_books)) and hasClickAction()).assertExists()
        compose.onNodeWithText(str(R.string.online_library)).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Fiction"), 45_000)
        compose.waitUntilAtLeastOneExists(hasContentDescription("Pride and Prejudice"), 30_000)
        screenshot("01-library")

        compose.onNodeWithText("Romance").performScrollTo().performClick()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.onNodeWithTag("online_level_B1").performScrollTo().performClick()
        compose.onNodeWithTag("online_search").performTextReplacement("Pride and Prejudice")
        compose.onNodeWithTag("online_search").performImeAction()
        compose.waitUntilAtLeastOneExists(hasTestTag("online_book_1342"), 45_000)
        compose.onNodeWithTag("online_books").performScrollToNode(hasTestTag("online_book_1342"))
        screenshot("02-filtered")
        compose.onNodeWithTag("online_book_1342").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Elizabeth Bennet", substring = true), 45_000)
        screenshot("03-cover-details")
        compose.onNodeWithText("69.2", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("English").assertExists()
        screenshot("03-book-details")

        val book = runBlocking { provider.book(1342) }
        val existed = runBlocking { importer.exists(provider, book) }
        if (!existed) {
            compose.onNodeWithText(str(R.string.online_add_title)).performClick()
            compose.onNodeWithText(str(R.string.common_cancel)).performClick()
            assertFalse(runBlocking { importer.exists(provider, book) })
            compose.onNodeWithText(str(R.string.online_add_title)).performClick()
            screenshot("04-confirm")
            compose.onNodeWithText(str(R.string.online_add_read)).performClick()
        } else {
            compose.onNodeWithText(str(R.string.online_read)).performClick()
        }
        readFromDetail(compose)
        // Playback controls remain visible even when the reader hides the top toolbar.
        compose.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.player_play)), 180_000)
        screenshot("05-reader")
        runBlocking {
            val id = importer.bookId(provider, book)
            val stored = repository.loadBook(id)
            assertEquals("Pride and Prejudice", stored.title)
            assertEquals("en", stored.learningLanguage)
            assertTrue(stored.wordCount > 100_000)
            assertTrue(stored.passages.any { it.text.contains("Bennet") })
            // A second addition must return the existing file, retaining chapters and progress identity.
            assertEquals(id, importer.add(provider, book, CefrLevel.C2))
            assertEquals(stored.cefrLevel, repository.loadBook(id).cefrLevel)
        }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } // ficha → biblioteca online
        compose.waitUntilAtLeastOneExists(hasText(str(R.string.online_read)), 10_000)
        val wifi = shell("settings get global wifi_on").trim()
        val data = shell("settings get global mobile_data").trim()
        try {
            shell("svc wifi disable")
            shell("svc data disable")
            compose.onNodeWithText(str(R.string.online_read)).performClick()
            readFromDetail(compose)
            compose.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.player_play)), 30_000)
            screenshot("05-reader-offline")
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } // ficha → biblioteca online
        } finally {
            shell("svc wifi ${if (wifi == "1") "enable" else "disable"}")
            shell("svc data ${if (data == "1") "enable" else "disable"}")
        }
        compose.onNodeWithContentDescription(str(R.string.common_back)).performClick()
        compose.onNodeWithContentDescription(str(R.string.common_back)).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Pride and Prejudice"))
        compose.onNodeWithText("Pride and Prejudice").assertIsDisplayed()
        screenshot("06-my-books")
        compose.onNodeWithContentDescription(str(R.string.catalog_import_book)).performClick()
        compose.onNodeWithText(str(R.string.catalog_import_from_web)).assertDoesNotExist()
        val libraryMenu = compose.onNode(hasText(str(R.string.online_library)) and hasAnyAncestor(isPopup()))
        libraryMenu.assertIsDisplayed()
        assertTrue(libraryMenu.fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText(str(R.string.catalog_import_from_file)).fetchSemanticsNode().boundsInRoot.top)
        screenshot("06-add-menu")
        libraryMenu.performClick()
        compose.onNodeWithContentDescription(str(R.string.common_back)).performClick()
        compose.onNodeWithContentDescription(str(R.string.common_menu)).performClick()
        compose.onNode(hasText(str(R.string.section_learn, "English")) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(str(R.string.catalog_import_book)).performClick()
        compose.onNode(hasText(str(R.string.online_library)) and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntilAtLeastOneExists(hasText("English"), 45_000)
        compose.onNodeWithText("English").assertIsSelected()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(250) // Allow the emulator's render thread to present the frame captured below.
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), "online-library-test").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            File(target, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).use { String(it.readBytes()) }
}
