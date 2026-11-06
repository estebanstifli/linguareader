package com.linguareader.ui.onboarding

import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStepsTest {

    private val fresh = UserPreferences()

    /** Recorre el asistente aplicando lo que hace cada pantalla al pulsar su botón principal. */
    private fun walk(start: UserPreferences = fresh): List<OnboardingStep> {
        var p = start
        val seen = mutableListOf<OnboardingStep>()
        while (true) {
            val step = nextOnboardingStep(p) ?: return seen
            seen += step
            p = when (step) {
                OnboardingStep.LANGUAGES -> p.withLanguagesChosen(listOf("en", "fr"), "es", wifiOnly = true)
                OnboardingStep.LEVEL -> p.copy(learnerLevel = CefrLevel.A2, learnerLevelChosen = true)
                OnboardingStep.SUMMARY -> p.copy(onboardingSummaryShown = true)
            }
            check(seen.size < 10) { "El asistente no termina: $seen" }
        }
    }

    @Test
    fun `a fresh install asks languages, level and shows the summary`() {
        assertEquals(listOf(OnboardingStep.LANGUAGES, OnboardingStep.LEVEL, OnboardingStep.SUMMARY), walk())
    }

    @Test
    fun `choosing languages sets the list, the primary one and the learning purpose`() {
        val p = fresh.withLanguagesChosen(listOf("de", "it"), "es", wifiOnly = false)
        assertEquals(listOf("de", "it"), p.learningLanguages)
        assertEquals("de", p.readingLanguage)
        assertEquals("es", p.translationLanguage)
        assertEquals(AppPurpose.LEARN, p.purpose)
        assertTrue(p.ttsMode && p.ttsModeChosen && p.learnLanguageChosen && p.translationSetupDone)
    }

    @Test
    fun `closing the app halfway resumes at the pending step`() {
        val halfway = fresh.withLanguagesChosen(listOf("fr"), "es", wifiOnly = true)
        assertEquals(OnboardingStep.LEVEL, nextOnboardingStep(halfway))
    }

    @Test
    fun `previous installs that only read see nothing`() {
        val reader = fresh.copy(
            purpose = AppPurpose.READ, ttsModeChosen = true, learnLanguageChosen = true, translationSetupDone = true,
            learnerLevelChosen = true,
        )
        assertNull(nextOnboardingStep(reader))
    }

    @Test
    fun `previous learning installs keep their single language as the list`() {
        // El repositorio convierte reading_language en la lista; aquí se simula ese estado.
        val legacy = fresh.copy(
            readingLanguage = "en", learningLanguages = listOf("en"), purpose = AppPurpose.LEARN, ttsModeChosen = true,
            learnLanguageChosen = true, translationSetupDone = true, learnerLevelChosen = true, onboardingSummaryShown = true,
        )
        assertNull(nextOnboardingStep(legacy))
        assertEquals(listOf("en", "pt"), legacy.withLearningLanguages(legacy.learningLanguages + "pt").learningLanguages)
        assertEquals("pt", legacy.withLearningLanguages(listOf("pt")).readingLanguage)
    }
}
