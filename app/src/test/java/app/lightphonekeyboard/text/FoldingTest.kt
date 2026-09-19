package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per language the keyboard offers, written with real words, because the failure mode here
 * is not a crash: it is a word that can be looked up but never typed, in a language nobody reviewing
 * the change reads.
 */
class FoldingTest {

    @Test fun `plain english is left alone`() {
        assertEquals("keyboard", Folding.fold("keyboard"))
        assertEquals("don't", Folding.fold("don't"))
        assertTrue(Folding.isPlain("keyboard"))
    }

    @Test fun `spanish`() {
        assertEquals("manana", Folding.fold("mañana"))
        assertEquals("cafe", Folding.fold("café"))
        assertEquals("accion", Folding.fold("acción"))
    }

    @Test fun `french`() {
        assertEquals("etre", Folding.fold("être"))
        assertEquals("garcon", Folding.fold("garçon"))
        assertEquals("ou", Folding.fold("où"))
        assertEquals("coeur", Folding.fold("cœur"))
    }

    @Test fun `german`() {
        assertEquals("madchen", Folding.fold("Mädchen"))
        assertEquals("uber", Folding.fold("über"))
        assertEquals("strasse", Folding.fold("straße"))
    }

    @Test fun `portuguese`() {
        assertEquals("coracao", Folding.fold("coração"))
        assertEquals("nao", Folding.fold("não"))
        assertEquals("portugues", Folding.fold("português"))
    }

    @Test fun `italian`() {
        assertEquals("perche", Folding.fold("perché"))
        assertEquals("piu", Folding.fold("più"))
    }

    @Test fun `norwegian, where the letters are letters and not decorations`() {
        // Unicode decomposes å into a plus a ring, so it needs no table. ø and æ it will not touch.
        assertEquals("a", Folding.fold("å"))
        assertEquals("o", Folding.fold("ø"))
        assertEquals("ae", Folding.fold("æ"))
        assertEquals("blamann", Folding.fold("blåmann"))
        assertEquals("ol", Folding.fold("øl"))
        assertEquals("aere", Folding.fold("ære"))
    }

    @Test fun `a word can get longer, which is why the display form is stored`() {
        assertEquals(3, Folding.fold("æg").length)
        assertFalse(Folding.isPlain("æg"))
    }

    @Test fun `a typographic apostrophe is an apostrophe`() {
        assertEquals("don't", Folding.fold("don’t"))
    }

    @Test fun `anything left over is dropped, and nothing throws`() {
        assertEquals("", Folding.fold(""))
        assertEquals("", Folding.fold("123"))
        assertEquals("", Folding.fold("日本語"))
        assertEquals("", Folding.fold("  "))
        assertEquals("abc", Folding.fold("a1b2c3"))
        assertFalse(Folding.foldable("42"))
        assertTrue(Folding.foldable("café"))
    }

    @Test fun `case is not carried into the key`() {
        assertEquals("basil", Folding.fold("Basil"))
        assertEquals("ny", Folding.fold("NY"))
    }
}
