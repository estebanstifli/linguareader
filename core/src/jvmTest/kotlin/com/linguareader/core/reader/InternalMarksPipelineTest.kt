package com.linguareader.core.reader

import com.linguareader.core.audio.AlignmentSidecar
import com.linguareader.core.audio.SidecarData
import com.linguareader.core.audio.SidecarEntry
import com.linguareader.core.audio.SpokenMark
import com.linguareader.core.audio.SpokenMarksSidecar
import com.linguareader.core.model.Passage
import com.linguareader.core.model.Sentence
import com.linguareader.core.model.Token
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InternalMarksPipelineTest {
    @get:Rule val folder = TemporaryFolder()
    private val sentence = Sentence("s", 0, "One two three.", 0, 14, emptyMap())
    private val tokens = listOf(Token("a",0,"One",0,3,"s"), Token("b",1,"two",4,7,"s"), Token("c",2,"three",8,13,"s"))
    private val passage = Passage("p", "ch", 0, sentence.text, listOf(sentence), tokens, null)
    private val fs = FileSystem.SYSTEM
    private val pipeline = SentenceClipPipeline(FakeSynthesizer()) // no external aligner registered

    private fun wav() = folder.newFile("clip.wav").apply {
        writeBytes(ByteBuffer.allocate(2044).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(2036).put("WAVEfmt ".toByteArray())
            putInt(16).putShort(1).putShort(1).putInt(1000).putInt(2000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(2000)
            repeat(1000) { putShort(1000) }
        }.array())
    }.toOkioPath()

    @Test
    fun `cached bounded marks drive words without another model and preserve both boundaries`() = runTest {
        val file = wav()
        val marks = listOf(SpokenMark(0,3,100,250,.8f),SpokenMark(4,7,400,550,.7f),SpokenMark(8,13,700,900,.9f))
        SpokenMarksSidecar.write(fs,file,marks)
        // A historical external sidecar is left intact but cannot supersede native marks.
        AlignmentSidecar.write(fs,file,SidecarData("old-external",2044,true,listOf(SidecarEntry(0,0,1000))))
        val timing = pipeline.align(passage,0,file.toString(),"en")!!
        assertEquals("a",timing.timeline.tokenAt(100)!!.tokenId)
        assertEquals(250L,timing.timeline.tokenAt(100)!!.endMs)
        assertEquals(.8f,timing.timeline.tokenAt(100)!!.confidence)
        assertNull(timing.timeline.tokenAt(300))
        assertNull(timing.timeline.tokenAt(950))
        assertTrue(fs.exists(AlignmentSidecar.fileFor(file)))
        assertEquals(marks,SpokenMarksSidecar.read(fs,file))
        assertEquals(550L,pipeline.align(passage,0,file.toString(),"en")!!.timeline.tokenAt(450)!!.endMs)
    }

    @Test
    fun `missing and incomplete native marks retain sentence fallback without inventing words`() = runTest {
        val file = wav()
        assertNull(pipeline.align(passage,0,file.toString(),"en"))
        SpokenMarksSidecar.write(fs,file,listOf(SpokenMark(0,3,100,250)))
        assertNull(pipeline.align(passage,0,file.toString(),"en"))
        SpokenMarksSidecar.write(fs,file,listOf(SpokenMark(0,3,100,250),SpokenMark(4,7,200,400),SpokenMark(8,13,700,900)))
        assertNull(pipeline.align(passage,0,file.toString(),"en"))
    }

    @Test
    fun `old Android TTS start-only sidecars still load and map without external models`() = runTest {
        val file = wav()
        fs.write(SpokenMarksSidecar.fileFor(file)) { writeUtf8("""{"v":1,"marks":[{"start":0,"end":3,"ms":100},{"start":4,"end":7,"ms":400},{"start":8,"end":13,"ms":700}]}""") }
        val timing = pipeline.align(passage,0,file.toString(),"en")!!
        assertEquals(400L,timing.timeline.tokenAt(120)!!.endMs)
        assertEquals("c",timing.timeline.tokenAt(800)!!.tokenId)
    }
}
