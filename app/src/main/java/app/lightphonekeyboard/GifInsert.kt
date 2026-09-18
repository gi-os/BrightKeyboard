package app.lightphonekeyboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
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
 * A keyboard cannot hand an app a file. It can only *offer* one, through
 * [InputConnectionCompat.commitContent], and the app on the other side has to have said in advance
 * that it accepts the type — `EditorInfo.contentMimeTypes`. There is no way around that and no
 * second API: an app that declares nothing cannot be given an image by a keyboard, on any version
 * of Android. So the honest design has two outcomes and both are normal:
 *
 *  - the field accepts `image/gif`, and it gets **the image**; or
 *  - it does not, and the image goes on the **clipboard** — and then the keyboard presses paste on
 *    the user's behalf, because "it's on the clipboard, go and paste it" is a chore, not a feature.
 *
 * It used to commit the *link* in the second case. That was wrong twice over: a link is not what
 * anybody picking a GIF asked for, and it quietly turned a picture into a URL in places — a note, a
 * search box — where the URL is no use to anyone either. The clipboard keeps it a picture, and the
 * apps where sending a GIF actually makes sense (the messengers) are exactly the ones that declare
 * the type, so the first outcome is the common one.
 *
 * ## The clip carries an empty string on purpose
 *
 * A clip holding only a `content://` URI is coerced to **text** by any field that cannot take an
 * image — `ClipData.Item.coerceToText` falls back to the URI's own string — so an automatic paste
 * into a plain text box would type `content://app.lightphonekeyboard.gifs/gifs/gif-…` into somebody's
 * message. That is worse than the link this change removed: it means nothing to a reader and the
 * permission behind it expires.
 *
 * So the item is built with an explicit empty text beside the URI. An app that handles images reads
 * the URI and gets the picture; an app that only pastes text coerces to `""` and gets nothing at
 * all, which is the right amount of nothing. `ClipData.newUri` cannot express that, which is why
 * the description and the item are built by hand here.
 *
 * Silently doing nothing is the only outcome that would be wrong, because from the user's side that
 * is a keyboard that ignored a tap.
 *
 * ## The file
 *
 * Downloaded to `cacheDir/gifs` and handed over as a `content://` URI through [FileProvider], with
 * a read grant attached to the commit. It is a cache, deliberately: the receiving app copies what
 * it wants immediately, the directory is capped, and anything the system evicts is re-downloaded
 * the next time it is picked.
 */
object GifInsert {

    /** What the picker did. The caller says so when it was not the first one. */
    enum class Result {
        /** Handed to the field as an image. What should happen, and does wherever the field allows. */
        INSERTED,

        /**
         * The field declared no image support, so the GIF went on the clipboard and a paste was
         * attempted. Whether the paste landed is not knowable — see [copyAndPaste] — so the caller
         * says the clip is there.
         */
        COPIED,

