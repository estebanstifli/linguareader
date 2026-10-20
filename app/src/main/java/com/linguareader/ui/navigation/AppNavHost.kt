package com.linguareader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.linguareader.data.content.PendingImports
import com.linguareader.ui.catalog.BookEditScreen
import com.linguareader.ui.catalog.CatalogScreen
import com.linguareader.ui.catalog.DeviceDocumentsScreen
import com.linguareader.ui.reader.ReaderScreen
import com.linguareader.ui.settings.SettingsPage
import com.linguareader.ui.settings.SettingsScreen
import com.linguareader.ui.vocabulary.VocabularyScreen
import kotlinx.serialization.Serializable

@Serializable object CatalogRoute
@Serializable data class ReaderRoute(val bookId: String)
@Serializable object VocabularyRoute
/** [page]: nombre de una [SettingsPage] para abrir directamente esa subpantalla; null = índice. */
@Serializable data class SettingsRoute(val page: String? = null)
@Serializable object DeviceDocumentsRoute
@Serializable data class BookEditRoute(val bookId: String)

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
                onOpenSettings = { nav.navigate(SettingsRoute()) },
                onEditBook = { nav.navigate(BookEditRoute(it)) },
                onOpenDeviceDocuments = { nav.navigate(DeviceDocumentsRoute) },
            )
        }
        composable<ReaderRoute> {
            ReaderScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(SettingsRoute(SettingsPage.VOICE.name)) })
        }
        composable<VocabularyRoute> { VocabularyScreen(onBack = { nav.popBackStack() }) }
        composable<SettingsRoute> { entry ->
            val page = entry.toRoute<SettingsRoute>().page?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenDocuments = {
                // Si se llegó aquí desde los documentos, se vuelve a ellos en vez de apilar otra copia.
                if (!nav.popBackStack(DeviceDocumentsRoute, inclusive = false)) nav.navigate(DeviceDocumentsRoute)
            }, initialPage = page)
        }
        composable<DeviceDocumentsRoute> {
            DeviceDocumentsScreen(onBack = { nav.popBackStack() }, onOpenScanSettings = { nav.navigate(SettingsRoute(SettingsPage.FILES.name)) })
        }
        composable<BookEditRoute> { BookEditScreen(onBack = { nav.popBackStack() }) }
    }
}
