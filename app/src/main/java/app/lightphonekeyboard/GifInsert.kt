package app.lightphonekeyboard

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Puts a GIF into whatever field the keyboard is typing into.
 *
 * ## Why this is not just "commit the image"
 *
 * A keyboard cannot hand an app a file. It can only offer one through
 * [InputConnectionCompat.commitContent], and the app on the other side has to have said in advance
 * that it accepts the type — `EditorInfo.contentMimeTypes`. Most apps say nothing, because most
 * fields are for text. So the honest design has two outcomes and both are normal:
 *
 *  - the field accepts `image/gif`, and it gets the file; or
 *  - it does not, and it gets the **link** instead, which is what every other keyboard does and
 *    what the receiving app will usually unfurl into the same GIF anyway.
 *
 * Silently doing nothing when a field declines is the one outcome that would be wrong, because from
 * the user's side that is a keyboard that ignored a tap.
 *
 * ## The file
 *
 * Downloaded to `cacheDir/gifs` and handed over as a `content://` URI through [FileProvider], with
 * a read grant attached to the commit. It is a cache, deliberately: the receiving app copies what
 * it wants immediately, the directory is capped, and anything the system evicts is re-downloaded
 * the next time it is picked.
 */
object GifInsert {

    /** What the picker did, so the caller can tell the user when it was not what they expected. */
    enum class Result { FILE, LINK, FAILED }

    /**
     * Insert [gif] into [ic]. Runs on a background thread — it downloads — so the caller hands it a
     * completion to marshal back.
     */
    fun insert(
        ctx: Context,
        ic: InputConnection,
        editor: EditorInfo?,
        url: String,
        label: String,
    ): Result {
        if (url.isBlank()) return Result.FAILED
        if (!accepts(editor)) return if (commitLink(ic, url)) Result.LINK else Result.FAILED
        val file = download(ctx, url) ?: return if (commitLink(ic, url)) Result.LINK else Result.FAILED
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.gifs", file)
        }.getOrNull() ?: return if (commitLink(ic, url)) Result.LINK else Result.FAILED

        val info = InputContentInfoCompat(
            uri,
            ClipDescription(label.ifBlank { "GIF" }, arrayOf(MIME)),
            null,
        )
        // The grant flag is what makes the URI readable in the other process. Without it the
        // receiving app gets a URI it cannot open, which looks exactly like a corrupt file.
        val ok = runCatching {
            InputConnectionCompat.commitContent(
                ic, editor ?: EditorInfo(), info, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
            )
        }.getOrDefault(false)
        return if (ok) Result.FILE else if (commitLink(ic, url)) Result.LINK else Result.FAILED
    }

    /** True when the focused field said it takes GIFs. */
    fun accepts(editor: EditorInfo?): Boolean {
        val types = editor?.let { EditorInfoCompat.getContentMimeTypes(it) } ?: return false
        return types.any { ClipDescription.compareMimeTypes(MIME, it) }
    }

    private fun commitLink(ic: InputConnection, url: String): Boolean =
        runCatching { ic.commitText(url, 1) }.getOrDefault(false)

    private fun download(ctx: Context, url: String): File? = runCatching {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        prune(dir)
        // Named from the URL rather than at random, so picking the same GIF twice reuses the file
        // instead of filling the cache with copies of it.
        val file = File(dir, name(url))
        if (file.isFile && file.length() > 0L) return@runCatching file
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val tmp = File(dir, "${file.name}.part")
            var total = 0L
            tmp.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 14)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        // A GIF this big is not going into a message; it is a mistake or a hostile
                        // answer, and writing it would fill the cache partition.
                        if (total > MAX_BYTES) { tmp.delete(); return@runCatching null }
                        out.write(buf, 0, n)
                    }
                }
            }
            // Renamed into place only once it is whole: a part file that exists, opens, and draws
            // half a GIF is worse than one that was never there.
            if (tmp.renameTo(file)) file else { tmp.delete(); null }
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        Log.w(TAG, "could not fetch the GIF", it)
        null
    }

    private fun name(url: String): String {
        val hash = url.hashCode().toLong() and 0xFFFFFFFFL
        return "gif-$hash.gif"
    }

    /** Keep the directory small. Oldest first, because the newest is the one about to be reused. */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (f in files) {
            if (bytes <= MAX_CACHE_BYTES) break
            bytes -= f.length()
            f.delete()
        }
    }

    private const val TAG = "GifInsert"
    private const val MIME = "image/gif"
    private const val DIR = "gifs"
    private const val MAX_BYTES = 12L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 40L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
}
