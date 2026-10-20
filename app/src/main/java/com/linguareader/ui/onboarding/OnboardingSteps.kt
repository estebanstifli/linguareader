package com.linguareader.ui.onboarding

import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.UserPreferences

/** Pantallas de la primera apertura, en orden. */
enum class OnboardingStep {
    /** ¿Leer mis libros, aprender un idioma o las dos cosas? */
    PURPOSE,
    /** ¿Qué idioma quieres aprender? */
    LEARN_LANGUAGE,
    /** ¿Cuál es tu idioma? (al que se traduce) */
    NATIVE_LANGUAGE,
    /** Nivel aproximado (A1–B2 o «No lo sé»). Al terminarlo se lanzan las descargas. */
    LEVEL,
    /** Buscar los documentos que ya hay en el móvil (pide el permiso). */
    SCAN,
    /** «Esto es lo que hemos preparado»: estado de descargas y búsqueda, y a leer. */
    SUMMARY,
}

/**
 * Paso pendiente de la primera apertura, o null si ya terminó. Cada paso queda marcado al completarlo (o al pulsar
 * «Ahora no»), así que cerrar la app a medias retoma donde se quedó y lo ya lanzado sigue en segundo plano.
 *
 * Instalaciones anteriores a la pregunta del propósito ([UserPreferences.purpose] null pero ya configuradas) solo ven
 * lo que les falte de lo de siempre, más la oferta de buscar documentos, sin resumen.
 */
fun nextOnboardingStep(p: UserPreferences, scanOffered: Boolean): OnboardingStep? {
    if (!p.ttsModeChosen) return OnboardingStep.PURPOSE
    val purpose = p.purpose
    if (purpose == null) {
        return when {
            p.ttsMode && !p.learnLanguageChosen && !p.translationSetupDone -> OnboardingStep.LEARN_LANGUAGE
            !p.translationSetupDone -> OnboardingStep.NATIVE_LANGUAGE
            !scanOffered -> OnboardingStep.SCAN
            else -> null
        }
    }
    val learns = purpose != AppPurpose.READ
    val reads = purpose != AppPurpose.LEARN
    return when {
        learns && !p.learnLanguageChosen -> OnboardingStep.LEARN_LANGUAGE
        learns && !p.translationSetupDone -> OnboardingStep.NATIVE_LANGUAGE
        learns && !p.learnerLevelChosen -> OnboardingStep.LEVEL
        reads && !scanOffered -> OnboardingStep.SCAN
        !p.onboardingSummaryShown -> OnboardingStep.SUMMARY
        else -> null
    }
}

/** Lo que cambia al elegir propósito: solo leer no usa la voz ni prepara nada de idiomas. */
fun UserPreferences.withPurpose(purpose: AppPurpose): UserPreferences = when (purpose) {
    AppPurpose.READ -> copy(
        purpose = purpose,
        ttsMode = false,
        ttsModeChosen = true,
        // Sin pasos de idioma: el traductor se ofrece la primera vez que se toca una palabra.
        learnLanguageChosen = true,
        translationSetupDone = true,
        learnerLevelChosen = true,
    )
    AppPurpose.LEARN, AppPurpose.BOTH -> copy(purpose = purpose, ttsMode = true, ttsModeChosen = true)
}
