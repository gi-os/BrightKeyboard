package app.lightphonekeyboard.text

/**
 * The clipboard history: what has been copied lately, newest first, with a few entries pinned.
 *
 * Pure Kotlin with no Android types, like everything else in this package, so the escaping and the
 * eviction rules are unit-tested rather than trusted. The keyboard is the only app on the phone that
 * may read the clipboard while another app is in front, so if this loses a clip nothing else has it.
 *
 * ## Format
 *
 * One record a line, `<0|1>\t<text>`, where the flag is the pin. A clip may itself contain tabs and
 * newlines, so the text is escaped — `\` `\n` `\r` `\t` become `\\` `\n` `\r` `\t` — and a line that
 * does not parse is dropped rather than allowed to swallow the rest of the file.
 *
 * ## Why capped, and why pinned entries are exempt
 *
 * A history that grows forever is a log of everything the user has ever copied sitting in a
 * SharedPreferences file. [LIMIT] entries is about a day of ordinary copying and two screens of
 * list. Pins are the exception because a pin is the user saying "keep this one", and an address or
 * a licence key that fell off the end after 24 copies would make pinning pointless.
 */
object Clips {

    /** Unpinned entries kept. Pins are extra, capped separately by [PIN_LIMIT]. */
    const val LIMIT = 24

    /** Pins kept. A ceiling rather than a policy — it only exists so nothing here is unbounded. */
    const val PIN_LIMIT = 16

    /**
     * Longest clip stored, in characters. Anything longer is kept as its first [MAX_LEN] characters:
     * a truncated paste is a smaller surprise than a preferences file with a whole document in it,
     * and a clip this long is nearly always an accident.
     */
    const val MAX_LEN = 4000

    data class Clip(val text: String, val pinned: Boolean = false)

    fun parse(stored: String?): List<Clip> {
        if (stored.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Clip>()
        for (line in stored.split('\n')) {
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab != 1) continue                       // not "<flag>\t..." — drop it
            val text = unescape(line.substring(tab + 1))
            if (text.isEmpty()) continue
            out.add(Clip(text, line[0] == '1'))
        }
        return out
    }

    fun serialize(clips: List<Clip>): String =
        clips.joinToString("\n") { (if (it.pinned) "1\t" else "0\t") + escape(it.text) }

    /**
     * Put [text] at the top.
     *
     * An exact repeat is *moved* rather than added, and keeps whatever pin it had: copying the same
     * address twice is one clip that was useful twice, and a list that fills with duplicates of
     * whatever the user is currently pasting around is no list at all.
     */
    fun add(clips: List<Clip>, text: String): List<Clip> {
        val trimmed = text.take(MAX_LEN)
        if (trimmed.isBlank()) return clips
        val existing = clips.firstOrNull { it.text == trimmed }
        val rest = clips.filter { it.text != trimmed }
        return prune(listOf(Clip(trimmed, existing?.pinned ?: false)) + rest)
    }

    /** Flip the pin on [text]. A newly pinned clip stays where it is; the list is ordered by recency. */
    fun togglePin(clips: List<Clip>, text: String): List<Clip> =
        prune(clips.map { if (it.text == text) it.copy(pinned = !it.pinned) else it })

    /** Drop everything that isn't pinned. What "Clear" does — a pin has to be undone deliberately. */
    fun clearUnpinned(clips: List<Clip>): List<Clip> = clips.filter { it.pinned }

    private fun prune(clips: List<Clip>): List<Clip> {
        var pins = 0
        var loose = 0
        val out = ArrayList<Clip>(clips.size)
        for (c in clips) {
            if (c.pinned) {
                if (pins++ < PIN_LIMIT) out.add(c)
            } else {
                if (loose++ < LIMIT) out.add(c)
            }
        }
        return out
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when (ch) {
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\' || i == s.length - 1) { sb.append(ch); i++; continue }
            when (s[i + 1]) {
                '\\' -> sb.append('\\')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                else -> { sb.append(ch); i++; continue }   // unknown escape: keep the backslash
            }
            i += 2
        }
        return sb.toString()
    }

    /**
     * One line of a clip, for a list cell. Newlines and runs of whitespace collapse to single spaces,
     * because a cell is one line tall and a multi-line clip would otherwise render as its first line
     * only — which looks like a different clip from the one that will be pasted.
     */
    fun preview(text: String, max: Int = 64): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }
}
