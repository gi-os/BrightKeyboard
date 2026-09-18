package app.lightphonekeyboard.api

import app.lightphonekeyboard.text.Gif
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * The GIF library: KLIPY's `api.klipy.com/api/v1/{key}/gifs/…`.
 *
 * ### Why this provider
 *
 * Because it is the one the app everybody's GIF habits were formed on now uses. Google **shut the
 * Tenor API down on 30 June 2026** — announced in January, no new sign-ups from then, and switched
 * off with a few months' notice — which broke the GIF picker in Discord, WhatsApp, X and Bluesky
 * on the same day. Discord moved its default GIF search to KLIPY (running GIPHY alongside it as an
 * experiment), WhatsApp moved to KLIPY as well, and KLIPY deliberately kept a Tenor-shaped surface
 * to make that a one-line change for everybody migrating. So: KLIPY.
 *
 * There is no Tenor path in this file and there should never be one — the endpoint is gone, not
 * deprecated.
 *
 * ### The key
 *
 * Two of them, and the user's wins. A key built into the APK at build time from a repository
 * secret ([app.lightphonekeyboard.api.KlipyKey]) so GIFs work on a fresh install with nothing to
 * set up, and one the user may type in Settings ([app.lightphonekeyboard.Prefs.klipyKey]), stored
 * as an ordinary preference. Neither is in this repository, which is public: a key committed here
 * is a key that gets scraped and rate-limited for everyone. KLIPY's test key allows 100 calls an
 * hour; a production key is a form on their partner panel.
 *
 * The key goes in the *path*, not a header or a query — their design, not ours.
 *
 * ### `customer_id`
 *
 * KLIPY wants a stable per-user id so its own recents and ad fill work. We send the random UUID in
 * [app.lightphonekeyboard.Prefs.gifCustomerId], generated on first use and never derived from
 * anything about the phone or the person: the keyboard keeps its own recents locally, so the id
 * exists to satisfy the API rather than to be useful to us.
 *
 * Plain [HttpURLConnection] + `org.json`, no networking dependency, same as every other client
 * here. Parsing lives in the companion so it is Android-free and unit-tested.
 */
/**
 * Carries the HTTP status, so a picker can say "the service is busy" rather than "something went
 * wrong" — 429 against the shared key is a normal thing to meet, not a fault.
 */
class ApiException(val code: Int, val reason: String) : IOException(reason)

class KlipyApi(private val key: String) {

    /** Trending, which is what the picker shows before anything is typed — same as Discord's. */
    fun trending(page: Int = 1, perPage: Int = PER_PAGE, customerId: String = ""): GifPage =
        parsePage(get("trending", page, perPage, customerId, null))

    /** Search. [query] is passed as typed; KLIPY handles the tokenising and the emoji. */
    fun search(query: String, page: Int = 1, perPage: Int = PER_PAGE, customerId: String = ""): GifPage =
        parsePage(get("search", page, perPage, customerId, query))

