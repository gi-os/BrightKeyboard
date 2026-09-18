package app.lightphonekeyboard.api

import app.lightphonekeyboard.BuildConfig
import java.util.Base64

/**
 * The GIF search key the app ships with.
 *
 * It exists so GIFs work on a fresh install with nothing to set up — the same bargain every other
 * messenger makes, where the GIF button is simply there. It is built in from a repository secret
 * rather than committed (see `app/build.gradle`), and stored scrambled rather than as a
 * readable string.
 *
 * ### What the scrambling is and isn't
 *
 * XOR against a fixed pad, Base64 on top. It defeats `strings` over the APK and the scrapers that
 * walk public repositories looking for things shaped like keys, which at this scale is the whole of
 * the threat. It does not defeat anybody willing to decompile: the pad is in the same binary, and
 * it always will be, in every app that ships a key. Saying otherwise would be the kind of comment
 * that makes the next person trust this more than they should.
 *
 * ### Why a key can still be needed in Settings
 *
 * The shipped key's allowance is **per key, not per install** — every phone running this app draws
 * on the same one. So it is a starter, not a guarantee: when it is spent the service answers 429,
 * the picker says the search is busy, and anyone who wants their own ceiling can put their own key
 * in Settings → GIFs, which takes precedence ([app.lightphonekeyboard.Prefs.klipyKey]). Nothing else depends
 * on this.
 */
object KlipyKey {

    /** Must match `scrambleKey`'s pad in `app/build.gradle` — the two are one mechanism split
     *  across a build script and a runtime, and changing one alone yields a key that decodes to
     *  rubbish and a 401 nobody can explain. */
    internal const val PAD = "brightkeyboard"

    /** Decoded once: this is read on every settings read, and the work is pointless twice. */
    val builtIn: String by lazy { decode(BuildConfig.KLIPY_KEY) }

    internal fun decode(scrambled: String): String {
        if (scrambled.isBlank()) return ""
        return runCatching {
            val bytes = Base64.getDecoder().decode(scrambled)
            val pad = PAD.toByteArray(Charsets.UTF_8)
            String(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor pad[i % pad.size].toInt()).toByte() }, Charsets.UTF_8)
        }.getOrDefault("")
        // A build with no key configured, or a mangled one, decodes to blank — which is the honest
        // "no search" state the app already knows how to be in, rather than a crash on first open.
    }
}
