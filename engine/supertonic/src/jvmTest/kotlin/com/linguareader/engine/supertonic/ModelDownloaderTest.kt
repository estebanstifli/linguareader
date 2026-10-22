package com.linguareader.engine.supertonic

import com.linguareader.core.content.Hashing
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

class ModelDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private var corrupt = false
    private val rangesSeen = mutableListOf<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/m/onnx/a.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            rangesSeen += range
            val body = if (corrupt) payload.copyOf().also { it[10] = 99 } else payload
            if (range != null) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                ex.sendResponseHeaders(206, (body.size - from).toLong())
                ex.responseBody.use { it.write(body, from, body.size - from) }
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        start()
    }

    @After fun stop() = server.stop(0)

    private fun downloader() = ModelDownloader(
        "http://127.0.0.1:${server.address.port}/m",
        listOf(ModelFile("onnx/a.bin", payload.size.toLong(), Hashing.sha256Hex(payload))),
    )

    @Test
    fun `downloads verifies and reports progress`() = runBlocking {
        var last: DownloadProgress? = null
        val result = downloader().download(tmp.root) { last = it }
        assertEquals(DownloadResult.Completed, result)
        assertTrue(File(tmp.root, "onnx/a.bin").readBytes().contentEquals(payload))
        assertEquals(1f, last!!.fraction)
    }

    @Test
    fun `resumes from a partial file using Range`() = runBlocking {
        File(tmp.root, "onnx").mkdirs()
        File(tmp.root, "onnx/a.bin.part").writeBytes(payload.copyOf(120_000))
        val result = downloader().download(tmp.root) {}
        assertEquals(DownloadResult.Completed, result)
        assertEquals("bytes=120000-", rangesSeen.first())
        assertTrue(File(tmp.root, "onnx/a.bin").readBytes().contentEquals(payload))
    }

    @Test
    fun `rejects corrupted content and leaves no installed file`() = runBlocking {
        corrupt = true
        val result = downloader().download(tmp.root) {}
        assertTrue(result is DownloadResult.Failed)
        assertTrue(!File(tmp.root, "onnx/a.bin").exists())
    }

    @Test
    fun `already installed files are not downloaded again`() = runBlocking {
        downloader().download(tmp.root) {}
        rangesSeen.clear()
        downloader().download(tmp.root) {}
        assertTrue(rangesSeen.isEmpty())
    }
}
