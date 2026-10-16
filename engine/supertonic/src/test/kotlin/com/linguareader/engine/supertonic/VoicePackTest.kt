package com.linguareader.engine.supertonic

import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.SpeechRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Genera el paquete de voces: por cada voz e idioma, una muestra de prueba y una frase de
 * «preparando la lectura» ya sintetizadas (pasos de calidad por defecto, velocidad 1.0).
 * La app lo descarga junto al modelo y lo usa en «Probar voz» y en la pantalla de preparación,
 * sin tener que cargar el modelo para oírlas. Solo con -Dsupertonic.voicePack=true (tarea :engine:supertonic:voicePack).
 */
class VoicePackTest {

    private val modelDir = File(System.getProperty("supertonic.modelDir", ".models/none"))
    private val out = File(System.getProperty("supertonic.packDir", "build/voice-pack"))

    private val sample = mapOf(
        "en" to "Hello! This is a short test of the Supertonic voice, running offline on this phone.",
        "fr" to "Bonjour ! Ceci est un court essai de la voix Supertonic, sans connexion.",
        "es" to "¡Hola! Esta es una breve prueba de la voz Supertonic, sin conexión.",
    )
    private val ready = mapOf(
        "en" to "Getting ready to read…",
        "fr" to "Préparation de la lecture…",
        "es" to "Preparando la lectura…",
    )

    @Test
    fun generatePack() = runBlocking {
        assumeTrue(System.getProperty("supertonic.voicePack") == "true")
        assumeTrue(SupertonicModelDir(modelDir).isComplete())
        val engine = SupertonicEngine(modelDir = { modelDir }, log = { })
        try {
            val dir = File(out, "voices").also { it.deleteRecursively(); it.mkdirs() }
            val steps = SupertonicEngine.DEFAULT_STEPS
            val items = ArrayList<String>()
            for (lang in sample.keys) for (voice in SupertonicModel.VOICES) for ((kind, texts) in listOf("sample" to sample, "ready" to ready)) {
                val rel = "$lang/$voice/$kind.wav"
                val target = File(dir, rel).also { it.parentFile.mkdirs() }
                val r = engine.synthesizeTo(SpeechRequest(texts.getValue(lang), lang, voice, 1.0f, mapOf("steps" to "$steps")), target)
                check(r is SynthesisOutcome.Written) { "Falló $rel: $r" }
                items += """    {"path":"$rel","sizeBytes":${target.length()},"sha256":"${sha(target)}"}"""
                println("[voicePack] $rel ${target.length() / 1000} KB")
            }
            File(dir, "manifest.json").writeText(
                "{\n  \"version\": 1,\n  \"engine\": \"${SupertonicModel.ENGINE_ID}\",\n  \"steps\": $steps,\n  \"items\": [\n" + items.joinToString(",\n") + "\n  ]\n}\n",
            )
            val zip = File(out, "voices.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            println("[voicePack] ${zip.absolutePath} ${zip.length() / 1_000_000} MB sha256=${sha(zip)}")
        } finally {
            engine.shutdown()
        }
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
