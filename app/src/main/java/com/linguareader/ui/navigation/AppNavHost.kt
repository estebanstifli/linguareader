package com.linguareader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.linguareader.data.content.PendingImports
import com.linguareader.ui.catalog.CatalogScreen
import com.linguareader.ui.reader.ReaderScreen
import com.linguareader.ui.settings.SettingsScreen
import com.linguareader.ui.vocabulary.VocabularyScreen
import kotlinx.serialization.Serializable

@Serializable object CatalogRoute
@Serializable data class ReaderRoute(val bookId: String)
@Serializable object VocabularyRoute
@Serializable object SettingsRoute

@Composable
fun AppNavHost(pendingImports: PendingImports) {
    val nav = rememberNavController()
    // Un libro compartido mientras se lee: volver a la biblioteca, donde se muestra la importación.
    LaunchedEffect(pendingImports) {
        pendingImports.arrivals.collect { nav.popBackStack(CatalogRoute, inclusive = false) }
    }
    NavHost(navController = nav, startDestination = CatalogRoute) {
        composable<CatalogRoute> {
            CatalogScreen(
                onOpenBook = { nav.navigate(ReaderRoute(it)) },
                onOpenVocabulary = { nav.navigate(VocabularyRoute) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<ReaderRoute> { ReaderScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(SettingsRoute) }) }
        composable<VocabularyRoute> { VocabularyScreen(onBack = { nav.popBackStack() }) }
        composable<SettingsRoute> { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
