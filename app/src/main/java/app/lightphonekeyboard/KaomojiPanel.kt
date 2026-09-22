package app.lightphonekeyboard

import android.content.Context
import android.graphics.Paint
import app.lightphonekeyboard.text.Kaomoji
import app.lightphonekeyboard.text.RecentEmoji

/**
 * What the kaomoji page is currently showing.
 *
 * The same split as [EmojiPanel]: [LightKeyboardView] owns where a cell lands and how the text is
 * sized to fit it, and this owns which faces, in what order, in which category. Everything the page
 * draws comes out of one immutable [View] snapshot for the reason the emoji panel publishes one —
 * the list, its group boundaries and its group names are read together while drawing, and a reader
 * that caught a new list beside old boundaries would index past the end and throw inside an IME,
 * which means no keyboard in any app.
 *
 * ## Asking the font, one code point at a time
 *
 * [Paint.hasGlyph] answers "can you draw this *as one glyph*". For an emoji that is the whole
 * question. For a kaomoji it is the wrong question asked of the whole face: `ʕ•ᴥ•ʔ` is five glyphs
 * and the call returns false for it however well the font draws every one. So each face is checked a
 * code point at a time, and a face is dropped only when a code point of its own is missing — which
 * is what stops the page showing a row of empty boxes on a phone whose font has never heard of
 * Bopomofo.
 *
 * Combining marks are exempt from that check. A lone U+0300 has no glyph of its own in most fonts
 * and [Paint.hasGlyph] says so, but the font still draws it perfectly well over the letter in front
 * of it — asking about it in isolation would throw away `ʕ•́ᴥ•̀ʔ` on a phone that renders it fine.
 */
class KaomojiPanel(private val context: Context) {

    /** The page's whole state, published as one object so a reader cannot see half an update. */
    class View(
        /** What each cell shows, in order: your recents, then the categories, then your own. */
        val cells: List<String>,
        /** Category names, in cell order, including "Recent" and "Yours" when those exist. */
        val groups: List<String>,
        /** Where each group starts in [cells], plus a final entry equal to its size. */
        val groupStart: IntArray,
    ) {
        val size: Int get() = cells.size
    }

    @Volatile
    private var drawable: List<Kaomoji.Face> = emptyList()

    @Volatile
    var view: View = View(emptyList(), emptyList(), intArrayOf(0))
        private set

    /** Empty until the font filter has run; until then the page says it is loading. */
    val ready: Boolean get() = drawable.isNotEmpty()

    /**
     * Called on the main thread when the filter lands. The page can already be on screen by then and
     * nothing else would repaint it — the same repaint [EmojiPanel.onReady] exists for.
     */
    var onReady: (() -> Unit)? = null

    private var recents: RecentEmoji = RecentEmoji.EMPTY
    private var own: List<String> = emptyList()

    /** The live query; empty when the page is browsing rather than searching. */
    var query: String = ""
        private set

    private var results: List<Int> = emptyList()

    @Volatile
    private var loading = false

