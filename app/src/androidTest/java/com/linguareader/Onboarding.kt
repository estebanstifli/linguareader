package com.linguareader

import androidx.compose.ui.test.performSemanticsAction
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.scan.ScanSettingsRepository

/**
 * Deja la app como si ya hubiera pasado la primera apertura (sin pantallas encima de la biblioteca) con las
 * preferencias [prefs] de la prueba.
 */
suspend fun skipOnboarding(preferences: UserPreferencesRepository, scan: ScanSettingsRepository, prefs: UserPreferences) {
    preferences.update {
        prefs.copy(ttsModeChosen = true, learnLanguageChosen = true, translationSetupDone = true, learnerLevelChosen = true)
    }
    scan.update { it.copy(offered = true, autoStart = false) }
}

/**
 * Abre un libro de la biblioteca con la acción «abrir» de su ficha (la que usa TalkBack). Un clic en el centro de la
 * ficha caería en la fila de botones (para leer, leído, favorito…), no en el libro.
 */
fun openFromCatalog(compose: androidx.compose.ui.test.SemanticsNodeInteractionsProvider, title: String) {
    compose.onNode(
        androidx.compose.ui.test.hasText(title) and androidx.compose.ui.test.hasClickAction(),
    ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
}

/**
 * Concede el permiso de notificaciones (Android 13+) antes de reproducir: si no, el diálogo del sistema que pide la
 * app al empezar a leer en voz alta tapa la actividad y la prueba se queda sin pantalla.
 */
fun grantNotifications() {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
}
