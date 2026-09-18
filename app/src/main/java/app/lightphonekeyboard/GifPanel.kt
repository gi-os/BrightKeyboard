package app.lightphonekeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import app.lightphonekeyboard.api.KlipyApi
import app.lightphonekeyboard.api.KlipyKey
import app.lightphonekeyboard.text.Gif
import app.lightphonekeyboard.text.GifJson
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * What the GIF page is currently showing, and the thumbnails it draws.
 *
 * Everything here is off the keyboard's thread. A search is a network round trip and a thumbnail is
 * a download and a decode; doing either on the thread that draws keys would freeze typing in every
 * app on the phone, which is the one thing an IME must never do.
 *
 * ## They move
 *
 * A still frame is a poor way to choose a GIF — half of them are a still of nothing. So the grid
 * animates, through `AnimatedImageDrawable`, which needs API 28 against this app's minimum of 26;
 * below that, and whenever a decode fails, the first frame is drawn instead and everything still
 * works. Only the page on screen is ever running, and leaving the page stops all of it, because a
 * dozen animations ticking behind a keyboard is exactly the kind of thing a Light Phone exists not
 * to do.
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
     *
     * Still the fallback even when animation works: it is what a cell shows while the animated
     * decode is still running, so a page fills in rather than staying empty.
     */
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /**
     * Running animations, by URL. Counted rather than weighed: an [android.graphics.drawable.
     * AnimatedImageDrawable] does not report its size, and the real bound is how many can sensibly
     * be on screen at once.
     */
    private val movies = object : LruCache<String, Drawable>(MOVIE_CACHE) {
        override fun entryRemoved(evicted: Boolean, key: String, old: Drawable, new: Drawable?) {
            // Posted, never called here. An eviction happens inside put(), which runs on the decode
            // thread, and stop() touches the same native object the UI thread may be inside draw()
            // on. A native race files no crash report — it takes the process with it silently.
            main.post { stop(old) }
        }
    }

    /** URLs whose animated decode failed or is unsupported; the still frame serves them for good. */
    private val notAnimatable = HashSet<String>()

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
        synchronized(inFlight) {
            // Both failure sets, and both bounded. Cleared on every new search so a blip demotes a
            // GIF to a still frame for one search rather than for the life of the process.
            failedThumbs.clear()
            notAnimatable.clear()
        }
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
        val star = starred()
        val starIds = star.mapTo(HashSet()) { it.id }
        val mine = star + recents().filterNot { it.id in starIds }
        if (mine.isEmpty()) return fetched
        val seen = mine.mapTo(HashSet()) { it.id }
        return mine + fetched.filterNot { it.id in seen }
    }

    /**
     * Only the starred ones, for the filter on the page. **No network at all**, which is the part
     * worth having on a keyboard: it is the one view that works with the radio off.
     */
    fun showStarredOnly() {
        synchronized(lock) {
            generation.incrementAndGet()   // whatever is in flight no longer owns the page
            covered = Covered(query, results, state, message)
            query = ""
            results = starred()
            state = if (results.isEmpty()) State.EMPTY else State.READY
            message = null
        }
        announce()
    }

    /**
     * Put back whatever the starred filter covered.
     *
     * Not a fresh trending fetch, which is what this used to do: switching the filter on and off
     * threw away the search the user was looking at, and cost a request to do it. Falls back to
     * loading when there is nothing remembered, which is only the case before anything has loaded.
     */
    fun restore() {
        val was = synchronized(lock) { covered.also { covered = null } }
        if (was == null) { open(); return }
        synchronized(lock) {
            generation.incrementAndGet()
            query = was.query
            results = was.results
            state = was.state
            message = was.message
        }
        announce()
    }

    private class Covered(
        val query: String,
        val results: List<Gif>,
        val state: State,
        val message: String?,
    )

    private var covered: Covered? = null

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
        ensureLoaded(url)
        return null
    }

    /**
     * The running animation for [url], or null when there is not one to draw yet.
     *
     * Starts the load on a miss and returns null, exactly like [thumbnail] — both are called from
     * the draw pass, and the repaint when it lands is what puts the result on screen. Until then the
     * cell draws the still frame, so a page fills in rather than sitting blank.
     *
     * The caller owns starting it and giving it somewhere to invalidate; a drawable with no callback
     * cannot schedule its own next frame. See `LightKeyboardView.drawGif`.
     */
    fun animation(url: String): Drawable? {
        if (url.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        movies.get(url)?.let { return it }
        ensureLoaded(url)
        return null
    }

    /**
     * Fetch [url] once and fill both caches from the same bytes.
     *
     * **One download per GIF.** [thumbnail] and [animation] are asked about the same cell in the
     * same draw pass, and when each fetched for itself every GIF on the page was downloaded twice,
     * at up to 3 MB a time, on a page whose whole argument is that it is frugal.
     */
    private fun ensureLoaded(url: String) {
        synchronized(inFlight) {
            if (url in failedThumbs && url in notAnimatable) return
            if (!inFlight.add(url)) return
        }
        thumbWork.execute {
            val bytes = fetchBytes(url)
            val movie = bytes?.let { decodeAnimation(it) }
            val still = bytes?.let { decodeFrame(it) }
            // Cached BEFORE the in-flight mark is dropped. The other way round, a draw landing in
            // between finds neither a result nor a claim and starts the same download again.
            if (movie != null) movies.put(url, movie)
            if (still != null) thumbs.put(url, still)
            synchronized(inFlight) {
                inFlight.remove(url)
                if (movie == null && notAnimatable.size < MAX_FAILED) notAnimatable.add(url)
                if (still == null && failedThumbs.size < MAX_FAILED) failedThumbs.add(url)
            }
            if (movie != null || still != null) announce()
        }
    }

    private fun decodeAnimation(bytes: ByteArray): Drawable? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        // A GIF big enough to be worth worrying about is drawn as a still instead. Every frame of a
        // running animation is held in memory, and a cache bounded by entry count cannot see that.
        if (bytes.size > MAX_ANIMATED_BYTES) return null
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            // Sampled down on the way in, like the still frame: a preview rendition is bigger than a
            // cell, and every frame of it is held in memory while it runs.
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > TARGET_PX) decoder.setTargetSampleSize(sampleFor(longest))
            decoder.isMutableRequired = false
        }
        drawable as? AnimatedImageDrawable
    }.getOrElse {
        Log.w(TAG, "could not decode an animation", it)
        null
    }

    /** Stop everything that is running. Called when the page is left; nothing animates off-screen. */
    fun stopAnimations() {
        movies.snapshot().values.forEach { stop(it) }
    }

    private fun stop(d: Drawable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) {
            runCatching { d.stop() }
            d.callback = null
        }
    }

    private fun sampleFor(longest: Int): Int {
        var sample = 1
        while (longest / sample > TARGET_PX) sample *= 2
        return sample
    }

    /** The bytes of [url], capped. Shared by the still frame and the animation. */
    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readBytes(MAX_THUMB_BYTES) }.takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        Log.w(TAG, "could not fetch a GIF", it)
        null
    }

    /** The first frame, scaled down to something a cell can use. */
    private fun decodeFrame(bytes: ByteArray): Bitmap? = runCatching {
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
        Log.w(TAG, "could not decode a still frame", it)
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

    /** The starred ones, newest star first. Never evicted by use — only by being unstarred. */
    fun starred(): List<Gif> = GifJson.decode(Prefs.starredGifs(context))

    /** Star or unstar [gif]. Returns true when it is now starred. */
    fun toggleStar(gif: Gif): Boolean {
        val current = starred()
        val had = current.any { it.id == gif.id }
        val next = if (had) current.filter { it.id != gif.id } else (listOf(gif) + current).take(STARRED)
        Prefs.setStarredGifs(context, GifJson.encode(next))
        return !had
    }

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

        /** A GIF above this is drawn as a still. Every frame of a running one is held in memory,
         *  and a cache bounded by entry count cannot see that. */
        const val MAX_ANIMATED_BYTES = 1_500_000
        const val TARGET_PX = 240
        const val RECENTS = 12

        /** Stars kept. A ceiling rather than a policy: nothing here may be unbounded. */
        const val STARRED = 60

        /** Animations held at once. A page's worth and a little, so a page turn back is instant. */
        const val MOVIE_CACHE = 12
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000

        /** Threads fetching thumbnails. Nine cells, so a few at once fill the page noticeably
         *  faster than one at a time without being a burst the phone's radio notices. */
        const val THUMB_THREADS = 3

        const val MAX_FAILED = 256
    }
}
