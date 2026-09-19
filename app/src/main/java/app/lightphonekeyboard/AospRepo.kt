package app.lightphonekeyboard

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The dictionary catalogue at codeberg.org/Helium314/aosp-dictionaries, read live.
 *
 * A hundred-odd languages, published as AOSP `.combined` word lists, which is where every keyboard in
 * this family gets its dictionaries. Nothing is bundled and nothing is rehosted: the phone asks that
 * project what it has, and fetches the one the user picks straight from it. That is also the honest
 * answer to the licensing question — those lists come from many sources under several licenses, and
 * passing them on inside somebody else's release is not this project's call to make.
 *
 * The listing is one small JSON request, made when the language screen is opened and not before.
 */
object AospRepo {

    private const val API =
        "https://codeberg.org/api/v1/repos/Helium314/aosp-dictionaries/contents/wordlists"
    private const val RAW =
        "https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/wordlists"

    /** A word list somebody could install. [bytes] is the download size, before any of it is parsed. */
    class Item(val code: String, val name: String, val file: String, val bytes: Long) {
        val url: String get() = "$RAW/$file"
    }

    /** Big enough to hold any real word list, small enough that a wrong URL cannot fill the phone. */
    const val MAX_BYTES = 40L * 1024 * 1024

    /**
     * Ask the repository what it publishes. Blocking; call it off the main thread.
     *
     * Only `main_*` lists: the `emoji_*` ones in the same folder are a different thing, mapping words
     * to emoji, and installing one as a dictionary would fill the suggestion strip with nonsense.
     */
    fun list(): List<Item> {
        val text = fetch(API) ?: return emptyList()
        val out = ArrayList<Item>()
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val file = o.optString("name")
            if (!file.startsWith("main_") || !file.endsWith(".combined")) continue
            val code = file.removePrefix("main_").removeSuffix(".combined")
            out.add(Item(code, displayName(code), file, o.optLong("size")))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * A readable name for a locale code. [Locale] knows most of them and knows them in the reader's
     * own language, which beats a table this project would have to keep up to date. Codes the platform
     * has never heard of keep their code, which is at least not a wrong name.
     */
    fun displayName(code: String): String {
        val base = code.substringBefore('_')
        val name = Locale.forLanguageTag(base.replace('_', '-')).displayLanguage
        val label = if (name.isBlank() || name.equals(base, ignoreCase = true)) code else name
        val variant = code.substringAfter('_', "")
        return if (variant.isEmpty()) label else "$label ($variant)"
    }

    private fun fetch(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }
        conn.inputStream.use { it.readBytes().decodeToString() }
    } catch (e: Exception) {
        null
    }
}
