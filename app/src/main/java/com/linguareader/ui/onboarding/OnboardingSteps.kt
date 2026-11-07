package com.linguareader.ui.onboarding

import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.UserPreferences

/** Pantallas de la primera apertura, en orden. La app es para aprender idiomas: no se pregunta el propósito. */
enum class OnboardingStep {
    /** ¿Qué idiomas quieres aprender (uno o varios) y cuál es tu idioma (al que se traduce)? */
    LANGUAGES,
    /** Nivel aproximado (A1–B2 o «No lo sé»). Al terminarlo se lanzan las descargas. */
    LEVEL,
    /** «Esto es lo que hemos preparado»: estado de las descargas, y a leer. */
    SUMMARY,
}

/**
 * Paso pendiente de la primera apertura, o null si ya terminó. Cada paso queda marcado al completarlo, así que cerrar
 * la app a medias retoma donde se quedó y lo ya lanzado sigue en segundo plano.
 *
 * Instalaciones anteriores que eligieron «solo leer mis documentos» ([AppPurpose.READ]) ya tienen todo marcado y no
 * ven nada; pueden añadir idiomas desde el menú.
 */
fun nextOnboardingStep(p: UserPreferences): OnboardingStep? = when {
    !p.learnLanguageChosen || !p.translationSetupDone -> OnboardingStep.LANGUAGES
    p.purpose == AppPurpose.READ -> null
    !p.learnerLevelChosen -> OnboardingStep.LEVEL
    !p.onboardingSummaryShown -> OnboardingStep.SUMMARY
    else -> null
}
