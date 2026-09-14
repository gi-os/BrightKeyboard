package app.lightphonekeyboard.text

/**
 * The emoji used lately, most recent first.
 *
 * A panel of 1,761 emoji is 220 rows deep, and almost everybody reaches for the same handful. Without
 * this, using 😂 twice means scrolling to it twice. It is the first category in the panel for that
 * reason.
 *
 * **The exact glyph is stored, tone and all.** A recent is the thing that was actually sent, so
 * re-deriving it from a base and the current default tone would quietly rewrite history the moment
 * somebody changed that setting — and would turn a deliberately-picked variant back into the default
 * one. Storing the string means a recent is inserted exactly as it was used.
 *
 * Immutable, like [UserWords] and [ForgottenWords], so the IME thread can swap a whole list in with
 * no lock. Pure logic, no Android types.
 */
class RecentEmoji private constructor(val entries: List<String>) {

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * [glyph] moved to the front, with the list trimmed to [MAX].
     *
     * Moved rather than added: using an emoji again should promote it, not sit a second copy beside
     * the first. That is also what keeps the list stable — the six you actually use stay at the front
     * instead of being pushed off by every one-off.
     */
    fun used(glyph: String): RecentEmoji {
        if (glyph.isEmpty()) return this
        if (entries.firstOrNull() == glyph) return this
        val out = ArrayList<String>(minOf(entries.size + 1, MAX))
        out.add(glyph)
        for (e in entries) {
            if (out.size >= MAX) break
            if (e != glyph) out.add(e)
        }
        return RecentEmoji(out)
    }

    fun serialize(): String = entries.joinToString("\n")

    companion object {
        /**
         * How many to remember. Three rows of eight is exactly one screen of the panel, so the
         * recents category fills its view and never scrolls.
         */
        const val MAX = 24

        val EMPTY = RecentEmoji(emptyList())

        fun of(glyphs: List<String>): RecentEmoji {
            val out = ArrayList<String>(minOf(glyphs.size, MAX))
            for (g in glyphs) {
                if (out.size >= MAX) break
                if (g.isNotEmpty() && g !in out) out.add(g)
            }
            return RecentEmoji(out)
        }

        fun deserialize(stored: String?): RecentEmoji {
            if (stored.isNullOrEmpty()) return EMPTY
            return of(stored.split('\n').filter { it.isNotEmpty() })
        }
    }
}
