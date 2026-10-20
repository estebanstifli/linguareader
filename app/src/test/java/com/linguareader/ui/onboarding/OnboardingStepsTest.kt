package com.linguareader.ui.onboarding

import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingStepsTest {

    private val fresh = UserPreferences()

    /** Recorre el asistente aplicando lo que hace cada pantalla al pulsar su botón principal. */
    private fun walk(purpose: AppPurpose): List<OnboardingStep> {
        var p = fresh
        var scanOffered = false
        val seen = mutableListOf<OnboardingStep>()
        while (true) {
            val step = nextOnboardingStep(p, scanOffered) ?: return seen
            seen += step
            p = when (step) {
                OnboardingStep.PURPOSE -> p.withPurpose(purpose)
                OnboardingStep.LEARN_LANGUAGE -> p.copy(readingLanguage = "en", learnLanguageChosen = true)
                OnboardingStep.NATIVE_LANGUAGE -> p.copy(translationLanguage = "es", translationSetupDone = true)
                OnboardingStep.LEVEL -> p.copy(learnerLevel = CefrLevel.A2, learnerLevelChosen = true)
                OnboardingStep.SCAN -> p.also { scanOffered = true }
                OnboardingStep.SUMMARY -> p.copy(onboardingSummaryShown = true)
            }
            check(seen.size < 10) { "El asistente no termina: $seen" }
        }
    }

    @Test
    fun `reading only asks for the documents search`() {
        assertEquals(listOf(OnboardingStep.PURPOSE, OnboardingStep.SCAN, OnboardingStep.SUMMARY), walk(AppPurpose.READ))
        assertFalse(fresh.withPurpose(AppPurpose.READ).ttsMode)
    }

    @Test
    fun `learning asks language, native language and level, without the search`() {
        assertEquals(
            listOf(OnboardingStep.PURPOSE, OnboardingStep.LEARN_LANGUAGE, OnboardingStep.NATIVE_LANGUAGE, OnboardingStep.LEVEL, OnboardingStep.SUMMARY),
            walk(AppPurpose.LEARN),
        )
    }

    @Test
    fun `both does the learning steps and then the search`() {
        assertEquals(
            listOf(
                OnboardingStep.PURPOSE, OnboardingStep.LEARN_LANGUAGE, OnboardingStep.NATIVE_LANGUAGE,
                OnboardingStep.LEVEL, OnboardingStep.SCAN, OnboardingStep.SUMMARY,
            ),
            walk(AppPurpose.BOTH),
        )
    }

    @Test
    fun `closing the app halfway resumes at the pending step`() {
        val halfway = fresh.withPurpose(AppPurpose.LEARN).copy(readingLanguage = "fr", learnLanguageChosen = true)
        assertEquals(OnboardingStep.NATIVE_LANGUAGE, nextOnboardingStep(halfway, scanOffered = false))
    }

    @Test
    fun `existing installs only see what they miss and no summary`() {
        val configured = fresh.copy(ttsModeChosen = true, translationSetupDone = true, learnLanguageChosen = true)
        assertEquals(OnboardingStep.SCAN, nextOnboardingStep(configured, scanOffered = false))
        assertNull(nextOnboardingStep(configured, scanOffered = true))
    }
}
