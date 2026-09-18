package app.lightphonekeyboard.text

import app.lightphonekeyboard.api.KlipyApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a failed request into a sentence.
 *
 * The one that matters is 403. KLIPY sits behind Cloudflare, and Cloudflare answers 403 with its own
 * page when it dislikes the client — the same status the API uses for a key it rejects. Reading the
 * status alone tells somebody whose key is fine to go and replace it, which is the worst advice
 * available. Verified against the live service: a real key, a made-up key and no key at all all came
 * back 403 with "Error 1010" from a datacentre address.
 */
class KlipyReasonTest {

    private val cloudflare1010 = """
        {"type":"https://developers.cloudflare.com/support/troubleshooting/http-status-codes/
        cloudflare-1xxx-errors/error-1010/","title":"Error 1010: Access denied","status":403,
        "detail":"The site owner has blocked access based on your browser's signature."}
    """.trimIndent()

    @Test
    fun `cloudflare's 403 does not blame the key`() {
        val reason = KlipyApi.reasonFor(403, cloudflare1010)
        assertTrue(reason, "connection" in reason)
        assertTrue("it must not send the user off to replace a working key", "key" !in reason.removeSuffix("not the key"))
    }

    @Test
    fun `the api's own 403 does blame the key`() {
        assertEquals("The GIF service refused that key", KlipyApi.reasonFor(403, """{"error":"invalid key"}"""))
        assertEquals("The GIF service refused that key", KlipyApi.reasonFor(401, ""))
    }

    /** An empty body is the ordinary case and must not be mistaken for an edge block. */
    @Test
    fun `no body reads as the api refusing`() {
        assertEquals("The GIF service refused that key", KlipyApi.reasonFor(403, ""))
        assertEquals("The GIF service refused that key", KlipyApi.reasonFor(403))
    }

    @Test
    fun `a busy shared key names the way out`() {
        val reason = KlipyApi.reasonFor(429, "")
        assertTrue(reason, "Settings" in reason)
    }

    @Test
    fun `everything else says what it was`() {
        assertEquals("That GIF service has no search endpoint", KlipyApi.reasonFor(404, ""))
        assertEquals("The GIF service is having trouble", KlipyApi.reasonFor(503, ""))
        assertEquals("The GIF service said 418", KlipyApi.reasonFor(418, ""))
    }
}
