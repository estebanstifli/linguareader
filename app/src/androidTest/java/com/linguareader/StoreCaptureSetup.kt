package com.linguareader

import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.ThemeMode
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.db.ProgressDao
import com.linguareader.engine.translation.TranslationModelManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Opt-in preparation of an existing emulator library. Never runs in the normal test suite. */
class StoreCaptureSetup : KoinComponent {
    private val preferences: UserPreferencesRepository by inject()
    private val models: TranslationModelManager by inject()
    private val progress: ProgressDao by inject()

    @Test fun prepare() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("storeCapture") == "true")
        check(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext !is TestApp)
        val target = args.getString("target") ?: "es"
        preferences.update { it.copy(
            translationLanguage = target,
            readingLanguage = if (target == "en") "fr" else "en",
            displayMode = if (args.getString("mode") == "tap") DisplayMode.TRANSLATION_ON_TAP else DisplayMode.TRANSLATION_BELOW,
            theme = if (args.getString("theme") == "dark") ThemeMode.DARK else ThemeMode.SEPIA,
            fingerPointerEnabled = true, showIllustrations = false,
            translationWifiOnly = false, fontScale = 1f, lineHeight = 1.35f,
            keepScreenOn = true,
            learnerLevel = CefrLevel.A1, libraryGrid = true,
        ) }
        if (args.getString("download") == "all") {
            for (language in listOf("en", "es", "fr", "de", "it", "pt")) {
                assertTrue("Model $language: ${models.downloads.value}",
                    withTimeout(240_000) { models.awaitDownload(language) })
            }
        }
        if (args.getString("reset") == "true") {
            progress.observeAll().first().forEach { progress.upsert(it.copy(passageIndex = 0, charOffset = 0)) }
        }
    }
}
