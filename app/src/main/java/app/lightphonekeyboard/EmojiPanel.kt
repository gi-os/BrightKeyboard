package app.lightphonekeyboard

import android.content.Context
import android.graphics.Paint
import android.util.Log
import app.lightphonekeyboard.text.Emoji
import app.lightphonekeyboard.text.RecentEmoji

/**
 * What the emoji panel is currently showing, and everything behind it.
 *
 * [LightKeyboardView] owns the geometry — where a cell lands on screen, how big the glyph is drawn.
 * This owns the answer to "which emoji, in what order": the loaded table, the filter for what this
 * phone can actually draw, the recents, the chosen skin tone, the category the view has scrolled to,
 * and the search results when a query is running. Splitting it that way keeps the view about pixels
 * and keeps this testable without one.
 *
 * **The font decides what exists.** A bundled table is a list of what Unicode defines, not of what
 * the phone can render, and the two are never the same — LightOS ships whatever emoji font its
 * Android version shipped with, so anything newer draws as an empty box. [Paint.hasGlyph] is the only
 * way to ask, so the whole table is asked once, on a background thread, and anything the font cannot
 * draw is dropped before it ever reaches the grid. That also means this stays correct if LightOS
 * updates its font: the answer is recomputed, not remembered.
 */
class EmojiPanel(private val context: Context) {

    /**
     * Everything the load produces, as one object.
     *
     * One field rather than four, and that is a correctness requirement rather than tidiness. The
     * load runs on a background thread and the UI thread reads all of this while drawing; published
     * as separate fields, the reader can see a new `groups` beside an old `groupStart` for the
     * instruction between the two writes, and every reader here walks `groups.indices` and then
     * indexes `groupStart` — so that window is an index out of bounds, thrown while drawing, which
     * in an IME means no keyboard in any app. A single immutable snapshot cannot tear.
     */
    private class Loaded(
        val table: Emoji,
        /** Indices into [table] the font can draw, in Unicode's order. */
        val usable: IntArray,
        /** Where each group starts in [usable], plus a final entry equal to its size. */
        val groupStart: IntArray,
        /** Group names that survived the font filter, parallel to [groupStart]. */
        val groups: List<String>,
    )

    @Volatile
    private var loaded: Loaded = Loaded(Emoji.EMPTY, IntArray(0), IntArray(1), emptyList())

    val table: Emoji get() = loaded.table

    /**
     * Empty until the load finishes, at which point the panel simply has nothing in it — which is
     * what it had before, so a failed load costs the emoji key and nothing else.
     */
    val usable: IntArray get() = loaded.usable

    val groups: List<String> get() = loaded.groups

    val ready: Boolean get() = loaded.usable.isNotEmpty()

    /**
     * Called on the main thread once the table is in. The panel is drawn from data that arrives
     * after it can already be on screen, and nothing else would repaint it — without this, opening
     * the keyboard and going straight to emoji leaves "Loading…" on screen until it is closed and
     * reopened, because a rebuild only happens on a touch the empty grid has nothing to receive.
     */
    var onReady: (() -> Unit)? = null

    var recents: RecentEmoji = RecentEmoji.EMPTY
        private set

    /** 0 for the yellow default, 1-5 for the Fitzpatrick tones. See [Prefs.skinTone]. */
    var tone: Int = 0
        private set

    /** The live search query; empty when the panel is browsing rather than searching. */
    var query: String = ""
        private set

    private var results: IntArray = IntArray(0)

    @Volatile
    private var loading = false

