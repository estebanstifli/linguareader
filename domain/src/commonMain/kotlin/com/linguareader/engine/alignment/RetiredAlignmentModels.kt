package com.linguareader.engine.alignment

import okio.FileSystem
import okio.Path

/** Upgrade cleanup for the obsolete, app-owned `files/models/alignment` downloads. */
class RetiredAlignmentModels(
    private val fileSystem: FileSystem,
    private val filesDir: Path,
    private val log: (String) -> Unit = {},
) {
    /** Idempotent and best effort. Refuses redirected roots and never traverses symbolic links. */
    fun remove() {
        runCatching {
            val base = fileSystem.canonicalize(filesDir)
            val models = base / "models"
            val root = models / "alignment"
            if (fileSystem.metadataOrNull(models)?.symlinkTarget != null ||
                fileSystem.metadataOrNull(root)?.symlinkTarget != null) {
                log("Refusing redirected retired alignment model directory")
                return
            }
            if (fileSystem.metadataOrNull(root) == null) return
            removeEntry(root)
            log("Retired external alignment model downloads removed")
        }.onFailure { log("Could not remove retired alignment models: ${it.message}") }
    }

    private fun removeEntry(path: Path) {
        val metadata = fileSystem.metadataOrNull(path) ?: return
        // A link itself may be removed, but its destination is never visited.
        if (metadata.isDirectory && metadata.symlinkTarget == null) {
            fileSystem.list(path).forEach(::removeEntry)
        }
        fileSystem.delete(path, mustExist = false)
    }
}
