package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipsTest {

    private fun round(clips: List<Clips.Clip>) = Clips.parse(Clips.serialize(clips))

    @Test
    fun `round trip keeps order and pins`() {
        val clips = listOf(
            Clips.Clip("first", pinned = true),
            Clips.Clip("second"),
        )
        assertEquals(clips, round(clips))
    }

    /** The format is line- and tab-delimited, so a clip containing either must not split the record. */
    @Test
    fun `round trip survives newlines tabs and backslashes`() {
        val nasty = "line one\nline\ttwo\\r\\n\r\nend\\"
        val clips = listOf(Clips.Clip(nasty))
        val back = round(clips)
        assertEquals(1, back.size)
        assertEquals(nasty, back[0].text)
    }

    @Test
    fun `a corrupt line is dropped and the rest survives`() {
        val stored = "0\tgood\nnot a record\n1\talso good"
        val back = Clips.parse(stored)
        assertEquals(2, back.size)
        assertEquals("good", back[0].text)
        assertTrue(back[1].pinned)
    }

    @Test
    fun `adding moves an existing clip to the top instead of duplicating it`() {
        var clips = Clips.add(emptyList(), "a")
        clips = Clips.add(clips, "b")
        clips = Clips.add(clips, "a")
        assertEquals(listOf("a", "b"), clips.map { it.text })
    }

    /** Re-copying something pinned must not silently unpin it. */
    @Test
    fun `re-adding a pinned clip keeps the pin`() {
        var clips = Clips.add(emptyList(), "keep me")
        clips = Clips.togglePin(clips, "keep me")
        clips = Clips.add(clips, "keep me")
        assertTrue(clips.single().pinned)
    }

    @Test
    fun `unpinned clips fall off the end but pinned ones never do`() {
        var clips = Clips.add(emptyList(), "pinned")
        clips = Clips.togglePin(clips, "pinned")
        for (i in 0 until Clips.LIMIT + 10) clips = Clips.add(clips, "loose $i")
        assertEquals(Clips.LIMIT, clips.count { !it.pinned })
        assertTrue(clips.any { it.text == "pinned" })
    }

    @Test
    fun `clearing keeps the pins`() {
        var clips = Clips.add(emptyList(), "a")
        clips = Clips.add(clips, "b")
        clips = Clips.togglePin(clips, "a")
        assertEquals(listOf("a"), Clips.clearUnpinned(clips).map { it.text })
    }

    @Test
    fun `blank text is not stored`() {
        assertTrue(Clips.add(emptyList(), "   \n ").isEmpty())
    }

    @Test
    fun `an over-long clip is truncated rather than refused`() {
        val clips = Clips.add(emptyList(), "x".repeat(Clips.MAX_LEN * 2))
        assertEquals(Clips.MAX_LEN, clips.single().text.length)
    }

    @Test
    fun `preview is one line`() {
        assertEquals("one two three", Clips.preview("one\n  two\tthree "))
        assertFalse(Clips.preview("y".repeat(200)).contains('\n'))
        assertEquals(20, Clips.preview("y".repeat(200), 20).length)
    }
}
