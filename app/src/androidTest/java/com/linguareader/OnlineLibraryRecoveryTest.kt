package com.linguareader

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.linguareader.data.catalog.*
import com.linguareader.ui.library.OnlineLibraryScreen
import com.linguareader.ui.library.OnlineLibraryViewModel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException

/** Deterministic provider failure, retry and empty result; does not depend on server availability. */
@OptIn(ExperimentalTestApi::class)
class OnlineLibraryRecoveryTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val importer: OnlineBookImporter by inject()
    private val store = ViewModelStore()
    @After fun clear() { store.clear() }

    @Test fun failedMetadataRequestShowsRetryThenEmptySearchWithoutCrashing() {
        var offline = true
        val provider = object : OnlineCatalog {
            override val id = "test"
            override val name = "Test library"
            override suspend fun books(filter: OnlineCatalogFilter, page: Int) = CatalogPage(emptyList(), CatalogPagination(1, 0, 0))
            override suspend fun fetch(url: String, limit: Long): ByteArray = error("Not used")
            override suspend fun book(id: Int): OnlineBook = error("Not used")
            override suspend fun categories(): List<CatalogCategory> {
                if (offline) throw IOException("Offline")
                return emptyList()
            }
            override suspend fun languages() = emptyList<CatalogLanguage>()
        }
        compose.activityRule.scenario.onActivity { activity ->
            val vm = OnlineLibraryViewModel(provider, importer, SavedStateHandle())
            store.put("recovery", vm)
            activity.setContent { MaterialTheme { OnlineLibraryScreen({}, {}, vm) } }
        }
        compose.waitUntilAtLeastOneExists(hasText(str(R.string.online_connection_error)), 10_000)
        compose.onNodeWithText(str(R.string.online_connection_error)).assertExists()
        offline = false
        compose.onNodeWithText(str(R.string.common_retry)).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText(str(R.string.online_empty)), 10_000)
        compose.onNodeWithText(str(R.string.online_empty)).assertExists()
    }
}