        /** Neither worked. */
        FAILED,
    }

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
        id: String = "",
    ): Result {
        if (url.isBlank()) return Result.FAILED
        // Downloaded before the field is consulted, because both outcomes need the file: one hands
        // it over, the other puts it on the clipboard.
        val file = download(ctx, url) ?: return Result.FAILED
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.gifs", file)
        }.getOrNull() ?: return Result.FAILED

        if (accepts(editor)) {
            val info = InputContentInfoCompat(
                uri,
                ClipDescription(label.ifBlank { "GIF" }, arrayOf(MIME)),
                null,
            )
            // The grant flag is what makes the URI readable in the other process. Without it the
            // receiving app gets a URI it cannot open, which looks exactly like a corrupt file.
            val ok = runCatching {
                InputConnectionCompat.commitContent(
                    ic, editor ?: EditorInfo(), info,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
                )
            }.getOrDefault(false)
            if (ok) return Result.INSERTED
            // Declared the type and then refused it, which happens. The clipboard is still there.
        }
        return if (copyAndPaste(ctx, ic, uri, label)) Result.COPIED else Result.FAILED
    }

    /**
     * Put the GIF on the clipboard as an image, not as a URL, and then press paste.
     *
     * The clip's description says `image/gif` and the item carries the URI, so an app that can paste
     * a picture pastes a picture; the system attaches the read grant to the primary clip, so the
     * receiver can open it with nothing else arranged. The empty text is deliberate — see the note
     * on this file.
     *
     * The paste is a *try*. There is no way to ask an app whether it will accept one and no signal
     * afterwards saying whether it did, so the clip is left on the clipboard either way and the
     * keyboard says it is there. Where the paste worked that message is one line of redundancy;
     * where it did not, it is the only thing standing between the user and a tap that did nothing.
     *
     * This does not pollute the keyboard's own clipboard history: that only records text clips.
     */
    private fun copyAndPaste(ctx: Context, ic: InputConnection, uri: Uri, label: String): Boolean =
        runCatching {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return false
            val clip = ClipData(
                ClipDescription(label.ifBlank { "GIF" }, arrayOf(MIME)),
                ClipData.Item("", null, null, uri),
            )
            cm.setPrimaryClip(clip)
            // Whatever the field makes of it. A rich one inserts the picture; a plain one coerces
            // the empty text and inserts nothing, which is the point of the empty text.
            runCatching { ic.performContextMenuAction(android.R.id.paste) }
            true
        }.getOrElse {
            Log.w(TAG, "could not put the GIF on the clipboard", it)
            false
        }

    /**
     * Tell the provider a GIF was actually used.
     *
     * KLIPY's terms ask for it and their ranking runs on it — a provider that never hears which
     * results were picked ranks worse for everybody. Best-effort by construction: it is fired after
     * the insert has already happened, and a failure here must never read as "the GIF didn't send",
     * because it did.
     */
    fun reportUse(ctx: Context, id: String) {
        if (id.isBlank()) return
        runCatching {
            val key = Prefs.klipyKey(ctx).ifBlank { app.lightphonekeyboard.api.KlipyKey.builtIn }
            if (key.isBlank()) return
            app.lightphonekeyboard.api.KlipyApi(key)
                .registerShare(id, Prefs.gifCustomerId(ctx))
        }
    }

    /** True when the focused field said it takes GIFs. */
    fun accepts(editor: EditorInfo?): Boolean {
        val types = editor?.let { EditorInfoCompat.getContentMimeTypes(it) } ?: return false
        return types.any { ClipDescription.compareMimeTypes(MIME, it) }
    }

    private fun download(ctx: Context, url: String): File? = runCatching {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        // Named from the URL rather than at random, so picking the same GIF twice reuses the file
        // instead of filling the cache with copies of it. A digest rather than String.hashCode:
        // the name is what the reuse check trusts, and two colliding URLs would silently insert
        // the wrong GIF, forever, because the bad file is then cached.
        val file = File(dir, name(url))
        if (file.isFile && file.length() > 0L) {
            // Touched, so reuse counts as use. [prune] drops the oldest first, and a clip on the
            // clipboard may be pasted hours later — a file that kept its original date could be
            // swept away while the clip still points at it, and the paste would open nothing.
            file.setLastModified(System.currentTimeMillis())
            return@runCatching file
        }
        // Pruned only once the reuse check has passed, or the sweep could delete the very file it
        // was about to hand back — and, worse, one whose read grant another app is still holding.
        prune(dir)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            // A temp file per attempt, not per URL. Two downloads of the same GIF — a double tap,
            // or a second finger — would otherwise interleave writes into one file and rename a
            // half-written GIF into place, where the reuse check above accepts it from then on.
            val tmp = File.createTempFile(file.name, ".part", dir)
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
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val sb = StringBuilder("gif-")
        // Half the digest is far more than enough to make a collision not a thing that happens,
        // and it keeps the filename short enough to read in a bug report.
        for (i in 0 until 16) sb.append("%02x".format(digest[i]))
        return sb.append(".gif").toString()
    }

    /**
     * Keep the directory small, oldest first.
     *
     * Not a guarantee for the clipboard: a clip can be pasted long after the file behind it was
     * swept, and the system may clear the whole cache under storage pressure whatever this does.
     * Touching a file on reuse ([download]) makes the ones in play the last to go, which is as far
     * as a cache can be trusted.
     */
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