    /**
     * Read the table and ask the font what it can draw. Safe to call repeatedly.
     *
     * Off the main thread because [Paint.hasGlyph] on 1,761 sequences is not free and this is called
     * while the keyboard is appearing. Everything here degrades to "the panel is empty" rather than
     * throwing: this runs inside an IME, where an exception means no keyboard at all.
     */
    @Synchronized
    fun prepare() {
        if (ready || loading) return
        loading = true
        Thread({
            try {
                val loaded = context.resources.openRawResource(R.raw.emoji).use { Emoji.load(it) }
                val paint = Paint()
                val kept = IntArray(loaded.size)
                var n = 0
                for (i in loaded.indices()) {
                    if (paint.hasGlyph(loaded.glyph(i))) kept[n++] = i
                }
                publish(loaded, if (n == kept.size) kept else kept.copyOf(n))
            } catch (t: Throwable) {
                // Throwable, not Exception: this is a bare thread, so anything escaping it takes the
                // whole IME process down and the phone has no keyboard at all until it restarts.
                Log.w(TAG, "emoji table unavailable; the panel will be empty", t)
            }
            loading = false
        }, "light-kb-emoji").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun publish(loaded: Emoji, kept: IntArray) {
        // Group boundaries over the filtered list, not the original: a group can lose entries to the
        // font filter, and one can lose all of them, in which case it should not get a jump button.
        val names = ArrayList<String>(loaded.groups.size)
        val starts = ArrayList<Int>(loaded.groups.size + 1)
        var last = -1
        for (k in kept.indices) {
            val g = loaded.group(kept[k])
            if (g != last) {
                names.add(loaded.groups.getOrElse(g) { "" })
                starts.add(k)
                last = g
            }
        }
        starts.add(kept.size)
        this.loaded = Loaded(loaded, kept, starts.toIntArray(), names)
        onReady?.invoke()
    }

    /** Re-read the settings and the recents list. Called whenever the keyboard opens. */
    fun reload() {
        tone = Prefs.skinTone(context)
        recents = RecentEmoji.deserialize(Prefs.recentEmoji(context))
    }

    /** Remember [glyph] as just used, and persist it. */
    fun remember(glyph: String) {
        val next = recents.used(glyph)
        if (next.entries == recents.entries) return
        recents = next
        Prefs.setRecentEmoji(context, next.serialize())
    }

    // ------------------------------------------------------------------ what to show

    /**
     * The glyphs the grid should show right now, in order.
     *
     * Three states, and they are mutually exclusive: a running search shows its results, otherwise
     * the recents lead the full list, otherwise the full list alone. Recents are prepended rather
     * than being a separate scroll position so that the commonest case — open the panel, tap the
     * emoji you used five minutes ago — needs no navigation at all.
     */
    fun glyphs(): List<String> {
        val snap = loaded
        if (query.isNotEmpty()) return results.map { snap.table.withTone(it, tone) }
        val out = ArrayList<String>(recents.size + snap.usable.size)
        out.addAll(recents.entries)
        for (i in snap.usable) out.add(snap.table.withTone(i, tone))
        return out
    }

    /** How many cells precede the main list — the recents, when there are any and no search. */
    fun leadingCount(): Int = if (query.isNotEmpty()) 0 else recents.size

    /** Set the query and recompute. Empty goes back to browsing. */
    fun search(q: String) {
        query = q.trim()
        results =
            if (query.length >= Emoji.MIN_QUERY) loaded.table.search(query, SEARCH_LIMIT)
            else IntArray(0)
    }

    /** True when a search is running but found nothing, which the panel says out loud. */
    fun searchedAndFoundNothing(): Boolean =
        query.length >= Emoji.MIN_QUERY && results.isEmpty()

    /**
     * Every spelling of the emoji shown in cell [cell] — each skin tone, each gendered form — or an
     * empty list when it has none.
     *
     * A recent has no variants offered: it is already the exact glyph that was used, and a recent
     * sitting in the list *because* somebody picked a variant should not then offer to re-pick it.
     */
    fun variantsAt(cell: Int): List<String> {
        val snap = loaded
        val i = tableIndexAt(cell) ?: return emptyList()
        if (!snap.table.hasVariants(i)) return emptyList()
        val all = snap.table.variantsOf(i)
        if (all.size <= ROW) return all

        // More spellings than a row can hold, so choose which ones rather than taking the first few.
        //
        // Taking the head is wrong for the two-person emoji: `handshake` stores a spelling per *pair*
        // of tones, twenty of them, and the first seven are all mixed pairs — so the picker offered
        // no uniform tone at all, which is the only thing most people want. One spelling per tone,
        // then whatever gendered forms still fit.
        val out = ArrayList<String>(ROW)
        out.add(all.first())
        for (t in Emoji.TONES) {
            val uniform = all.firstOrNull { it.contains(t) && it !in out } ?: continue
            out.add(uniform)
            if (out.size >= ROW) return out
        }
        for (v in all) {
            if (v in out) continue
            if (Emoji.TONES.any { v.contains(it) }) continue   // a toned form; already represented
            out.add(v)
            if (out.size >= ROW) break
        }
        return out
    }

    /** The table index behind cell [cell], or null for a recent (which is a glyph, not an index). */
    fun tableIndexAt(cell: Int): Int? {
        if (query.isNotEmpty()) return results.getOrNull(cell)
        val lead = recents.size
        if (cell < lead) return null
        val k = cell - lead
        val snap = loaded
        return if (k in snap.usable.indices) snap.usable[k] else null
    }

    /** The first cell of group [g], for the category jump buttons. */
    fun cellOfGroup(g: Int): Int {
        val snap = loaded
        if (g !in snap.groups.indices) return 0
        return leadingCount() + snap.groupStart[g]
    }

    /** Which group cell [cell] belongs to, for showing the category as selected. -1 for recents. */
    fun groupOfCell(cell: Int): Int {
        val snap = loaded
        val lead = leadingCount()
        if (cell < lead) return -1
        val k = cell - lead
        for (g in snap.groups.indices) {
            if (k >= snap.groupStart[g] && k < snap.groupStart[g + 1]) return g
        }
        return snap.groups.size - 1
    }

    /**
     * One glyph per category, for the jump buttons — the first emoji of each group that the font can
     * draw. Taken from the data rather than hardcoded, so a category whose usual icon is missing from
     * this phone's font still gets a button with something on it.
     */
    fun categoryIcons(): List<String> {
        val snap = loaded
        val out = ArrayList<String>(snap.groups.size)
        for (g in snap.groups.indices) {
            val at = snap.groupStart[g]
            out.add(if (at in snap.usable.indices) snap.table.glyph(snap.usable[at]) else "")
        }
        return out
    }

    private companion object {
        const val TAG = "LightKeyboard"

        /** Results kept for a query. Three rows of eight, i.e. one screen of the panel. */
        const val SEARCH_LIMIT = 24

        /** Variants a picker row can hold. Matches the grid's column count. */
        const val ROW = 8
    }
}
