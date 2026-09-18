package app.lightphonekeyboard.text

import org.json.JSONArray
import org.json.JSONObject

/**
 * One GIF, as this app needs it.
 *
 * Deliberately not the provider's object. A search result from KLIPY carries a dozen renditions,
 * ad slots and analytics ids; what a keyboard needs is a small one to draw in the grid, a bigger
 * one to insert, and enough identity to recognise the same GIF again in the recents. Everything
 * else is dropped at the parse boundary
 * ([app.lightphonekeyboard.api.KlipyApi.Companion.parsePage]) so no other part of the app has to know
 * what shape the provider answers in — which matters more than usual here, since the last provider
 * this feature would have used was switched off mid-year (see KlipyApi).
 *
 * [id] is the provider's own id or slug. It is what a favourite is keyed by, so it has to survive
 * a round trip through storage — and it is also what the share ping is reported against.
 *
 * [sendUrl] and [previewUrl] are both `.gif` URLs, never mp4 or webp: the file is handed to
 * whatever app the keyboard is typing into, and `image/gif` is the type those apps declare they
 * accept. See `SwipeEncoder`'s sibling, `GifInsert`, for how it gets there.
 */
data class Gif(
    val id: String,
    val title: String,
    val previewUrl: String,
    val sendUrl: String,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** Aspect ratio, or 1 when the provider didn't say. Clamped because a banner-shaped GIF makes
     *  a row of slivers. The keyboard's grid centre-crops rather than fitting, so this is advisory
     *  there; it is the provider's own shape, kept for anything that wants to lay one out. */
    val ratio: Float
        get() = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 2f) else 1f

    /** What to call it when there is nothing better — a filename, and the line under a cell. */
    val label: String get() = title.ifBlank { "GIF" }
}

/**
 * The favourites and recents list, as JSON.
 *
 * JSON rather than the newline-joined form the clipboard and the word list use: a GIF carries a
 * provider-written *title*, and a delimiter trick that is safe for words chosen from a dictionary
 * is not safe for a string somebody else chose. Free of Android so it has a test rather than a
 * phone.
 *
 * Decoding is total: an unreadable blob, a missing field or a stored entry from an older build
 * yields the entries that do parse and drops the rest. A saved GIF list is a convenience, and
 * losing one entry must never be the reason the picker won't open.
 */
object GifJson {

    fun encode(gifs: List<Gif>): String {
        val array = JSONArray()
        for (gif in gifs) {
            array.put(
                JSONObject()
                    .put("id", gif.id)
                    .put("title", gif.title)
                    .put("preview", gif.previewUrl)
                    .put("send", gif.sendUrl)
                    .put("w", gif.width)
                    .put("h", gif.height),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<Gif> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Gif>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val send = obj.optString("send").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val preview = obj.optString("preview").takeIf { it.isNotBlank() && it != "null" } ?: send
            out.add(
                Gif(
                    id = id,
                    title = obj.optString("title").takeIf { it != "null" }.orEmpty(),
                    previewUrl = preview,
                    sendUrl = send,
                    width = obj.optInt("w", 0),
                    height = obj.optInt("h", 0),
                ),
            )
        }
        return out
    }
}
