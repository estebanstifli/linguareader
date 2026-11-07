package com.linguareader.engine.supertonic

import okio.Path.Companion.toOkioPath
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
 * Genera los paquetes de voces, **uno por idioma**: `voices-<idioma>-v<N>.zip` con, por cada voz, la muestra de
 * «Probar voz» y la frase de «preparando la lectura» ya sintetizadas ([SupertonicSampleTexts]; pasos de calidad por
 * defecto, velocidad 1.0) y un `manifest.json` con tamaño y sha256 de cada WAV. La app descarga solo el paquete del
 * idioma que se aprende (o el que se elige en Ajustes) y así oye las voces sin cargar el modelo.
 *
 * Solo con -Dsupertonic.voicePack=true (tarea :engine:supertonic:voicePack). Opcional:
 * -Dsupertonic.languages=de,it para regenerar solo algunos idiomas (por defecto, los 31).
 * Los zips ya existentes de otros idiomas no se tocan.
 */
class VoicePackTest {

    private val modelDir = File(System.getProperty("supertonic.modelDir", ".models/none"))
    private val out = File(System.getProperty("supertonic.packDir", "build/voice-pack"))
    private val languages = System.getProperty("supertonic.languages")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: SupertonicModel.LANGUAGES.toList()

    @Test
    fun generatePacks() = runBlocking {
        assumeTrue(System.getProperty("supertonic.voicePack") == "true")
        assumeTrue(SupertonicModelDir(modelDir.toOkioPath()).isComplete())
        val missing = languages.filter { it !in SupertonicSampleTexts.SAMPLE || it !in SupertonicSampleTexts.READY }
        check(missing.isEmpty()) { "Sin textos de muestra para: $missing" }
        val engine = SupertonicEngine(modelDir = { modelDir.toOkioPath() }, log = { })
        try {
            val steps = SupertonicEngine.DEFAULT_STEPS
            for (lang in languages) {
                val dir = File(out, "voices/$lang").also { it.deleteRecursively(); it.mkdirs() }
                val items = ArrayList<String>()
                for (voice in SupertonicModel.VOICES) for ((kind, text) in listOf("sample" to SupertonicSampleTexts.SAMPLE, "ready" to SupertonicSampleTexts.READY)) {
                    val rel = "$voice/$kind.wav"
                    val target = File(dir, rel).also { it.parentFile.mkdirs() }
                    val r = engine.synthesizeTo(SpeechRequest(text.getValue(lang), lang, voice, 1.0f, mapOf("steps" to "$steps")), target.toOkioPath())
                    check(r is SynthesisOutcome.Written) { "Falló $lang/$rel: $r" }
                    items += """    {"path":"$rel","sizeBytes":${target.length()},"sha256":"${sha(target)}"}"""
                    println("[voicePack] $lang/$rel ${target.length() / 1000} KB")
                }
                File(dir, "manifest.json").writeText(
                    "{\n  \"version\": $VERSION,\n  \"language\": \"$lang\",\n  \"engine\": \"${SupertonicModel.ENGINE_ID}\",\n  \"steps\": $steps,\n  \"items\": [\n" +
                        items.joinToString(",\n") + "\n  ]\n}\n",
                )
                val zip = File(out, "voices-$lang-v$VERSION.zip")
                ZipOutputStream(zip.outputStream()).use { z ->
                    dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                        z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
                println("[voicePack] ${zip.absolutePath} ${zip.length() / 1_000_000} MB sha256=${sha(zip)}")
            }
        } finally {
            engine.shutdown()
        }
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Versión del formato/contenido del paquete por idioma (va en el nombre del zip y en el catálogo). */
        const val VERSION = 1
    }
}
