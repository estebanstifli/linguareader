package com.linguareader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
fun AppNavHost() {
    val nav = rememberNavController()
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
