package com.linguareader.engine.supertonic

import com.linguareader.core.platform.Sha256
import com.linguareader.core.platform.toHexLower
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random

/** Estado de una carpeta de modelo en disco. */
class SupertonicModelDir(val root: Path, val fileSystem: FileSystem = FileSystem.SYSTEM) {

    fun file(path: String): Path = root / path

    fun readText(path: String): String = fileSystem.read(file(path)) { readUtf8() }

    /**
     * Publishes a small, hash-pinned graph beside the original verified weights.
     * Its external TensorProto ranges point into vector_estimator.onnx: no second
     * copy of the 256 MB weights, network request, Python or ONNX converter is needed.
     * Call from the synthesis/IO dispatcher, after verification of the source model.
     * Existing installations migrate lazily; a damaged derived graph is rebuilt.
     */
    fun ensureTimingModel(): Path {
        check(isVerified()) { "The original Supertonic files must be verified before preparing word timings" }
        return publishTimingGraph()
    }

    /** Graph-only publication separated for concurrency tests; source verification is the caller's prerequisite. */
    internal fun publishTimingGraph(): Path {
        val target = file(SupertonicTimingGraph.FILE)
        if (validTimingGraph(target)) return target
        val bytes = SupertonicTimingGraph.bytes()
        check(bytes.size.toLong() == SupertonicTimingGraph.SIZE)
        val digest = Sha256().apply { update(bytes, 0, bytes.size) }.digest().toHexLower()
        check(digest == SupertonicTimingGraph.SHA256) { "Embedded Supertonic timing graph is corrupt" }
        // Download/verification (IO) and first synthesis (TTS) can race across two
        // ModelDir instances. Each invocation owns its temporary file exclusively.
        val suffix = Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
        val temporary = target.parent!! / (target.name + ".$suffix.part")
        var ownsTemporary = false
        try {
            fileSystem.write(temporary, mustCreate = true) {
                ownsTemporary = true
                write(bytes)
            }
            try {
                fileSystem.atomicMove(temporary, target)
            } catch (error: IOException) {
                // Some filesystems refuse replacing a target concurrently published
                // by another caller. Accept only the exact pinned, complete graph.
                if (!runCatching { validTimingGraph(target) }.getOrDefault(false)) throw error
            }
        } finally {
            if (ownsTemporary) runCatching { fileSystem.delete(temporary, mustExist = false) }
        }
        return target
    }

    private fun validTimingGraph(path: Path): Boolean =
        sizeOf(path) == SupertonicTimingGraph.SIZE && sha256(path, fileSystem) == SupertonicTimingGraph.SHA256

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
