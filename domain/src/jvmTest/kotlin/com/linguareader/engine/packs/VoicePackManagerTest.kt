package com.linguareader.engine.packs

import com.linguareader.engine.supertonic.HttpResponse
import com.linguareader.engine.supertonic.HttpTransport
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.engine.supertonic.SupertonicModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Paquetes de voces por idioma: solo se baja el idioma pedido (el que se aprende o el que se prueba), el catálogo
 * remoto añade idiomas, el paquete único antiguo se borra y los clips se resuelven por idioma.
 */
class VoicePackManagerTest {

    @get:Rule val folder = TemporaryFolder()

    private val fs = FileSystem.SYSTEM
    private val wav = ByteArray(64) { it.toByte() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Zip de un idioma con una sola voz (F1) y sus dos clips. */
    private fun pack(lang: String): ByteArray {
        val manifest = """{"version":1,"language":"$lang","items":[
            {"path":"F1/sample.wav","sizeBytes":${wav.size},"sha256":"${sha(wav)}"},
            {"path":"F1/ready.wav","sizeBytes":${wav.size},"sha256":"${sha(wav)}"}]}""".toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in listOf("manifest.json" to manifest, "F1/sample.wav" to wav, "F1/ready.wav" to wav)) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun catalog(vararg langs: String, packs: Map<String, ByteArray>) =
        """{"version":2,"languages":{${langs.joinToString(",") { l -> "\"$l\":{\"asset\":\"voices-$l-v1.zip\",\"sizeBytes\":${packs.getValue(l).size},\"sha256\":\"${sha(packs.getValue(l))}\"}" }}}}"""

    /** Transporte en memoria: URL → bytes; anota qué se pidió. */
    private class FakeTransport(private val files: Map<String, ByteArray>) : HttpTransport {
        val requested = ArrayList<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requested += url
            val name = url.substringAfterLast('/')
            val data = files[name] ?: return response(404, ByteArray(0))
            return response(200, data)
        }
        private fun response(code: Int, data: ByteArray) = object : HttpResponse {
            var pos = 0
            override val code = code
            override fun header(name: String): String? = when (name.lowercase()) {
                "content-length" -> data.size.toString()
                "accept-ranges" -> "bytes"
                else -> null
            }
            override suspend fun read(buffer: ByteArray): Int {
                if (pos >= data.size) return -1
                val n = minOf(buffer.size, data.size - pos)
                data.copyInto(buffer, 0, pos, pos + n); pos += n
                return n
            }
            override fun close() {}
        }
    }

    private class Env(folder: TemporaryFolder, remote: Map<String, ByteArray>, bundledCatalog: String, val learning: MutableStateFlow<String?>) {
        val root = folder.newFolder("files").toOkioPath()
        val cache = folder.newFolder("cache").toOkioPath()
        val transport = FakeTransport(remote)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val supertonic = SupertonicModelManager(FileSystem.SYSTEM, root, scope, transport)
        val source = PackSource("https://files.example", "packs-v1", transport, FileSystem.SYSTEM)
        val manager = VoicePackManager(FileSystem.SYSTEM, root, cache, scope, source, supertonic, { bundledCatalog }, learning)
    }

    /** Espera en tiempo real (el gestor trabaja en hilos de E/S reales). */
    private suspend fun <T> await(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }
    private suspend fun settle() = withContext(Dispatchers.Default) { delay(300) }

