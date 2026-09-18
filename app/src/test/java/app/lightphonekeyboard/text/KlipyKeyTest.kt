package app.lightphonekeyboard.text

import app.lightphonekeyboard.api.KlipyKey
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped GIF key is scrambled by the **build script** and unscrambled by **app code** — one
 * mechanism living in two files that compile separately and can drift apart without anything
 * failing to build. What that drift produces is a key that decodes to rubbish, a 401 from the
 * service, and nothing anywhere to say why. Hence this.
 */
class KlipyKeyTest {

    /** A stand-in shaped like a real key. Never the actual one: it belongs in a repository secret,
     *  and this file is as public as the rest of the repo. */
    private val sample = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"

    /** The build script's `scramble`, mirrored — kept honest by [pad matches the build script]. */
    private fun scramble(value: String): String {
        if (value.isEmpty()) return ""
        val pad = KlipyKey.PAD.toByteArray(Charsets.UTF_8)
        val bytes = value.toByteArray(Charsets.UTF_8)
        return Base64.getEncoder()
            .encodeToString(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor pad[i % pad.size].toInt()).toByte() })
    }

    @Test
    fun `round trips a key`() {
        assertEquals(sample, KlipyKey.decode(scramble(sample)))
        // Longer than the pad, so the pad wraps — the case an off-by-one in the modulo breaks.
        assertEquals("x".repeat(64), KlipyKey.decode(scramble("x".repeat(64))))
    }

    @Test
    fun `what ships is not the key in plain text`() {
        // The whole point of scrambling: `strings` over the APK must not turn the key up.
        val shipped = scramble(sample)
        assertFalse(shipped == sample)
        assertFalse(shipped.contains(sample.substring(0, 12)))
    }

    @Test
    fun `a build with no key configured decodes to nothing rather than throwing`() {
        // A fresh clone, and CI's check build, which holds no secrets.
        assertEquals("", KlipyKey.decode(""))
        assertEquals("", KlipyKey.decode("   "))
        assertEquals("", KlipyKey.decode("not base64 at all !!"))
    }

    @Test
    fun `pad matches the build script`() {
        // The two halves of the mechanism, compared. A pad changed on one side only is exactly the
        // failure this file exists for, and it is invisible until somebody's search stops working.
        // Groovy here, not the Kotlin DSL this came from, so the line looked for is Groovy's.
        val script = sequenceOf(File("build.gradle"), File("app/build.gradle"))
            .firstOrNull { it.isFile }
        assertNotNull("app/build.gradle not found from the test's working directory", script)
        assertTrue(
            "the pad in the build script's scrambleKey() no longer matches KlipyKey.PAD",
            script!!.readText().contains("""def pad = '${KlipyKey.PAD}'.getBytes('UTF-8')"""),
        )
    }
}
