package app.lightphonekeyboard

import android.content.Context
import android.net.Uri
import app.lightphonekeyboard.text.WordList
import java.io.File

/**
 * The imported word list, on disk.
 *
 * A file rather than a preference, because SharedPreferences holds every value it has in memory for
 * the life of the process and a glossary is tens of thousands of words. The personal list stays in
 * prefs, where a dozen names belong.
 *
 * Read once at engine start and held in the same [app.lightphonekeyboard.text.UserWords] as the
 * hand-added names, so every consumer below — corrector, swipe decoder, suggester, splitter — gets
 * them without knowing there are two sources.
 */
object CustomWords {

    private const val FILE = "imported-words.txt"

    private fun file(c: Context) = File(c.filesDir, FILE)

    /** The imported words, or empty. Never throws: a list that cannot be read is a list we do not have. */
    fun load(c: Context): List<String> = try {
        val f = file(c)
        if (f.exists()) f.readLines() else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun count(c: Context): Int = load(c).size

    /**
     * The stored list as the file's own text, or null when there isn't one.
     *
     * Exists for the applicationId move ([Migration]): a new applicationId is a new app to Android
     * and gets none of this, so it has to be handed over. Raw text rather than the parsed list,
     * because the file is the format and round-tripping through a List would quietly normalise
     * whatever the user imported.
     */
    internal fun raw(c: Context): String? =
        runCatching { file(c).takeIf { it.exists() }?.readText() }.getOrNull()

    /** Counterpart to [raw]: replace the stored list wholesale. */
    internal fun restore(c: Context, text: String) {
        runCatching { file(c).writeText(text) }
    }

    fun clear(c: Context) {
        runCatching { file(c).delete() }
    }

    /**
     * Read [uri], keep what this keyboard can type, and replace the stored list with it.
     *
     * Replace rather than append: importing twice is nearly always the same file again, corrected,
     * and a keyboard that had silently accumulated both copies would be impossible to reason about.
     * Returns what was kept and what was not, so the screen can say so.
     */
    fun import(c: Context, uri: Uri): WordList.Result {
        val text = c.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: return WordList.Result(emptyList(), 0)
        val parsed = WordList.parse(text)
        if (parsed.words.isEmpty()) {
            clear(c)
        } else {
            file(c).writeText(parsed.words.joinToString("\n"))
        }
        return parsed
    }
}
