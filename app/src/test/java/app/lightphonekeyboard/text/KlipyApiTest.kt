package app.lightphonekeyboard.text

import app.lightphonekeyboard.api.KlipyApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * The GIF search's only fragile part: the shape the service answers in.
 *
 * Worth a test rather than a phone because the parser is deliberately tolerant of two envelopes —
 * KLIPY's own and the Tenor-compatible one they serve for the apps that were stranded when Google
 * switched Tenor off — and "tolerant" is indistinguishable from "quietly wrong" without something
 * asserting which rendition it picked. Picking the wrong one means either sending an eight-megabyte
 * file over a phone tunnel or, worse, sending an mp4 that arrives as a video.
 */
class KlipyApiTest {

    /** KLIPY's documented envelope: `data.data[]`, renditions nested by size under `file`. */
    private val native = """
        {
          "result": true,
          "data": {
            "data": [
              {
                "id": "42",
                "slug": "cat-typing-42",
                "title": "cat typing",
                "file": {
                  "hd": {
                    "gif": { "url": "https://cdn.klipy.test/42/hd.gif", "width": 960, "height": 720 },
                    "mp4": { "url": "https://cdn.klipy.test/42/hd.mp4", "width": 960, "height": 720 }
                  },
                  "md": {
                    "gif": { "url": "https://cdn.klipy.test/42/md.gif", "width": 480, "height": 360 }
                  },
                  "xs": {
                    "gif": { "url": "https://cdn.klipy.test/42/xs.gif", "width": 120, "height": 90 }
                  }
                }
              }
            ],
            "current_page": 1,
            "per_page": 24,
            "has_next": true
          }
        }
    """.trimIndent()

    /** The Tenor-shaped envelope: `results[]`, renditions under `media_formats`, sizes in `dims`. */
    private val compatible = """
        {
          "results": [
            {
              "id": "99",
              "title": "shrug",
              "media_formats": {
                "gif": { "url": "https://cdn.klipy.test/99/full.gif", "dims": [498, 280] },
                "tinygif": { "url": "https://cdn.klipy.test/99/tiny.gif", "dims": [220, 124] },
                "mp4": { "url": "https://cdn.klipy.test/99/full.mp4", "dims": [498, 280] }
              }
            }
          ],
          "next": "20"
        }
    """.trimIndent()

    @Test
    fun `reads the native envelope and sends the medium rendition`() {
        val page = KlipyApi.parsePage(JSONObject(native))
        assertEquals(1, page.gifs.size)
        val gif = page.gifs.single()
        assertEquals("42", gif.id)
        assertEquals("cat typing", gif.title)
        // Medium to send: an HD GIF is megabytes uploaded over Tailscale for no visible gain.
        assertEquals("https://cdn.klipy.test/42/md.gif", gif.sendUrl)
        assertEquals(480, gif.width)
        assertEquals(360, gif.height)
        // NOT the smallest for the grid: `xs` is commonly 120px against a cell about 400 device
        // pixels wide, which is what made the results look soft. With no `sm` in this payload the
        // preference steps up to `md` rather than down to `xs`.
        assertEquals("https://cdn.klipy.test/42/md.gif", gif.previewUrl)
        assertTrue(page.hasNext)
    }

    @Test
    fun `reads the tenor-shaped envelope, dims array and all`() {
        val page = KlipyApi.parsePage(JSONObject(compatible))
        val gif = page.gifs.single()
        assertEquals("99", gif.id)
        assertEquals("https://cdn.klipy.test/99/full.gif", gif.sendUrl)
        assertEquals(498, gif.width)
        assertEquals(280, gif.height)
        assertEquals("https://cdn.klipy.test/99/tiny.gif", gif.previewUrl)
        // A cursor in `next` is another page, even though we page by number rather than by cursor.
        assertTrue(page.hasNext)
    }

    @Test
    fun `never picks an mp4 or a webp, whatever the envelope`() {
        for (raw in listOf(native, compatible)) {
            for (gif in KlipyApi.parsePage(JSONObject(raw)).gifs) {
                assertTrue(gif.sendUrl, gif.sendUrl.endsWith(".gif"))
                assertTrue(gif.previewUrl, gif.previewUrl.endsWith(".gif"))
            }
        }
    }

