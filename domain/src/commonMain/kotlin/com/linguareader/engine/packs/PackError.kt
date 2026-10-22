package com.linguareader.engine.packs

/**
 * Motivo tipado de un fallo de descarga o instalación de un paquete (modelo, voces, biblioteca). La UI de cada
 * plataforma lo traduce a texto (Android: `ui/common/PackErrors.kt`); el código compartido no conoce recursos.
 */
sealed interface PackError {
    data class FolderInaccessible(val detail: String) : PackError
    data object FolderCreateFailed : PackError
    data class NoSpace(val neededMb: Long, val freeMb: Long) : PackError
    /** Descarga interrumpida; lo bajado se conserva y el siguiente intento reanuda. */
    data class DownloadInterruptedResumable(val detail: String) : PackError
    data class DownloadInterrupted(val detail: String) : PackError
    data class DownloadError(val detail: String) : PackError
    data class FilesCorrupt(val files: String) : PackError
    data class VerifyError(val detail: String) : PackError
    data object RepoNotConfigured : PackError
    data object VoicePackCatalogUnavailable : PackError
    data class LibraryFailed(val detail: String) : PackError
}

/** Estado de la biblioteca de un idioma. */
sealed interface LibraryPackState {
    data object NotInstalled : LibraryPackState
    data class Downloading(val bytes: Long, val total: Long) : LibraryPackState
    data class Installing(val done: Int, val total: Int) : LibraryPackState
    data class Installed(val version: Int, val books: Int, val updateAvailable: Boolean) : LibraryPackState
    data class Failed(val error: PackError) : LibraryPackState
}
