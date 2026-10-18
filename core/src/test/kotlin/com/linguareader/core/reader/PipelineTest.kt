package com.linguareader.core.reader

import com.linguareader.core.alignment.ClipAligner
import com.linguareader.core.alignment.WordSpanMs
import com.linguareader.core.audio.AlignmentSidecar
import com.linguareader.core.audio.FileAudioCache
import com.linguareader.core.engine.AudioAsset
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.testing.SampleBooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sintetizador que escribe WAV reales (1 s) en un directorio, como la caché de disco de verdad. */
private class DiskSynthesizer(private val dir: File) : SpeechSynthesizer {
    override val id = "disk-fake"
    override val info = TtsEngineInfo(id, "Disk", streaming = false, providesWordTimings = false, requiresModelDownload = false, offline = true)
    val requests = mutableListOf<String>()
    override suspend fun synthesize(request: SpeechRequest): SpeechResult {
        requests += request.text
        val f = File(dir, "${request.text.hashCode()}.wav")
        if (!f.exists()) f.writeBytes(wav(1000))
        return SpeechResult.Success(AudioAsset(f.path, "audio/wav", 1000, null), "v1")
    }
    override suspend fun availability(languageTag: String, voiceId: String?) = EngineReadiness.Ready
    override suspend fun listVoices(languageTag: String) = emptyList<VoiceInfo>()
    override fun release() {}

    companion object {
        fun wav(ms: Int): ByteArray {
            val sr = 44_100
            val n = sr * ms / 1000
            return ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    .putInt(sr).putInt(sr * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
            }.array()
        }
    }
}

private class CountingAligner(var gate: CompletableDeferred<Unit>? = null, var signature: String = "m1") : ClipAligner {
    override val id = "counting"
    var calls = 0
    override fun signature(languageTag: String) = signature
    override suspend fun align(clipPath: String, words: List<String>, languageTag: String): List<WordSpanMs>? {
        calls++
        gate?.await()
        return words.indices.map { WordSpanMs(it, 50L + 100 * it, 50L + 100 * it + 80, 0.9f) }
    }
    override fun release() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private val passage = SampleBooks.english.passages.first() // 2 frases
    private val player = FakeClipPlayer()

    private fun TestScope.controller(synth: SpeechSynthesizer, aligner: ClipAligner?) =
        RenderedReaderAudioController(
            synth, player, FakeClipPlayer(), StandardTestDispatcher(testScheduler),
            aligner = aligner, alignDispatcher = StandardTestDispatcher(testScheduler), tickMs = 10, clockMs = { testScheduler.currentTime },
        ).also { advanceUntilIdle() }

    @Test
    fun `tts runs ahead independently while a clip waits for its alignment, and playback only gets aligned clips`() = runTest {
        val synth = DiskSynthesizer(tmp.newFolder())
        val gate = CompletableDeferred<Unit>()
        val aligner = CountingAligner(gate)
        val c = controller(synth, aligner)
        c.load(passage, "en", null)
        c.play(); advanceTimeBy(100)
        // Etapa 1 ya terminó todas las frases; etapa 2 sigue con la primera; nada llegó aún al reproductor.
        assertEquals(passage.sentences.map { it.text }, synth.requests)
        assertEquals(1, aligner.calls)
        assertTrue(player.queue.isEmpty())
        gate.complete(Unit); advanceTimeBy(200)
        assertEquals(listOf(0, 1), player.queue.map { it.first })
        assertEquals(2, aligner.calls)
        c.pause(); advanceUntilIdle()
    }

    @Test
    fun `alignment is persisted next to the audio and reused instead of recomputed`() = runTest {
        val synth = DiskSynthesizer(tmp.newFolder())
        val aligner = CountingAligner()
        val first = controller(synth, aligner)
        first.load(passage, "en", null)
        first.play(); advanceTimeBy(500); first.pause(); advanceUntilIdle()
        assertEquals(2, aligner.calls)
        passage.sentences.forEach { s ->
            val wav = File(synth.requests.first { it == s.text }.let { File(tmp.root, "").walkTopDown().first { f -> f.name == "${it.hashCode()}.wav" }.path })
            assertTrue(AlignmentSidecar.fileFor(wav).isFile)
        }
        // «Volver atrás de página»: un controlador nuevo (como tras reabrir) no vuelve a alinear.
        val second = controller(synth, aligner)
        second.load(passage, "en", null)
        second.play(); advanceTimeBy(100)
        assertEquals(2, aligner.calls)
        // y el resaltado por palabra funciona con lo guardado
        val tokens = passage.tokensOf(passage.sentences[0])
        player.position = ClipPosition(0, 50L + 100 * 1 + 10)
        advanceTimeBy(50)
        assertEquals(tokens[1].id, second.playbackState.value.activeTokenId)
        second.pause(); advanceUntilIdle()
    }

