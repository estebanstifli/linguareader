package com.linguareader

import androidx.lifecycle.ViewModelStore
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.ui.settings.TtsSettingsViewModel
import com.linguareader.ui.settings.VoiceTestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Real settings controller, voice packs and Media3 on Android. */
class SupertonicSettingsPreviewTest : KoinComponent {
    private val preferences: UserPreferencesRepository by inject()

    @Test fun voiceAndLanguageChangesPreviewTheirCurrentCombination() = runBlocking {
        val original = preferences.preferences.first()
        val store = ViewModelStore()
        try {
            preferences.update { it.copy(ttsEngineId = SupertonicModel.ENGINE_ID,
                readingLanguage = "es", supertonicVoice = "F1") }
            val vm = withContext(Dispatchers.Main) {
                getKoin().get<TtsSettingsViewModel>().also { store.put("preview", it) }
            }
            assertTrue("Install the real Supertonic model first", vm.modelState.value is ModelState.Installed)
            withTimeout(10_000) { vm.previewLanguage.first { it == "es" } }

            suspend fun awaitPlayed(language: String, voice: String) {
                withTimeout(90_000) {
                    vm.test.first { state ->
                        assertFalse("Preview failed: $state", state is VoiceTestState.Failed)
                        state == VoiceTestState.Played(language, voice)
                    }
                }
                assertEquals(language, vm.previewLanguage.value)
                assertEquals(voice, preferences.preferences.first().supertonicVoice)
            }

            // No language tap is required: the selected reading language is already visible.
            withContext(Dispatchers.Main) { vm.setVoice("F2") }.join()
            awaitPlayed("es", "F2")
            withContext(Dispatchers.Main) { vm.runTest("fr") }
            awaitPlayed("fr", "F2")
            withContext(Dispatchers.Main) {
                vm.setVoice("F1")
                vm.setVoice("F3")
            }
            awaitPlayed("fr", "F3")
            delay(300)
            assertEquals(VoiceTestState.Played("fr", "F3"), vm.test.value)
            // A repeated tap on the selected voice also starts its sample.
            withContext(Dispatchers.Main) { vm.setVoice("F3") }.join()
            awaitPlayed("fr", "F3")
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            preferences.update { original }
        }
    }
}
