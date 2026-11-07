package com.linguareader.engine.supertonic

import com.linguareader.core.content.Hashing
import okio.Path.Companion.toOkioPath
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SupertonicTimingGraphTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `embedded graph decompresses to the pinned graph without bundling weights`() {
        val graph = SupertonicTimingGraph.bytes()
        assertEquals(SupertonicTimingGraph.SIZE, graph.size.toLong())
        assertEquals(SupertonicTimingGraph.SHA256, Hashing.sha256Hex(graph))
        assertTrue(graph.size < 1_000_000)
        val metadata = graph.toString(Charsets.ISO_8859_1)
        assertTrue(metadata.contains("vector_estimator.onnx"))
        assertTrue(metadata.contains("timing_attention"))
    }

    @Test fun `cannot prepare a graph without a verified source installation`() {
        val dir = SupertonicModelDir(temporary.root.toOkioPath())
        val result = runCatching { dir.ensureTimingModel() }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertFalse(temporary.root.resolve(SupertonicTimingGraph.FILE).exists())
    }

    @Test fun `concurrent publishers use separate temporary files and accept the verified winner`() {
        val root = temporary.root.toOkioPath()
        FileSystem.SYSTEM.createDirectories(root / "onnx")
        val bothWritten = CyclicBarrier(2)
        val firstPublished = CountDownLatch(1)
        val publishing = AtomicInteger()
        val temporaryPaths = Collections.synchronizedList(mutableListOf<Path>())
        val fs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                temporaryPaths += source
                bothWritten.await(10, TimeUnit.SECONDS)
                if (publishing.getAndIncrement() == 0) {
                    try { super.atomicMove(source, target) } finally { firstPublished.countDown() }
                } else {
                    assertTrue(firstPublished.await(10, TimeUnit.SECONDS))
                    // Deterministic simulation of a filesystem that refuses replacement.
                    throw IOException("Another caller already published the destination")
                }
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                workers.submit<Path> { SupertonicModelDir(root, fs).publishTimingGraph() }
            }.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals(SupertonicTimingGraph.SHA256, SupertonicModelDir.sha256(results[0]))
            assertEquals("Concurrent callers must not share a .part file", 2, temporaryPaths.distinct().size)
            assertTrue(temporaryPaths.none { FileSystem.SYSTEM.exists(it) })
            assertEquals(listOf(results[0]), FileSystem.SYSTEM.list(root / "onnx"))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun `failed publication never accepts a corrupt destination and cleans only its own temporary`() {
        val root = temporary.root.toOkioPath()
        val target = root / SupertonicTimingGraph.FILE
        FileSystem.SYSTEM.createDirectories(target.parent!!)
        FileSystem.SYSTEM.write(target) { writeUtf8("corrupt") }
        val unrelated = target.parent!! / "unrelated.part"
        FileSystem.SYSTEM.write(unrelated) { writeUtf8("another operation owns this") }
        val expected = IOException("Publication denied")
        val fs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path): Unit = throw expected
        }
        val result = runCatching { SupertonicModelDir(root, fs).publishTimingGraph() }
        assertTrue(result.exceptionOrNull() === expected)
        assertEquals("corrupt", FileSystem.SYSTEM.read(target) { readUtf8() })
        assertTrue(FileSystem.SYSTEM.exists(unrelated))
        assertEquals(setOf(target, unrelated), FileSystem.SYSTEM.list(target.parent!!).toSet())
    }
}