    private fun Env.markModelInstalled() {
        // El gestor del modelo publica Installed cuando los ficheros están; aquí se fuerza el estado por reflexión del flujo.
        val field = SupertonicModelManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(supertonic) as MutableStateFlow<ModelState>).value = ModelState.Installed(1, root.toString())
    }

    @Test
    fun `downloads only the learning language once the model is installed`() = runTest {
        val packs = mapOf("en" to pack("en"), "de" to pack("de"))
        val remote = packs.mapKeys { "voices-${it.key}-v1.zip" } + ("voices.json" to catalog("en", "de", packs = packs).toByteArray())
        val env = Env(folder, remote, catalog("en", "de", packs = packs), MutableStateFlow("de-DE"))
        settle()
        assertTrue(env.manager.states.value.values.none { it is ModelState.Installed })

        env.markModelInstalled()
        settle()
        await { env.manager.states.first { it["de"] is ModelState.Installed } }
        assertTrue(env.manager.states.value["en"] is ModelState.NotInstalled)
        assertNotNull(env.manager.clip("de-DE", "F1", VoiceClip.SAMPLE))
        assertNull(env.manager.clip("en", "F1", VoiceClip.SAMPLE))
        assertFalse(env.transport.requested.any { it.endsWith("voices-en-v1.zip") })
        assertTrue(fs.exists(env.root / "voice-pack" / "de" / ".installed"))
    }

    @Test
    fun `ensure downloads a language on demand and ignores unknown ones`() = runTest {
        val packs = mapOf("fr" to pack("fr"))
        val remote = packs.mapKeys { "voices-${it.key}-v1.zip" }
        val env = Env(folder, remote, catalog("fr", packs = packs), MutableStateFlow(null))
        settle()
        assertFalse(env.manager.ensure("fr")) // sin modelo instalado no se baja nada
        env.markModelInstalled()
        settle()
        assertFalse(env.manager.ensure("xx"))
        assertTrue(env.manager.ensure("fr-CA"))
        settle()
        await { env.manager.states.first { it["fr"] is ModelState.Installed } }
        assertTrue(env.manager.isInstalled("fr"))
        env.manager.delete("fr")
        assertFalse(env.manager.isInstalled("fr"))
        assertNull(env.manager.clip("fr", "F1", VoiceClip.READY))
    }

    @Test
    fun `remote catalog adds languages and legacy single pack is removed`() = runTest {
        val packs = mapOf("en" to pack("en"), "it" to pack("it"))
        val remote = packs.mapKeys { "voices-${it.key}-v1.zip" } + ("voices.json" to catalog("en", "it", packs = packs).toByteArray())
        val filesDir = folder.newFolder("files2")
        File(filesDir, "voice-pack").mkdirs()
        File(filesDir, "voice-pack/.installed").writeText("1:oldsha")
        File(filesDir, "voice-pack/en/F1").mkdirs()
        File(filesDir, "voice-pack/en/F1/sample.wav").writeBytes(wav)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport(remote)
        val supertonic = SupertonicModelManager(fs, filesDir.toOkioPath(), scope, transport)
        val manager = VoicePackManager(fs, filesDir.toOkioPath(), folder.newFolder("cache2").toOkioPath(), scope,
            PackSource("https://files.example", "packs-v1", transport, fs), supertonic, { catalog("en", packs = packs) }, MutableStateFlow(null))
        assertFalse(File(filesDir, "voice-pack/.installed").exists())
        assertFalse(File(filesDir, "voice-pack/en").exists())
        settle()
        await { manager.catalog.first { "it" in it } }
        assertEquals(setOf("en", "it"), manager.catalog.value.keys)
        assertTrue(manager.available("it-IT"))
    }

    @Test
    fun `corrupt pack is rejected and reported`() = runTest {
        val good = pack("es")
        val bad = good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val env = Env(folder, mapOf("voices-es-v1.zip" to bad), catalog("es", packs = mapOf("es" to good)), MutableStateFlow(null))
        env.markModelInstalled()
        settle()
        env.manager.download("es")
        settle()
        val state = await { env.manager.states.first { it["es"] !is ModelState.NotInstalled && it["es"] !is ModelState.Downloading && it["es"] !is ModelState.Verifying }["es"] }
        assertTrue("estado: $state", state is ModelState.Failed)
        assertFalse(env.manager.isInstalled("es"))
    }
}
