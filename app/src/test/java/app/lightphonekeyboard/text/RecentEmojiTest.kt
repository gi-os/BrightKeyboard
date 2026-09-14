package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentEmojiTest {

    @Test
    fun `the newest is first`() {
        val r = RecentEmoji.EMPTY.used("🍕").used("🚀").used("👋")
        assertEquals(listOf("👋", "🚀", "🍕"), r.entries)
    }

    @Test
    fun `using one again promotes it instead of duplicating it`() {
        // The property that keeps the list stable: the handful you actually use stay at the front
        // rather than being pushed off by every one-off.
        val r = RecentEmoji.EMPTY.used("🍕").used("🚀").used("🍕")
        assertEquals(listOf("🍕", "🚀"), r.entries)
    }

    @Test
    fun `using the one already at the front changes nothing`() {
        val r = RecentEmoji.EMPTY.used("🍕")
        assertTrue(r.used("🍕").entries === r.entries || r.used("🍕").entries == r.entries)
        assertEquals(1, r.used("🍕").size)
    }

    @Test
    fun `the list is capped and drops the oldest`() {
        var r = RecentEmoji.EMPTY
        val all = (0 until RecentEmoji.MAX + 10).map { "e$it" }
        for (g in all) r = r.used(g)
        assertEquals(RecentEmoji.MAX, r.size)
        assertEquals(all.last(), r.entries.first())
        assertTrue("the oldest should be gone", !r.entries.contains(all.first()))
    }

    @Test
    fun `it survives a round trip through storage`() {
        val r = RecentEmoji.EMPTY.used("🍕").used("👋🏽").used("🇫🇷")
        assertEquals(r.entries, RecentEmoji.deserialize(r.serialize()).entries)
    }

    @Test
    fun `a tone-picked emoji is stored exactly as it was used`() {
        // Storing the base and re-deriving from the current default tone would rewrite history the
        // moment somebody changed that setting, and would undo a deliberately picked variant.
        val r = RecentEmoji.EMPTY.used("👋🏿")
        assertEquals("👋🏿", r.entries.first())
        assertEquals("👋🏿", RecentEmoji.deserialize(r.serialize()).entries.first())
    }

    @Test
    fun `nothing stored gives an empty list`() {
        assertTrue(RecentEmoji.deserialize(null).isEmpty())
        assertTrue(RecentEmoji.deserialize("").isEmpty())
        assertTrue(RecentEmoji.EMPTY.isEmpty())
    }

    @Test
    fun `an empty glyph is refused`() {
        assertTrue(RecentEmoji.EMPTY.used("").isEmpty())
    }

    @Test
    fun `a stored list with duplicates or blanks is cleaned up`() {
        val r = RecentEmoji.deserialize("🍕\n\n🚀\n🍕\n")
        assertEquals(listOf("🍕", "🚀"), r.entries)
    }
}