    @Test
    fun `a different aligner model invalidates the saved alignment`() = runTest {
        val synth = DiskSynthesizer(tmp.newFolder())
        val aligner = CountingAligner()
        val c = controller(synth, aligner)
        c.load(passage, "en", null)
        c.play(); advanceTimeBy(500); c.pause(); advanceUntilIdle()
        assertEquals(2, aligner.calls)
        aligner.signature = "m2"
        val again = controller(synth, aligner)
        again.load(passage, "en", null)
        again.play(); advanceTimeBy(500); again.pause(); advanceUntilIdle()
        assertEquals(4, aligner.calls)
    }

    @Test
    fun `warm-up does tts and alignment for the first sentences so that playing needs neither`() = runTest {
        val synth = DiskSynthesizer(tmp.newFolder())
        val aligner = CountingAligner()
        val c = controller(synth, aligner)
        c.load(passage, "en", null)
        assertTrue(c.warmUp(passage, "en", null, WarmUpPolicy(maxSentences = 2)))
        assertEquals(2, aligner.calls)
        assertTrue(player.queue.isEmpty())
        val ttsBefore = synth.requests.size
        c.play(); advanceTimeBy(500); c.pause(); advanceUntilIdle()
        assertEquals(2, aligner.calls) // no se alineó otra vez
        assertEquals(listOf(0, 1), player.queue.map { it.first })
        assertNotNull(c.playbackState.value)
        assertFalse(c.playbackState.value.buffering)
        assertEquals(ttsBefore + 2, synth.requests.size) // la síntesis pasa por la caché (aquí el fake la cuenta, el WAV ya existe)
    }

    @Test
    fun `window prefetch also aligns every sentence of the following pages so they start with all their words`() = runTest {
        val synth = DiskSynthesizer(tmp.newFolder())
        val aligner = CountingAligner()
        val c = controller(synth, aligner)
        val window = TestPages.build(4).subList(1, 4)
        c.load(passage, "en", null)
        c.play(); c.prefetch(window, "en", null)
        advanceTimeBy(500); c.pause(); advanceUntilIdle() // pausa el ticker; la precarga sigue hasta terminar
        val distinct = (listOf(passage) + window).flatMap { p -> p.sentences.map { it.text } }.distinct().size
        assertEquals(distinct, aligner.calls)

        // Al llegar a la página siguiente todo está en caché: ni síntesis nueva en disco ni alineación nueva.
        val callsBefore = aligner.calls
        c.load(window[0], "en", null)
        assertTrue(c.warmUp(window[0], "en", null, WarmUpPolicy(maxSentences = window[0].sentences.size)))
        assertEquals(callsBefore, aligner.calls)
    }

    @Test
    fun `cache trim removes the alignment together with the evicted audio and counts it in the size`() {
        val dir = tmp.newFolder()
        var now = 0L
        val cache = FileAudioCache(dir, 1_000L, clock = { now })
        val a = cache.put("aaaaaaaa") { it.writeBytes(ByteArray(400)) }
        AlignmentSidecar.write(a, com.linguareader.core.audio.SidecarData("m", a.length(), true))
        now = 10
        assertTrue(cache.sizeBytes() > 400)
        now = 20
        cache.put("bbbbbbbb") { it.writeBytes(ByteArray(400)) }
        now = 30
        cache.put("cccccccc") { it.writeBytes(ByteArray(400)) } // supera 1000 → expulsa el más antiguo (a) con su alineación
        assertFalse(a.exists())
        assertFalse(AlignmentSidecar.fileFor(a).exists())
        assertTrue(cache.sizeBytes() <= 1_000L)
    }
}
