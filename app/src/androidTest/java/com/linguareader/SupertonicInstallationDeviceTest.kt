package com.linguareader

import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.engine.supertonic.HttpResponse
import com.linguareader.engine.supertonic.HttpTransport
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.engine.supertonic.SupertonicModelDir
import com.linguareader.engine.supertonic.SupertonicModelManager
import com.linguareader.engine.supertonic.platformHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** Real Android HTTP/resume through our R2 domain, verification and graph repair of the pinned model. */
class SupertonicInstallationDeviceTest : KoinComponent {
    private val engine: SupertonicEngine by inject()

    @Test fun partialR2DownloadRepairsGraphAndRemainsPlayable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val job = SupervisorJob()
        val requests = mutableListOf<Pair<String, String?>>()
        val responses = mutableListOf<Int>()
        val realTransport = platformHttpTransport()
        val recordingTransport = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
                requests += url to headers["Range"]
                return realTransport.get(url, headers).also { responses += it.code }
            }
        }
        val manager = SupertonicModelManager(FileSystem.SYSTEM, context.filesDir.toOkioPath(),
            CoroutineScope(job + Dispatchers.IO), transport = recordingTransport)
        val dir = SupertonicModelDir(manager.modelDir)
        // Opt-in deployment check for an empty emulator; never deletes an existing installation.
        if (!dir.isComplete() && InstrumentationRegistry.getArguments().getString("installIfMissing") == "true") {
            try {
                manager.download()
                val installed = withTimeout(300_000) {
                    manager.state.first { it is ModelState.Installed || it is ModelState.Failed }
                }
                assertTrue("Fresh R2 installation failed: $installed", installed is ModelState.Installed)
                val expected = SupertonicModel.FILES.map { "${SupertonicModel.BASE_URL}/${it.path}" }
                assertEquals("All fresh model files must come directly from R2", expected, requests.map { it.first })
                assertTrue("Fresh requests must have successful responses", responses.all { it == 200 || it == 206 })
                assertTrue(dir.isVerified())
                val folder = File(context.filesDir, "internal-timing-test").apply { mkdirs() }
                File(folder, "r2-fresh-install.txt").writeText(
                    "PASS: fresh R2 download of all ${requests.size} original files (${SupertonicModel.TOTAL_BYTES} bytes); " +
                        "all SHA-256 checks passed; timing graph prepared locally.\n")
                requests.clear()
                responses.clear()
            } catch (error: Throwable) {
                job.cancelAndJoin()
                throw error
            }
        }
        assertTrue("This test requires the installed original weights", dir.isVerified())
        engine.release()
        val license = File(dir.file("LICENSE").toString())
        val partial = File(license.path + ".part")
        val graph = File(dir.ensureTimingModel().toString())
        val marker = File(dir.file(SupertonicModel.MARKER).toString())
        val originalLicense = license.readBytes()
        val originalGraph = graph.readBytes()
        val originalMarker = marker.readBytes()
        assertFalse("Do not overwrite an existing partial download", partial.exists())
        try {
            // Leave a genuine partial HTTP download and a corrupt, rebuildable graph.
            partial.writeBytes(originalLicense.copyOf(512))
            assertTrue(license.delete())
            assertTrue(marker.delete())
            graph.writeText("corrupt graph: installation recovery probe")
            manager.refresh()
            assertTrue(manager.state.value is ModelState.NotInstalled)
            manager.download()
            val terminal = withTimeout(120_000) {
                manager.state.first { it is ModelState.Installed || it is ModelState.Failed }
            }
            assertTrue("Real download/verification failed: $terminal", terminal is ModelState.Installed)
            val expectedLicenseUrl = "https://files.lingoaudiobook.eu/models/supertonic-3/${SupertonicModel.REVISION}/LICENSE"
            assertEquals("The app must resume directly from R2, without falling back to another host",
                listOf(expectedLicenseUrl to "bytes=512-"), requests)
            assertEquals("R2 must honor the partial download", listOf(206), responses)
            assertArrayEquals(originalLicense, license.readBytes())
            assertArrayEquals(originalGraph, graph.readBytes())
            assertFalse("Completed downloads must publish the final file", partial.exists())
            assertTrue(dir.isVerified())
            val folder = File(context.filesDir, "internal-timing-test").apply { mkdirs() }
            val audio = engine.synthesizeTo(SpeechRequest("El modelo vuelve a leer con sus tiempos internos.", "es", "F1", 1f,
                mapOf("steps" to "5")), File(folder, "installation-recovery.wav").toOkioPath())
            assertTrue(audio.toString(), audio is SynthesisOutcome.Written)
            assertTrue((audio as SynthesisOutcome.Written).marks.orEmpty().all { it.endMs != null })
            assertFalse(audio.marks.isNullOrEmpty())
            File(folder, "installation.txt").writeText(
                "PASS: real R2 HTTP 206 download from $expectedLicenseUrl, resuming a 512-byte partial LICENSE; SHA verification of all 17 files; " +
                    "corrupt derived graph repaired byte-for-byte; real audio with word end times.\n")
        } finally {
            job.cancelAndJoin()
            engine.release()
            // The failure path must leave the previously installed model usable too.
            if (!license.exists() || !license.readBytes().contentEquals(originalLicense)) license.writeBytes(originalLicense)
            if (!graph.exists() || !graph.readBytes().contentEquals(originalGraph)) graph.writeBytes(originalGraph)
            if (!marker.exists() || !marker.readBytes().contentEquals(originalMarker)) marker.writeBytes(originalMarker)
            partial.delete()
        }
    }
}
