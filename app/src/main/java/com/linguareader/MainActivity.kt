package com.linguareader

import org.koin.android.ext.android.inject
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import com.linguareader.core.model.ScreenOrientation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.data.content.PendingImports
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.ui.navigation.AppNavHost
import com.linguareader.ui.onboarding.OnboardingGate
import com.linguareader.ui.theme.LinguaReaderTheme

class MainActivity : ComponentActivity() {

    private val preferences: UserPreferencesRepository by inject()
    private val pendingImports: PendingImports by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Solo la primera vez: tras una rotación o recreación el intent ya se procesó.
        if (savedInstanceState == null) pendingImports.offer(intent)
        enableEdgeToEdge()
        setContent {
            // Hasta tener las preferencias guardadas (tema, orientación, primera apertura) no se compone nada: con
            // valores por defecto se componía la app entera y, al llegar las reales, se volvía a componer.
            val loaded by preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
            val prefs = loaded ?: return@setContent
            LaunchedEffect(prefs.orientation) {
                requestedOrientation = when (prefs.orientation) {
                    ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
            }
            // Tras el primer frame con contenido, arranca lo pesado (ver LinguaReaderApp.startDeferredServices).
            LaunchedEffect(Unit) {
                withFrameNanos { }
                (application as LinguaReaderApp).startDeferredServices() // en los tests, TestApp no arranca nada
            }
            LinguaReaderTheme(theme = prefs.theme) {
                OnboardingGate(content = { AppNavHost(pendingImports) })
            }
        }
    }

    /** "Abrir con…" o "Compartir" con la app ya abierta (launchMode singleTask). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingImports.offer(intent)
    }
}
