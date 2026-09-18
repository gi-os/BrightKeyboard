package app.lightphonekeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import app.lightphonekeyboard.api.KlipyApi
import app.lightphonekeyboard.api.KlipyKey
import app.lightphonekeyboard.text.Gif
import app.lightphonekeyboard.text.GifJson
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * What the GIF page is currently showing, and the thumbnails it draws.
 *
 * Everything here is off the keyboard's thread. A search is a network round trip and a thumbnail is
 * a download and a decode; doing either on the thread that draws keys would freeze typing in every
 * app on the phone, which is the one thing an IME must never do.
 *
 * ## Only the first frame
 *
 * A grid of animated GIFs is what every other keyboard shows and it is not what this one shows.
 * `AnimatedImageDrawable` needs API 28 against this app's 26, it wants a callback-driven redraw on
 * a view that otherwise repaints only when a finger moves, and six of them running at once on this
 * phone is a measurable amount of the battery a Light Phone exists to save. The first frame is what
 * you pick from; the file that gets inserted is the whole animation.
 *
 * ## Why the state is this plain
 *
 * One page at a time, no scrolling, an explicit next and previous. Same reasoning as the clipboard
 * page: the emoji grid's scroll already shares a finger with swipe typing and swipe-to-dismiss, and
 * a third gesture reading the same touches is where that stops being predictable.
 */
class GifPanel(private val context: Context) {

    enum class State { IDLE, LOADING, READY, EMPTY, FAILED, NO_KEY }

    @Volatile
    var state: State = State.IDLE
        private set

    /** Why it failed, in words that fit a keyboard. Null unless [state] is [State.FAILED]. */
    @Volatile
    var message: String? = null
        private set

    @Volatile
    var results: List<Gif> = emptyList()
        private set

    /** The query the results belong to. Blank means trending. */
    @Volatile
    var query: String = ""
        private set

    /** Called on the UI thread whenever anything above changed and the page should repaint. */
    var onChanged: (() -> Unit)? = null

    private val work = Executors.newSingleThreadExecutor { r ->
        Thread(r, "light-kb-gifs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /**
     * Decoded thumbnails, by URL. Sized in kilobytes rather than entries, because the whole risk
     * here is a page of large ones arriving at once.
     */
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** URLs already being fetched, so a repaint does not start the same download again. */
    private val inFlight = HashSet<String>()

    /** The generation a request belongs to. A result from an abandoned search is dropped. */
    private var generation = 0

    private fun key(): String =
        Prefs.klipyKey(context).ifBlank { KlipyKey.builtIn }

    /** Open on trending, or re-run the last query. */
    fun open() {
        load(query)
    }

    fun search(text: String) {
        load(text)
    }

    private fun load(text: String) {
        val k = key()
        query = text
        if (k.isBlank()) {
            state = State.NO_KEY
            results = emptyList()
            announce()
            return
        }
        state = State.LOADING
        results = emptyList()
        message = null
        announce()
        val mine = ++generation
        val customer = Prefs.gifCustomerId(context)
        work.execute {
            val outcome = runCatching {
                val api = KlipyApi(k)
                if (text.isBlank()) api.trending(customerId = customer)
                else api.search(text, customerId = customer)
            }
            // A search the user has already moved on from must not overwrite the one they are
            // waiting for. Checked here rather than at the call site because the call site has
            // already returned.
            if (mine != generation) return@execute
            outcome.onSuccess { page ->
                results = page.gifs
                state = if (page.gifs.isEmpty()) State.EMPTY else State.READY
                message = null
            }.onFailure { e ->
                results = emptyList()
                state = State.FAILED
                message = (e as? app.lightphonekeyboard.api.ApiException)?.reason
                    ?: "Couldn't reach the GIF service"
                Log.w(TAG, "gif request failed", e)
            }
            announce()
        }
    }

    /**
     * The thumbnail for [url], or null when it is not here yet.
     *
     * Starts the fetch on a miss and returns null, rather than blocking — this is called from the
     * draw pass. The repaint when it lands is what puts it on screen.
     */
    fun thumbnail(url: String): Bitmap? {
        if (url.isBlank()) return null
        thumbs.get(url)?.let { return it }
        synchronized(inFlight) {
            if (!inFlight.add(url)) return null
        }
        work.execute {
            val bitmap = fetchFrame(url)
            synchronized(inFlight) { inFlight.remove(url) }
            if (bitmap != null) {
                thumbs.put(url, bitmap)
                announce()
            }
        }
        return null
    }

    /** The first frame of the GIF at [url], scaled down to something a cell can use. */
    private fun fetchFrame(url: String): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val bytes = try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readBytes(MAX_THUMB_BYTES) }
        } finally {
            conn.disconnect()
        }
        if (bytes.isEmpty()) return@runCatching null
        // Measured first, then decoded at a sample size: a preview rendition is still bigger than
        // a cell on this screen, and decoding it at full size to draw it at a sixth is most of the
        // memory this class would ever use.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > TARGET_PX * 2 || bounds.outHeight / sample > TARGET_PX * 2) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565   // no alpha to keep, half the bytes
            },
        )
    }.getOrElse {
        Log.w(TAG, "could not fetch a thumbnail", it)
        null
    }

    /** Read at most [limit] bytes, so a hostile or mistaken answer cannot be unbounded. */
    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(1 shl 15)
        val buf = ByteArray(1 shl 14)
        while (out.size() < limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ recents

    fun recents(): List<Gif> = GifJson.decode(Prefs.recentGifs(context))

    /** Remember [gif] as the newest recent, moving it rather than repeating it. */
    fun remember(gif: Gif) {
        val kept = (listOf(gif) + recents().filter { it.id != gif.id }).take(RECENTS)
        Prefs.setRecentGifs(context, GifJson.encode(kept))
    }

    private fun announce() {
        val cb = onChanged ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { cb() }
    }

    private companion object {
        const val TAG = "GifPanel"
        const val THUMB_CACHE_KB = 6 * 1024
        const val MAX_THUMB_BYTES = 3 * 1024 * 1024
        const val TARGET_PX = 240
        const val RECENTS = 12
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
