package app.lightphonekeyboard

import android.content.Context
import app.lightphonekeyboard.text.CombinedList
import app.lightphonekeyboard.text.Dictionary
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * A language other than English, fetched when it is asked for.
 *
 * A pack is a zip of files the keyboard already knows how to read — `words.bin` in the same LKD1
 * format as the bundled English list, a `charmodel.bin` of the same shape, a `display.txt`, and a
 * line or two of metadata. Nothing here parses a new format; see `tools/gen_pack.py`.
 *
 * Downloaded rather than bundled because six of them is three megabytes of dictionaries that most
 * people will never open, on an APK that is already twenty-four. The download is the only network
 * call this keyboard makes besides the GIF button, it happens when somebody taps a language, and
 * typing never causes one.
 *
 * ## Why the words are folded
 *
 * Everything under a tap is a-z: the trie branches twenty-six ways, the character model is cubic in
 * the alphabet, the swipe model emits twenty-seven classes. `words.bin` therefore holds `cafe`, and
 * `display.txt` says that `cafe` is written `café`. See `text/Folding.kt`.
 */
object LangPack {

    /** The built-in language. No pack, no download, always there. */
    const val ENGLISH = "en"

    /** Where the packs live. A release rather than the repo: they are built artifacts. */
    private const val BASE = "https://github.com/gi-os/BrightKeyboard/releases/download/packs-v1"

    /** What a pack may weigh. A real one is about half a megabyte; this is a wrong-file guard. */
    private const val MAX_BYTES = 8L * 1024 * 1024

    /** Offered languages, in the order the settings screen lists them. */
    val AVAILABLE = listOf(
        Lang("es", "Español"),
        Lang("fr", "Français"),
        Lang("de", "Deutsch"),
        Lang("pt", "Português"),
        Lang("it", "Italiano"),
        Lang("no", "Norsk"),
        // Neither is published as an AOSP word list, so a built pack is the only way to get them.
        Lang("id", "Bahasa Indonesia"),
        Lang("is", "Íslenska"),
    )

    class Lang(val code: String, val name: String)

    class Loaded(val dictionary: Dictionary, val charModel: ByteArray, val display: Map<String, String>)

    /** What the engine actually types against: one dictionary, one character model, one display map. */
    class Active(
        val dictionary: Dictionary?,
        val charModel: FloatArray?,
        val display: Map<String, String>,
    )

    /**
     * Fold every switched-on language into one set of tables.
     *
     * English on its own returns nothing and the bundled files are used unchanged, which keeps the
     * common case exactly as fast as it was. Anything else is built here, including the character
     * model: there is no file to download for "Spanish and Norwegian together", and that table has to
     * describe whatever combination is actually switched on.
     *
     * Blocking, and called from the engine's own background thread.
     */
    fun active(c: Context): Active {
        val codes = Prefs.languages(c).filter { it != ENGLISH && isInstalled(c, it) }
        if (codes.isEmpty()) return Active(null, null, emptyMap())
        val lists = codes.map { entriesOf(c, it) }.filter { it.isNotEmpty() }
        if (lists.isEmpty()) return Active(null, null, emptyMap())
        // English stays in the mix when it was left switched on beside another language.
        val merged = CombinedList.merge(lists)
        if (merged.isEmpty()) return Active(null, null, emptyMap())
        return Active(
            Dictionary.of(CombinedList.toDictionaryEntries(merged)),
            CombinedList.charModel(merged),
            CombinedList.toDisplay(merged),
        )
    }

    fun nameOf(code: String): String =
        if (code == ENGLISH) "English" else AVAILABLE.firstOrNull { it.code == code }?.name ?: code

    private fun dir(c: Context, code: String) = File(File(c.filesDir, "packs"), code)

    fun installed(c: Context): List<String> =
        AVAILABLE.map { it.code }.filter { isInstalled(c, it) }

    fun remove(c: Context, code: String) {
        dir(c, code).listFiles()?.forEach { it.delete() }
        dir(c, code).delete()
    }

    /**
     * Read an installed pack. Returns null for English, for one not installed, and for one that will
     * not load — a half-written pack must fall back to English rather than take the keyboard down.
     */
    /** Where an installed AOSP list lives, as the entries the merger wants. */
    private fun entriesFile(c: Context, code: String) = File(dir(c, code), "entries.tsv")