    @Test
    fun `the grid takes sm when there is one, and never drops to xs to save bytes`() {
        val allSizes = """
            {"data":{"data":[{"id":"3","file":{
              "hd":{"gif":{"url":"https://x.test/hd.gif","width":960,"height":720}},
              "md":{"gif":{"url":"https://x.test/md.gif","width":480,"height":360}},
              "sm":{"gif":{"url":"https://x.test/sm.gif","width":220,"height":165}},
              "xs":{"gif":{"url":"https://x.test/xs.gif","width":120,"height":90}}
            }}]}}
        """.trimIndent()
        val gif = KlipyApi.parsePage(JSONObject(allSizes)).gifs.single()
        assertEquals("https://x.test/sm.gif", gif.previewUrl)
        assertEquals("https://x.test/md.gif", gif.sendUrl)
    }

    @Test
    fun `one rendition serves both jobs when it is all there is`() {
        // Preference, not requirement: an item offering only a tiny rendition is still drawn and
        // still sendable, because an empty cell is worse than a small GIF.
        val onlyXs = """
            {"data":{"data":[{"id":"4","file":{"xs":{"gif":{"url":"https://x.test/only.gif"}}}}]}}
        """.trimIndent()
        val gif = KlipyApi.parsePage(JSONObject(onlyXs)).gifs.single()
        assertEquals("https://x.test/only.gif", gif.previewUrl)
        assertEquals("https://x.test/only.gif", gif.sendUrl)
    }

    @Test
    fun `an item with no gif at all is dropped rather than sent as nothing`() {
        val onlyVideo = """
            {"data":{"data":[
              {"id":"7","file":{"md":{"mp4":{"url":"https://cdn.klipy.test/7/md.mp4"}}}},
              {"id":"8","file":{"md":{"gif":{"url":"https://cdn.klipy.test/8/md.gif"}}}}
            ],"has_next":false}}
        """.trimIndent()
        val page = KlipyApi.parsePage(JSONObject(onlyVideo))
        assertEquals(listOf("8"), page.gifs.map { it.id })
        assertFalse(page.hasNext)
    }

    @Test
    fun `an ad slot or an unrecognised row cannot become a gif`() {
        // Ads arrive in the same array as results and carry no id and no renditions.
        val withAd = """
            {"data":{"data":[
              {"type":"ad","content":{"html":"<div/>"}},
              {"id":"11","file":{"sm":{"gif":{"url":"https://cdn.klipy.test/11/sm.gif"}}}}
            ]}}
        """.trimIndent()
        assertEquals(listOf("11"), KlipyApi.parsePage(JSONObject(withAd)).gifs.map { it.id })
    }

    @Test
    fun `an empty or unrecognised answer is an empty page, not an exception`() {
        assertEquals(emptyList<Gif>(), KlipyApi.parsePage(JSONObject("{}")).gifs)
        assertEquals(emptyList<Gif>(), KlipyApi.parsePage(JSONObject("""{"result":false}""")).gifs)
        assertEquals(emptyList<Gif>(), KlipyApi.parsePage(JSONObject("""{"data":{"data":[]}}""")).gifs)
        // A page with nothing on it has nothing behind it either, whatever the envelope claims.
        assertFalse(KlipyApi.parsePage(JSONObject("""{"data":{"data":[]}}""")).hasNext)
    }

    @Test
    fun `a slug stands in for a missing id, since a favourite is keyed by it`() {
        val slugOnly = """
            {"data":{"data":[{"slug":"dog-high-five","file":{"md":{"gif":{"url":"https://x.test/a.gif"}}}}]}}
        """.trimIndent()
        val gif = KlipyApi.parsePage(JSONObject(slugOnly)).gifs.single()
        assertEquals("dog-high-five", gif.id)
        // No title field: the slug is the only human-readable thing there is.
        assertEquals("dog-high-five", gif.title)
    }

    @Test
    fun `a literal json null title does not become the word null`() {
        // org.json's optString hands back "null" for an explicit null — the bug that once
        // titled conversations "null", and the reason KlipyApi has its own string reader.
        val nulled = """
            {"data":{"data":[{"id":"5","title":null,"file":{"md":{"gif":{"url":"https://x.test/b.gif"}}}}]}}
        """.trimIndent()
        val gif = KlipyApi.parsePage(JSONObject(nulled)).gifs.single()
        assertEquals("", gif.title)
        assertEquals("GIF", gif.label)
        assertNull(gif.title.takeIf { it == "null" })
    }

    @Test
    fun `a banner-shaped gif is clamped so a grid row is not a row of slivers`() {
        assertEquals(2f, Gif("1", "", "p", "s", width = 1000, height = 100).ratio, 0.001f)
        assertEquals(0.5f, Gif("1", "", "p", "s", width = 100, height = 1000).ratio, 0.001f)
        // Square when the service didn't say, rather than a divide by zero.
        assertEquals(1f, Gif("1", "", "p", "s").ratio, 0.001f)
    }
}
