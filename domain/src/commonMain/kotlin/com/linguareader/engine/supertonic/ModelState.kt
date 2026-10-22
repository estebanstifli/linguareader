package com.linguareader.engine.supertonic

import com.linguareader.engine.packs.PackError

/** Estado de un modelo o paquete descargable en el dispositivo. */
sealed interface ModelState {
    data class NotInstalled(val totalBytes: Long, val partialBytes: Long) : ModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val currentFile: String) : ModelState
    data class Verifying(val fraction: Float) : ModelState
    data class Installed(val bytesOnDisk: Long, val path: String) : ModelState
    data class Failed(val error: PackError) : ModelState
}