    /**
     * Tells KLIPY a GIF was actually sent.
     *
     * Their terms ask for it and their relevance ranking runs on it — a provider that never hears
     * which results were used ranks worse for everyone. Best-effort by construction: the caller
     * fires it after the send has already happened, and a failure here must never surface as
     * "the GIF didn't send", because it did.
     *
     * The exact payload their share endpoint wants is not in the public documentation, so this
     * sends the identifier as a query parameter and ignores whatever comes back. That is the
     * reason the failure handling is "none" rather than a retry: a ping this app cannot verify
     * is not worth a second request, and it is not worth one line of the user's screen either.
     */
    fun registerShare(id: String, customerId: String = "") {
        if (id.isBlank()) return
        val url = URL(
            buildString {
                append(BASE).append('/').append(enc(key)).append("/gifs/share")
                append("?slug=").append(enc(id))
                if (customerId.isNotBlank()) append("&customer_id=").append(enc(customerId))
            },
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // A POST with no body still needs the length said out loud, or HttpURLConnection
            // waits to be written to.
            setFixedLengthStreamingMode(0)
        }
        try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String, page: Int, perPage: Int, customerId: String, query: String?): JSONObject {
        val url = URL(
            buildString {
                append(BASE).append('/').append(enc(key)).append("/gifs/").append(path)
                append("?page=").append(page.coerceAtLeast(1))
                append("&per_page=").append(perPage.coerceIn(MIN_PER_PAGE, MAX_PER_PAGE))
                // Content rating. `g` is the only sane default for a phone that shows the picker
                // to whoever is holding it, and it is what Discord's own default filter does.
                append("&rating=").append(RATING)
                if (!query.isNullOrBlank()) append("&q=").append(enc(query))
                if (customerId.isNotBlank()) append("&customer_id=").append(enc(customerId))
            },
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) throw ApiException(code, reasonFor(code, body))
            return runCatching { JSONObject(body) }.getOrNull()
                ?: throw IOException("The GIF service answered with something that isn't JSON")
        } finally {
            conn.disconnect()
        }
    }

    private fun reasonFor(code: Int, body: String): String = Companion.reasonFor(code, body)

    companion object {

        /**
         * An error worth putting on a 360px screen. The code alone is not one, and for 403 the code
         * is actively misleading.
         *
         * KLIPY sits behind Cloudflare, which answers **403 with its own page** when it does not
         * like the client — "Error 1010: Access denied … based on your browser's signature". That is
         * the same status the API uses for a key it will not accept, so reading the status alone
         * tells somebody with a perfectly good key to go and replace it. Observed: a request from a
         * datacentre IP gets 1010 for a real key, a made-up key and no key at all, identically.
         *
         * So the body is read, and only for that one distinction. Everything else here is the
         * status, because everything else is unambiguous.
         */
        fun reasonFor(code: Int, body: String = ""): String = when {
            code == 403 && blockedByEdge(body) ->
                "The GIF service is blocking this connection, not the key"
            code == 401 || code == 403 -> "The GIF service refused that key"
            code == 404 -> "That GIF service has no search endpoint"
            // The shipped key's allowance is shared by every install, so this is a normal thing to
            // meet rather than an error — and it names the way out, since the way out is a setting.
            code == 429 -> "GIF search is busy — try shortly, or add your own key in Settings"
            code in 500..599 -> "The GIF service is having trouble"
            else -> "The GIF service said $code"
        }

        /** Cloudflare's own refusal rather than the API's. Matched on its wording, not on a status. */
        private fun blockedByEdge(body: String): Boolean {
            if (body.isBlank()) return false
            val lower = body.lowercase()
            return "cloudflare" in lower || "error 1010" in lower || "attention required" in lower
        }

        private const val BASE = "https://api.klipy.com/api/v1"

        /** Where to get a key. Shown in Settings, so it lives beside the endpoint it belongs to. */
        const val KEY_SOURCE = "klipy.com/developers"

        /**
         * Their own placeholder wording, which their branding guidelines ask for. [SEARCH_HINT] is
         * what the strip shows while a GIF search is running and [ATTRIBUTION] is the line on the
         * settings screen. Kept here rather than typed into either, so neither can drift apart from
         * the provider this file talks to.
         */
        const val SEARCH_HINT = "Search KLIPY"
        const val ATTRIBUTION = "Powered by KLIPY"

        /** A page of results. 24 is their default; the grid is two columns, so this is a dozen
         *  rows — enough to scroll before the next page is needed and small enough to arrive
         *  quickly over the phone's connection. */
        private const val PER_PAGE = 24
        private const val MIN_PER_PAGE = 8
        private const val MAX_PER_PAGE = 50

        private const val RATING = "g"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        /**
         * Reads a page of results out of whatever the service answered with.
         *
         * **Deliberately tolerant about shape**, which is a decision rather than laziness. KLIPY
         * documents its native envelope as `{"result": true, "data": {"data": [...],
         * "current_page": 1, "has_next": true}}`, and *also* serves a Tenor-compatible surface
         * (`{"results": [...], "next": "…"}`) precisely so the apps stranded by Tenor's shutdown
         * could point at it and carry on. Which of those a given key gets is not something this
         * phone can decide, and the failure mode of guessing wrong is an empty grid with no
         * explanation. So both are read, and an item is taken to be a GIF if a `.gif` URL can be
         * found inside it.
         *
         * The renditions are found by *walking* the item rather than by naming a field, for the
         * same reason: KLIPY nests them as `file.{hd,md,sm,xs}.gif.url` and the Tenor-shaped
         * answer nests them as `media_formats.{gif,tinygif,nanogif}.url`. [rank] is what picks a
         * size out of whatever came back, and it is the only place that knows what those names
         * mean.
         */
        fun parsePage(root: JSONObject): GifPage {
            val data = root.optJSONObject("data")
            val items = data?.optJSONArray("data")
                ?: root.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: data?.optJSONArray("items")
                ?: JSONArray()
            val gifs = ArrayList<Gif>(items.length())
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                parseItem(item)?.let { gifs.add(it) }
            }
            // `has_next` when it is there; a full page when it isn't (the Tenor-shaped answer says
            // it with a cursor we don't page by). A wrong "yes" costs one empty fetch, a wrong
            // "no" hides every result past the first page, so the fallback errs towards yes.
            val hasNext = when {
                data?.has("has_next") == true -> data.optBoolean("has_next")
                root.has("has_next") -> root.optBoolean("has_next")
                root.optString("next").isNotBlank() -> true
                else -> gifs.isNotEmpty()
            }
            return GifPage(gifs, hasNext)
        }

        /** One result, or null when nothing in it is a GIF we could draw and send (an ad slot,
         *  a sticker-only entry, a rendition set with no `.gif` in it at all). */
        private fun parseItem(item: JSONObject): Gif? {
            val id = item.string("id") ?: item.string("slug") ?: return null
            val renditions = walk(item)
            if (renditions.isEmpty()) return null
            val send = renditions.minByOrNull { rank(it.path, forSending = true) } ?: return null
            val preview = renditions.minByOrNull { rank(it.path, forSending = false) } ?: send
            return Gif(
                id = id,
                title = item.string("title") ?: item.string("slug").orEmpty(),
                previewUrl = preview.url,
                sendUrl = send.url,
                width = send.width,
                height = send.height,
            )
        }

        /** A rendition found somewhere inside a result: the URL, its size, and the field names it
         *  was found under (which is the only clue to how big it is). */
        private class Rendition(val path: String, val url: String, val width: Int, val height: Int)

        /**
         * Every `.gif` URL inside [item], with the path it was found at.
         *
         * Recursive, depth-limited, and it only accepts a node that actually carries a `url`
         * string ending in `.gif` — which is what keeps mp4 and webp renditions out. Sending an
         * mp4 would arrive as a video attachment and sending a webp would arrive as a file
         * Messages draws as a grey box, and both look like this app being broken rather than like
         * a format mismatch.
         */
        private fun walk(item: JSONObject): List<Rendition> {
            val out = ArrayList<Rendition>(6)
            fun visit(node: JSONObject, path: String, depth: Int) {
                if (depth > MAX_DEPTH) return
                val url = node.string("url") ?: node.string("gif")
                if (url != null && url.substringBefore('?').endsWith(".gif", ignoreCase = true)) {
                    out.add(Rendition(path, url, node.dimension("width", 0), node.dimension("height", 1)))
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    node.optJSONObject(key)?.let { visit(it, if (path.isEmpty()) key else "$path.$key", depth + 1) }
                }
            }
            visit(item, "", 0)
            return out
        }

        /** The rendition sizes both providers name, smallest first. KLIPY sizes as `hd/md/sm/xs`,
         *  the Tenor-shaped answer as `gif/mediumgif/tinygif/nanogif`; [FULL] is a bare `gif`,
         *  which is the full-size one in both vocabularies. [UNKNOWN] is a path that matches
         *  nothing — still usable, since a GIF of unknown size beats an empty cell. */
        private enum class Size { XS, SM, MD, FULL, HD, UNKNOWN }

        /**
         * What to send, in order of preference.
         *
         * **Medium first.** A phone tunnelling to a Mac over Tailscale is uploading this, and an HD
         * GIF is routinely eight megabytes that iMessage re-encodes anyway; a medium one is a
         * second or two and looks the same in a message.
         */
        private val SEND_ORDER = listOf(Size.MD, Size.FULL, Size.SM, Size.HD, Size.XS, Size.UNKNOWN)

        /**
         * What to draw in the grid, in order of preference.
         *
         * **Not the smallest**, which is what this used to take and what made the results look
         * soft. The panel is 1080px across a hair under four inches, so a cell in this keyboard's
         * three-column grid is roughly 270 device pixels wide — and an `xs`/`nanogif` rendition is
         * commonly 120px, i.e. upscaled more than twice before anybody sees it. `sm`/`tinygif`
         * (~220px) is the honest floor and `md` is better than either, so `md` is taken when there
         * is no `sm` rather than falling back down to `xs`.
         *
         * It stops short of preferring `md` outright: a page is nine cells, and at medium that is
         * megabytes of download per page turn over this phone's connection. If the grid ever wants
         * to be sharper still, move [Size.MD] to the front of this list — that is the whole change.
         */
        private val PREVIEW_ORDER = listOf(Size.SM, Size.MD, Size.FULL, Size.XS, Size.HD, Size.UNKNOWN)

        /**
         * How much we want a rendition, lower being better: its position in the order for the job.
         *
         * An explicit order per purpose rather than arithmetic on a size number, which is what was
         * here first. The arithmetic read as clever and produced *ties* — two sizes equally far
         * from the target — and a tie is broken by whichever key the service happened to serialise
         * first, so which rendition the grid drew was effectively decided by JSON key order.
         */
        private fun rank(path: String, forSending: Boolean): Int {
            val order = if (forSending) SEND_ORDER else PREVIEW_ORDER
            return order.indexOf(sizeOf(path))
        }

        /** Which size a rendition's path names. Substrings, because the path is a trail of field
         *  names (`file.md.gif`, `media_formats.tinygif`) rather than one label. */
        private fun sizeOf(path: String): Size {
            val p = path.lowercase()
            return when {
                p.contains("nano") || p.contains("xs") -> Size.XS
                p.contains("tiny") || p.contains("sm") || p.contains("preview") -> Size.SM
                p.contains("medium") || p.contains(".md") || p.endsWith("md") -> Size.MD
                p.contains("hd") || p.contains("large") -> Size.HD
                p == "gif" || p.endsWith(".gif") -> Size.FULL
                else -> Size.UNKNOWN
            }
        }

        private const val MAX_DEPTH = 4

        /** Reads a string field, treating org.json's literal `"null"` as absent — the same trap
         *  `BlueBubblesApi.string` exists for, and the reason chats were once titled "null". */
        private fun JSONObject.string(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

        /**
         * A dimension, from either a pair of fields or a `dims` array.
         *
         * `{"width": 480, "height": 270}` is KLIPY's; `"dims": [480, 270]` is the Tenor-shaped
         * one, and it is positional — hence [index] rather than a second lookup by name.
         */
        private fun JSONObject.dimension(name: String, index: Int): Int {
            optInt(name, 0).takeIf { it > 0 }?.let { return it }
            return optJSONArray("dims")?.optInt(index, 0) ?: 0
        }

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

/** A page of GIFs and whether there is another one behind it. */
data class GifPage(val gifs: List<Gif>, val hasNext: Boolean)
