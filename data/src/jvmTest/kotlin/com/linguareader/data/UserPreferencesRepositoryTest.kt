package com.linguareader.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.linguareader.core.model.ThemeMode
import com.linguareader.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** Preferencias sobre el DataStore multiplataforma (`createWithPath`), el mismo que usará iOS. */
class UserPreferencesRepositoryTest {

    private val dir = Files.createTempDirectory("prefs").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repo = UserPreferencesRepository(
        PreferenceDataStoreFactory.createWithPath(scope = scope) { dir.resolve("user_preferences.preferences_pb").toOkioPath() },
        deviceLanguage = { "fr" },
    )

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `defaults use the device language for translation`() = runTest {
        val p = repo.preferences.first()
        assertNull(p.readingLanguage)
        assertEquals("fr", p.translationLanguage)
    }

    @Test
    fun `updates round trip through the store`() = runTest {
        repo.update { it.copy(readingLanguage = "en", theme = ThemeMode.DARK, ttsPitch = 1.25f, voiceByLanguage = mapOf("en" to "v1", "es" to "v2"), systemTtsEngine = "org.rhvoice") }
        val p = repo.preferences.first()
        assertEquals("en", p.readingLanguage)
        assertEquals(ThemeMode.DARK, p.theme)
        assertEquals(1.25f, p.ttsPitch)
        assertEquals(mapOf("en" to "v1", "es" to "v2"), p.voiceByLanguage)
        assertEquals("org.rhvoice", p.systemTtsEngine)
        repo.update { it.copy(readingLanguage = null, systemTtsEngine = null) }
        assertNull(repo.preferences.first().readingLanguage)
        assertNull(repo.preferences.first().systemTtsEngine)
    }
}
