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

    /**
     * Searches and thumbnails run on **separate** threads.
     *
     * On one, a queue of nine thumbnail downloads sits in front of the next search, each able to
     * hold the thread for its full timeout — so typing another letter answered a minute later, or
     * never. They are different jobs with different urgency and they get different queues.
     */
    private val searchWork = Executors.newSingleThreadExecutor { r ->
        Thread(r, "light-kb-gif-search").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private val thumbWork = Executors.newFixedThreadPool(THUMB_THREADS) { r ->
        Thread(r, "light-kb-gif-thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** Everything here happens off the keyboard's thread; this is how it gets back. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Decoded thumbnails, by URL. Sized in kilobytes rather than entries, because the whole risk
     * here is a page of large ones arriving at once.
     */
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** URLs already being fetched, so a repaint does not start the same download again. */
    private val inFlight = HashSet<String>()

    /**
     * URLs that failed. Without this, a thumbnail that cannot be fetched is retried on every single
     * draw pass — offline, that is nine downloads per repaint, each holding a thread for its whole
     * timeout. Bounded, and cleared whenever a new result set arrives, so a genuine blip is retried
     * the next time the user searches rather than never.
     */
    private val failedThumbs = HashSet<String>()

    /**
     * The generation a request belongs to, so a result from an abandoned search is dropped.
     *
     * Atomic, and the check and the publish happen together under [lock]. A plain `Int` gave the
     * worker no guarantee of ever seeing an increment from the main thread, and even a fresh read
     * left a window between the check and the write where a newer search could start and have its
     * LOADING state overwritten by the older one's results.
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    private val lock = Any()

    private fun key(): String =
        Prefs.klipyKey(context).ifBlank { KlipyKey.builtIn }

    /** Open on trending, or re-run the last query. */
    fun open() {
        load(query)
    }

    /**
     * Run a search.
     *
     * No debounce, and none is needed: a search happens when the return key is pressed and not
     * before, so every call here is one the user deliberately asked for. This used to fire on every
     * letter, which is what a debounce was hiding — four requests for a five-letter word, three of
     * them abandoned, and the one that mattered queued behind them.
     */
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
        synchronized(inFlight) { failedThumbs.clear() }   // one monitor guards both sets
        announce()
        val mine = generation.incrementAndGet()
        val customer = Prefs.gifCustomerId(context)
        searchWork.execute {
            val outcome = runCatching {
                val api = KlipyApi(k)
                if (text.isBlank()) api.trending(customerId = customer)
                else api.search(text, customerId = customer)
            }
            // A search the user has already moved on from must not overwrite the one they are
            // waiting for. The check and the publish are one step: between them another search can
            // start, and this would otherwise erase its LOADING state with older results.
            synchronized(lock) {
                if (mine != generation.get()) return@execute
                outcome.onSuccess { page ->
                    results = withRecents(text, page.gifs)
                    state = if (results.isEmpty()) State.EMPTY else State.READY
                    message = null
                }.onFailure { e ->
                    results = emptyList()
                    state = State.FAILED
                    message = (e as? app.lightphonekeyboard.api.ApiException)?.reason
                        ?: context.getString(R.string.gif_failed)
                    Log.w(TAG, "gif request failed", e)
                }
            }
            announce()
        }
    }

    /**
     * The GIFs used lately, in front of what came back — but only when nothing was searched for.
     *
     * A picker opens on trending, which is what everybody else does and is right for finding
     * something new. It is not right for the thing most people do most often, which is to send the
     * same half-dozen GIFs again. Under a query they are not shown at all: the user asked for
     * something specific, and answering with what they sent last week is not it.
     */
    private fun withRecents(query: String, fetched: List<Gif>): List<Gif> {
        if (query.isNotBlank()) return fetched
        val recent = recents()
        if (recent.isEmpty()) return fetched
        val seen = recent.mapTo(HashSet()) { it.id }
        return recent + fetched.filterNot { it.id in seen }
    }

    /** Put the page into a failed state from outside — the insert failing is not a search failing. */
    fun failed(reason: String) {
        synchronized(lock) {
            generation.incrementAndGet()   // whatever is in flight no longer owns the page
            results = emptyList()
            state = State.FAILED
            message = reason
        }
        announce()
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
            if (url in failedThumbs) return null
            if (!inFlight.add(url)) return null
        }
        thumbWork.execute {
            val bitmap = fetchFrame(url)
            // Cached BEFORE the in-flight mark is dropped. The other way round, a draw landing in
            // between finds neither a bitmap nor a claim and starts the same download again.
            if (bitmap != null) thumbs.put(url, bitmap)
            synchronized(inFlight) {
                inFlight.remove(url)
                if (bitmap == null && failedThumbs.size < MAX_FAILED) failedThumbs.add(url)
            }
            if (bitmap != null) announce()
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
        main.post { cb() }
    }

    private companion object {
        const val TAG = "GifPanel"
        const val THUMB_CACHE_KB = 6 * 1024
        const val MAX_THUMB_BYTES = 3 * 1024 * 1024
        const val TARGET_PX = 240
        const val RECENTS = 12
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000

        /** Threads fetching thumbnails. Nine cells, so a few at once fill the page noticeably
         *  faster than one at a time without being a burst the phone's radio notices. */
        const val THUMB_THREADS = 3

        const val MAX_FAILED = 256
    }
}