    /**
     * Install a `.combined` word list fetched from [AospRepo]. Blocking. Null on success.
     *
     * The parse happens here rather than at load time because it is the slow part and it only has to
     * happen once. What lands on disk is the same shape either kind of pack keeps: folded key, log
     * frequency, and the written spelling where it differs.
     */
    fun installCombined(c: Context, code: String, name: String, url: String): String? {
        val target = dir(c, code)
        val staging = File(File(c.filesDir, "packs"), "$code.part")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }
            val entries = conn.inputStream.bufferedReader().use { reader ->
                var read = 0L
                CombinedList.parse(
                    reader.lineSequence().onEach {
                        read += it.length + 1
                        if (read > AospRepo.MAX_BYTES) throw IllegalStateException("List is too large")
                    },
                )
            }
            if (entries.isEmpty()) {
                return "No words this keyboard can type. Its letters are probably not a-z."
            }
            File(staging, "entries.tsv").bufferedWriter().use { w ->
                for (e in entries) {
                    w.write(e.key); w.write("\t"); w.write(e.logf.toString())
                    w.write("\t"); w.write(e.display); w.write("\n")
                }
            }
            File(staging, "meta.txt").writeText("code=$code\nname=$name\nsource=aosp\nwords=${entries.size}")
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) return "Could not store the dictionary"
            return null
        } catch (e: Exception) {
            return e.message?.take(80) ?: "Download failed"
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Every installed language's entries, whichever way it was installed. */
    fun entriesOf(c: Context, code: String): List<CombinedList.Entry> {
        val tsv = entriesFile(c, code)
        if (tsv.exists()) {
            return tsv.readLines().mapNotNull { line ->
                val a = line.indexOf('\t')
                val b = line.indexOf('\t', a + 1)
                if (a <= 0 || b <= a) return@mapNotNull null
                val logf = line.substring(a + 1, b).toFloatOrNull() ?: return@mapNotNull null
                CombinedList.Entry(line.substring(0, a), line.substring(b + 1), logf)
            }
        }
        // One of the six built packs: read its words.bin back out into the same shape.
        val loaded = load(c, code) ?: return emptyList()
        val out = ArrayList<CombinedList.Entry>(loaded.dictionary.size)
        for (i in 0 until loaded.dictionary.size) {
            val w = loaded.dictionary.word(i)
            out.add(CombinedList.Entry(w, loaded.display[w] ?: w, loaded.dictionary.logfOf(i)))
        }
        return out
    }

    fun isInstalled(c: Context, code: String): Boolean =
        code == ENGLISH || File(dir(c, code), "words.bin").exists() || entriesFile(c, code).exists()

    fun load(c: Context, code: String): Loaded? {
        if (code == ENGLISH) return null
        val d = dir(c, code)
        return try {
            val dict = File(d, "words.bin").inputStream().use { Dictionary.load(it) }
            val model = File(d, "charmodel.bin").readBytes()
            if (model.size != CHAR_MODEL_BYTES) return null
            val display = HashMap<String, String>()
            File(d, "display.txt").forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) display[line.substring(0, tab)] = line.substring(tab + 1)
            }
            Loaded(dict, model, display)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetch and unpack [code]. Blocking; call it off the main thread. Returns null on success or a
     * short reason for the screen.
     *
     * Unpacks into a staging directory and moves it into place at the end, so an interrupted download
     * leaves the previous state rather than a directory the loader will later half-read.
     */
    fun download(c: Context, code: String): String? {
        val staging = File(File(c.filesDir, "packs"), "$code.part")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val conn = (URL("$BASE/$code.pack").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { unzip(it, staging) }
            if (conn.responseCode !in 200..299) return "Server said ${conn.responseCode}"
            if (!File(staging, "words.bin").exists()) return "That pack is missing its words"
            val target = dir(c, code)
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) return "Could not store the pack"
            return null
        } catch (e: Exception) {
            return e.message?.take(80) ?: "Download failed"
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Unpack, refusing anything that is not one of the four names a pack holds.
     *
     * A zip entry names its own path, and a name like `../../shared_prefs/x.xml` would otherwise be
     * written wherever it liked. An allowlist is a stronger answer than checking for `..`, because
     * there is no reason for a pack to contain anything else.
     */
    private fun unzip(input: InputStream, into: File) {
        val allowed = setOf("words.bin", "charmodel.bin", "display.txt", "meta.txt")
        var total = 0L
        ZipInputStream(input).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in allowed) { zin.closeEntry(); continue }
                val out = File(into, name)
                out.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalStateException("Pack is too large")
                        o.write(buf, 0, n)
                    }
                }
                zin.closeEntry()
            }
        }
    }

    /** 27 * 27 * 27 floats, which is what the character model has always been. */
    const val CHAR_MODEL_BYTES = 27 * 27 * 27 * 4
}