    /**
     * Ask the font what it can draw. Safe to call repeatedly; runs once.
     *
     * Off the main thread, like the emoji load, because this is a few hundred faces and several
     * thousand [Paint.hasGlyph] calls and it happens while the keyboard is appearing. Anything
     * escaping is caught: a bare thread throwing inside an IME takes the whole process with it, and
     * the failure this is protecting against costs the kaomoji page and nothing else.
     */
    @Synchronized
    fun prepare() {
        if (ready || loading) return
        loading = true
        Thread({
            try {
                val paint = Paint()
                val kept = Kaomoji.BUILT_IN.filter { canDraw(paint, it.text) }
                drawable = kept
                rebuild()
                onReady?.invoke()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "kaomoji unavailable; the page will be empty", t)
            }
            loading = false
        }, "light-kb-kaomoji").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun canDraw(paint: Paint, text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x80) continue                     // ASCII; every font has it
            if (isMark(cp)) continue                    // see the class doc
            if (!paint.hasGlyph(String(Character.toChars(cp)))) return false
        }
        return true
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
        Character.FORMAT,
        -> true
        else -> false
    }

    /** Re-read the recents and your own faces. Called whenever the page opens. */
    fun reload() {
        recents = RecentEmoji.deserialize(Prefs.recentKaomoji(context))
        own = Kaomoji.parseUser(Prefs.userKaomoji(context))
        rebuild()
    }

    /** Remember [text] as just used, and persist it. */
    fun remember(text: String) {
        val next = recents.used(text)
        if (next.entries == recents.entries) return
        recents = next
        Prefs.setRecentKaomoji(context, next.serialize())
        rebuild()
    }

    /** Set the query and recompute. Empty goes back to browsing. */
    fun search(q: String) {
        query = q.trim()
        results =
            if (query.length >= Kaomoji.MIN_QUERY) Kaomoji.search(searchable(), query, SEARCH_LIMIT)
            else emptyList()
        rebuild()
    }

    /** True when a search ran and found nothing, which the page says out loud. */
    fun searchedAndFoundNothing(): Boolean =
        query.length >= Kaomoji.MIN_QUERY && results.isEmpty()

    /**
     * What a search looks through: your own faces first, then the built-ins.
     *
     * Yours lead so that a face you wrote down wins a tie against one that shipped with the app. A
     * user face has no name, so it is found by what is in it — see [Kaomoji.search].
     */
    private fun searchable(): List<Kaomoji.Face> =
        own.map { Kaomoji.Face(it, "", YOURS) } + drawable

    /** The face in cell [cell], or null when the cell is past the end. */
    fun textAt(cell: Int): String? = view.cells.getOrNull(cell)

    /**
     * How many cells precede the categories — the recents, when there are any and no search.
     *
     * The page watches this across a commit. Using a face you have used before only reorders the
     * recents and every cell stays where it is, but using a new one adds a cell and shifts the whole
     * list along by one, and a page laid out before that is a page whose cells no longer match what
     * is drawn in them.
     */
    fun leadingCount(): Int = if (query.isNotEmpty()) 0 else minOf(recents.size, RECENT_SHOWN)

    /** Which group cell [cell] is in, or -1 when there are none. */
    fun groupOfCell(cell: Int): Int {
        val v = view
        for (g in v.groups.indices) {
            if (cell >= v.groupStart[g] && cell < v.groupStart[g + 1]) return g
        }
        return if (v.groups.isEmpty()) -1 else v.groups.size - 1
    }

    /** The first cell of group [g]. */
    fun cellOfGroup(g: Int): Int {
        val v = view
        return if (g in v.groups.indices) v.groupStart[g] else 0
    }

    /**
     * Rebuild the snapshot from whatever the parts currently are.
     *
     * A search replaces the whole list rather than filtering it in place, and gets no groups at all:
     * results are ranked across every category, so a category strip over them would be pointing at
     * an order that no longer exists.
     */
    private fun rebuild() {
        val faces = drawable
        if (query.isNotEmpty()) {
            val all = searchable()
            val cells = results.mapNotNull { all.getOrNull(it)?.text }
            view = View(cells, emptyList(), intArrayOf(0, cells.size))
            return
        }
        val cells = ArrayList<String>(recents.size + faces.size + own.size)
        val names = ArrayList<String>(Kaomoji.CATEGORIES.size + 2)
        val starts = ArrayList<Int>(Kaomoji.CATEGORIES.size + 3)

        if (!recents.isEmpty()) {
            names.add(RECENT)
            starts.add(0)
            cells.addAll(recents.entries.take(RECENT_SHOWN))
        }
        // Group boundaries over the faces that survived the font filter, not over the table: a
        // category can lose entries to a missing font and could in principle lose all of them, and
        // an empty category should not get a name in the strip.
        var last = -1
        for (f in faces) {
            if (f.group != last) {
                names.add(Kaomoji.CATEGORIES.getOrElse(f.group) { "" })
                starts.add(cells.size)
                last = f.group
            }
            cells.add(f.text)
        }
        if (own.isNotEmpty()) {
            names.add(YOURS_NAME)
            starts.add(cells.size)
            cells.addAll(own)
        }
        starts.add(cells.size)
        view = View(cells, names, starts.toIntArray())
    }

    private companion object {
        const val TAG = "LightKeyboard"

        /** Results kept for a query. Four pages of the page's six cells. */
        const val SEARCH_LIMIT = 24

        /** Group index given to your own faces while searching. Never shown; search ignores it. */
        const val YOURS = -1

        /**
         * Recents shown, which is one page of the page and not the [RecentEmoji.MAX] that are kept.
         *
         * The emoji panel shows all 24 because its recents are one screen of an eight-wide grid.
         * Here a page holds six, so all 24 would be four pages of recents standing between the
         * user and every category — a shortcut you have to page past is not a shortcut.
         */
        const val RECENT_SHOWN = 6

        const val RECENT = "Recent"
        const val YOURS_NAME = "Yours"
    }
}
