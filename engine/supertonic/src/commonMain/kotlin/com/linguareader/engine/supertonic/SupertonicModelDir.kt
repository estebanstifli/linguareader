package com.linguareader.engine.supertonic

import com.linguareader.core.platform.Sha256
import com.linguareader.core.platform.toHexLower
import okio.FileSystem
import okio.Path

/** Estado de una carpeta de modelo en disco. */
class SupertonicModelDir(val root: Path, val fileSystem: FileSystem = FileSystem.SYSTEM) {

    fun file(path: String): Path = root / path

    fun readText(path: String): String = fileSystem.read(file(path)) { readUtf8() }

    private fun sizeOf(path: Path): Long = fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size ?: -1L

    /** Todos los ficheros presentes con el tamaño correcto (comprobación rápida, sin hash). */
    fun isComplete(): Boolean = SupertonicModel.FILES.all { sizeOf(file(it.path)) == it.sizeBytes }

    fun isVerified(): Boolean =
        isComplete() && file(SupertonicModel.MARKER).let { m ->
            sizeOf(m) >= 0 && fileSystem.read(m) { readUtf8() }.trim() == SupertonicModel.REVISION
        }

    fun bytesOnDisk(): Long =
        if (!fileSystem.exists(root)) 0L
        else fileSystem.listRecursively(root).sumOf { fileSystem.metadataOrNull(it)?.takeIf { m -> m.isRegularFile }?.size ?: 0L }

    /** Verifica sha256 de todo (≈400 MB: unos segundos). Escribe el marcador si todo es correcto. */
    fun verify(onProgress: (Long) -> Unit = {}): List<String> {
        val bad = mutableListOf<String>()
        var done = 0L
        for (mf in SupertonicModel.FILES) {
            val f = file(mf.path)
            if (sizeOf(f) != mf.sizeBytes || sha256(f, fileSystem) { n -> onProgress(done + n) } != mf.sha256) bad += mf.path
            done += mf.sizeBytes
        }
        if (bad.isEmpty()) fileSystem.write(file(SupertonicModel.MARKER)) { writeUtf8(SupertonicModel.REVISION) }
        return bad
    }

    fun delete(): Boolean = runCatching { fileSystem.deleteRecursively(root, mustExist = false) }.isSuccess

    companion object {
        fun sha256(file: Path, fileSystem: FileSystem = FileSystem.SYSTEM, onBytes: (Long) -> Unit = {}): String {
            if (fileSystem.metadataOrNull(file)?.isRegularFile != true) return ""
            val digest = Sha256()
            val buf = ByteArray(1 shl 16)
            var total = 0L
            fileSystem.read(file) {
                while (true) {
                    val n = read(buf, 0, buf.size)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                    total += n
                    onBytes(total)
                }
            }
            return digest.digest().toHexLower()
        }
    }
}
