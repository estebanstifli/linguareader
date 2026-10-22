package com.linguareader.data.scan

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.scanStore: DataStore<Preferences> by preferencesDataStore(name = "scan_settings")

/** Dónde y qué buscar al examinar el dispositivo (Ajustes › Examinar archivos). */
data class ScanSettings(
    /** Buscar en segundo plano al abrir la app (si hay permiso y la última búsqueda es antigua). */
    val autoStart: Boolean = true,
    val wholeDevice: Boolean = true,
    val downloads: Boolean = true,
    /** Carpetas elegidas con el selector del sistema (URIs de árbol con permiso persistente). */
    val folders: Set<String> = emptySet(),
    /** Rutas de carpetas que no se examinan (ni sus subcarpetas). */
    val exclusions: Set<String> = emptySet(),
    /** Formatos que se buscan (extensiones en minúscula). Vacío = todos los que la app sabe abrir. */
    val formats: Set<String> = emptySet(),
    /** Los .txt abundan (registros, notas de otras apps): se pueden dejar fuera. */
    val includeTxt: Boolean = true,
    val includeHidden: Boolean = false,
    /** Ya se ofreció la búsqueda en la primera apertura. */
    val offered: Boolean = false,
    /** Fin de la última búsqueda completa (epoch ms); 0 = nunca. */
    val lastScanAt: Long = 0,
    /** El usuario cerró el aviso «Hemos encontrado N documentos» de la biblioteca. */
    val foundBannerDismissed: Boolean = false,
)

class ScanSettingsRepository(private val context: Context) {

    private object Keys {
        val autoStart = booleanPreferencesKey("auto_start")
        val wholeDevice = booleanPreferencesKey("whole_device")
        val downloads = booleanPreferencesKey("downloads")
        val folders = stringSetPreferencesKey("folders")
        val exclusions = stringSetPreferencesKey("exclusions")
        val formats = stringSetPreferencesKey("formats")
        val includeTxt = booleanPreferencesKey("include_txt")
        val includeHidden = booleanPreferencesKey("include_hidden")
        val offered = booleanPreferencesKey("offered")
        val lastScanAt = longPreferencesKey("last_scan_at")
        val foundBannerDismissed = booleanPreferencesKey("found_banner_dismissed")
    }

    val settings: Flow<ScanSettings> = context.scanStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::fromPreferences)

    suspend fun update(transform: (ScanSettings) -> ScanSettings) {
        context.scanStore.edit { stored ->
            val next = transform(fromPreferences(stored))
            stored[Keys.autoStart] = next.autoStart
            stored[Keys.wholeDevice] = next.wholeDevice
            stored[Keys.downloads] = next.downloads
            stored[Keys.folders] = next.folders
            stored[Keys.exclusions] = next.exclusions
            stored[Keys.formats] = next.formats
            stored[Keys.includeTxt] = next.includeTxt
            stored[Keys.includeHidden] = next.includeHidden
            stored[Keys.offered] = next.offered
            stored[Keys.lastScanAt] = next.lastScanAt
            stored[Keys.foundBannerDismissed] = next.foundBannerDismissed
        }
    }

    private fun fromPreferences(p: Preferences): ScanSettings {
        val d = ScanSettings()
        return ScanSettings(
            autoStart = p[Keys.autoStart] ?: d.autoStart,
            wholeDevice = p[Keys.wholeDevice] ?: d.wholeDevice,
            downloads = p[Keys.downloads] ?: d.downloads,
            folders = p[Keys.folders] ?: d.folders,
            exclusions = p[Keys.exclusions] ?: d.exclusions,
            formats = p[Keys.formats] ?: d.formats,
            includeTxt = p[Keys.includeTxt] ?: d.includeTxt,
            includeHidden = p[Keys.includeHidden] ?: d.includeHidden,
            offered = p[Keys.offered] ?: d.offered,
            lastScanAt = p[Keys.lastScanAt] ?: d.lastScanAt,
            foundBannerDismissed = p[Keys.foundBannerDismissed] ?: d.foundBannerDismissed,
        )
    }
}
