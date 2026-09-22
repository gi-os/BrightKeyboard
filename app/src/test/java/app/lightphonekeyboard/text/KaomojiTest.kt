package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaomojiTest {

    // ------------------------------------------------------------------ the bundled set

    /**
     * Every bundled face has to survive the store and the cell it is drawn in. A face with a tab in
     * it would be saved as two faces the first time it was used, and one over [Kaomoji.MAX_LEN] is
     * longer than anything the user is allowed to add, which makes the limit a lie.
     */
    @Test
    fun `every built-in face is storable`() {
        for (f in Kaomoji.BUILT_IN) {
            assertTrue("empty face named '${f.name}'", f.text.isNotEmpty())
            assertTrue("too long: ${f.text}", f.text.length <= Kaomoji.MAX_LEN)
            assertTrue("would not store: ${f.text}", Kaomoji.isAcceptable(f.text))
            assertTrue("no name: ${f.text}", f.name.isNotEmpty())
            assertEquals("name must be lowercase: ${f.name}", f.name.lowercase(), f.name)
            assertTrue("group out of range: ${f.group}", f.group in Kaomoji.CATEGORIES.indices)
        }
    }

    /** A duplicate would sit twice in the grid and take the second one's place in search. */
    @Test
    fun `no face appears twice`() {
        val seen = HashSet<String>()
        for (f in Kaomoji.BUILT_IN) {
            assertTrue("duplicate: ${f.text}", seen.add(f.text))
        }
    }

    /**
     * The panel walks the set in order and starts a new category each time the group changes, so a
     * category split in two would appear in the strip twice. Sorted-by-group is the contract.
     */
    @Test
    fun `categories are contiguous and none is empty`() {
        val order = Kaomoji.BUILT_IN.map { it.group }
        assertEquals(order.sorted(), order)
        assertEquals(Kaomoji.CATEGORIES.indices.toList(), order.distinct())
    }

    // ------------------------------------------------------------------ your own

    @Test
    fun `a face with a line break is refused`() {
        assertFalse(Kaomoji.isAcceptable("(^_^)\n(x_x)"))
        assertFalse(Kaomoji.isAcceptable("(^_^)\tx"))
        assertFalse(Kaomoji.isAcceptable("   "))
        assertFalse(Kaomoji.isAcceptable("x".repeat(Kaomoji.MAX_LEN + 1)))
        assertTrue(Kaomoji.isAcceptable(" (^_^) "))
    }

    @Test
    fun `round trip keeps order`() {
        val faces = listOf("(^_^)", "¯\\_(ツ)_/¯", "(・_・)")
        assertEquals(faces, Kaomoji.parseUser(Kaomoji.serializeUser(faces)))
    }

    @Test
    fun `a repeat moves to the front rather than being added twice`() {
        var faces = Kaomoji.addUser(emptyList(), "(^_^)")
        faces = Kaomoji.addUser(faces, "(・_・)")
        faces = Kaomoji.addUser(faces, "(^_^)")
        assertEquals(listOf("(^_^)", "(・_・)"), faces)
    }

    @Test
    fun `the list is capped`() {
        var faces = emptyList<String>()
        for (i in 0 until Kaomoji.LIMIT + 10) faces = Kaomoji.addUser(faces, "(^_$i)")
        assertEquals(Kaomoji.LIMIT, faces.size)
        assertEquals("(^_${Kaomoji.LIMIT + 9})", faces.first())
    }

    @Test
    fun `an unacceptable face is not added`() {
        assertEquals(emptyList<String>(), Kaomoji.addUser(emptyList(), "two\nlines"))
    }

    @Test
    fun `a stored line that cannot be drawn is dropped rather than kept`() {
        // Not written by this app — a preference edited by hand, or a format that changed.
        assertEquals(listOf("(^_^)"), Kaomoji.parseUser("(^_^)\n\n${"x".repeat(200)}\n(^_^)"))
    }

    @Test
    fun `removing takes out every copy and leaves the rest`() {
        assertEquals(
            listOf("(^_^)"),
            Kaomoji.withoutUser(listOf("(^_^)", "(x_x)"), "(x_x)"),
        )
    }

    // ------------------------------------------------------------------ search

    private fun find(query: String): List<String> =
        Kaomoji.search(Kaomoji.BUILT_IN, query).map { Kaomoji.BUILT_IN[it].text }

    @Test
    fun `shrug finds the shrug first`() {
        assertEquals("¯\\_(ツ)_/¯", find("shrug").first())
    }

    @Test
    fun `table finds the table flip first`() {
        assertEquals("(╯°□°)╯︵ ┻━┻", find("table").first())
    }

    /** The whole point of a written name: the characters in the face say nothing about a bear. */
    @Test
    fun `a word from the middle of a name still finds it`() {
        assertTrue(find("hug").contains("ʕっ•ᴥ•ʔっ"))
    }

    @Test
    fun `one letter is not a search`() {
        assertEquals(emptyList<Int>(), Kaomoji.search(Kaomoji.BUILT_IN, "a"))
        assertEquals(emptyList<Int>(), Kaomoji.search(Kaomoji.BUILT_IN, " "))
    }

    @Test
    fun `nothing matching gives nothing`() {
        assertEquals(emptyList<Int>(), Kaomoji.search(Kaomoji.BUILT_IN, "qqqq"))
    }

    @Test
    fun `the limit is honoured`() {
        assertEquals(3, Kaomoji.search(Kaomoji.BUILT_IN, "ca", limit = 3).size)
    }

    /**
     * A face the user added has no name, so the only thing search can match on is what is in it.
     * Without the text tier this face would be unreachable by search at all.
     */
    @Test
    fun `an unnamed face is found by what is in it`() {
        val mine = Kaomoji.Face("(◕ᴗ◕✿)", "", -1)
        val hits = Kaomoji.search(listOf(mine) + Kaomoji.BUILT_IN, "◕ᴗ")
        assertEquals(listOf(0), hits)
    }

    /** An exact name beats a face that merely contains the word, whatever order they are in. */
    @Test
    fun `an exact name outranks a partial one`() {
        val faces = listOf(
            Kaomoji.Face("a", "cat wave", 0),
            Kaomoji.Face("b", "cat", 0),
        )
        assertEquals(listOf(1, 0), Kaomoji.search(faces, "cat"))
    }
}
