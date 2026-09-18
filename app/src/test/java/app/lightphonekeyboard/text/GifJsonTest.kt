package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The saved GIFs are the only part of this feature that is *yours* — a list nothing on any server
 * holds a copy of — and this codec is the only way it can be lost. Same reasoning, and the same
 * `org.json`-on-the-test-classpath trick, as [NewsletterJsonTest].
 */
class GifJsonTest {

    @Test
    fun `round trips a saved list`() {
        val gifs = listOf(
            Gif("42", "cat typing", "https://x.test/xs.gif", "https://x.test/md.gif", 480, 360),
            Gif("shrug-9", "", "https://x.test/t.gif", "https://x.test/f.gif"),
        )
        assertEquals(gifs, GifJson.decode(GifJson.encode(gifs)))
    }

    @Test
    fun `garbage and absent storage decode to nothing rather than throwing`() {
        assertEquals(emptyList<Gif>(), GifJson.decode(null))
        assertEquals(emptyList<Gif>(), GifJson.decode(""))
        assertEquals(emptyList<Gif>(), GifJson.decode("{not json"))
        assertEquals(emptyList<Gif>(), GifJson.decode("""{"id":"1"}"""))
    }

    @Test
    fun `an entry missing what a send needs is dropped, and the rest survive`() {
        val raw = """
            [
              {"title":"no id","send":"https://x.test/a.gif"},
              {"id":"2","title":"no send url"},
              {"id":"3","title":"fine","send":"https://x.test/c.gif"}
            ]
        """.trimIndent()
        assertEquals(listOf("3"), GifJson.decode(raw).map { it.id })
    }

    @Test
    fun `an entry with no preview falls back to the sendable url`() {
        val raw = """[{"id":"4","send":"https://x.test/d.gif"}]"""
        val gif = GifJson.decode(raw).single()
        assertEquals("https://x.test/d.gif", gif.previewUrl)
        assertEquals(0, gif.width)
    }

    @Test
    fun `a title with a newline in it survives, which is why this is json`() {
        // The guid lists elsewhere are newline-joined; a provider-written title cannot be.
        val gifs = listOf(Gif("5", "two\nlines", "https://x.test/e.gif", "https://x.test/e.gif"))
        assertEquals(gifs, GifJson.decode(GifJson.encode(gifs)))
    }
}
