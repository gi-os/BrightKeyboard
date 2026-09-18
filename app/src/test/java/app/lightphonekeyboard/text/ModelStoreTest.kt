package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Written as the sequence a phone actually goes through, because that is the only way this shows
 * itself: the rule is right at every individual input and still lost every new user their model.
 */
class ModelStoreTest {

    /** The view's two fields, and what the keyboard does with them at each field change. */
    private class Keyboard {
        var loaded = false
        var saved: String? = null
        var stored: String? = null
        var model = 0                        // stands in for what has been learned

        fun type(taps: Int) { model += taps }

        /** LightKeyboardView.reset(), the part that matters. */
        fun nextField() {
            if (ModelStore.clearedBySettings(loaded, saved, stored)) {
                model = 0; saved = null; loaded = false
            }
            save()
            load()                           // reset() ends in rebuild(), which relayouts and loads
        }

        fun save() {
            if (!loaded) return
            val s = model.toString()
            if (s == saved) return
            saved = s; stored = s
        }

        fun load() {
            if (loaded) return
            loaded = true
            stored?.let { saved = it; model = it.toInt() }
        }

        /** The settings page: clears the stored copy and the live one, in that order. */
        fun resetFromSettings() { model = 0; stored = null }
    }

    @Test
    fun `a fresh install keeps what it learns`() {
        val k = Keyboard()
        k.load()
        k.type(50)
        k.nextField()
        assertEquals("the first field's taps were thrown away", 50, k.model)
        k.type(30)
        k.nextField()
        assertEquals(80, k.model)
        assertEquals("80", k.stored)
    }

    @Test
    fun `a fresh install is still saving after ten fields`() {
        val k = Keyboard()
        k.load()
        repeat(10) { k.type(5); k.nextField() }
        assertTrue("saving was disarmed somewhere in the run", k.loaded)
        assertEquals(50, k.model)
    }

    @Test
    fun `a reset from settings sticks`() {
        val k = Keyboard()
        k.load()
        k.type(200)
        k.nextField()
        k.resetFromSettings()
        k.nextField()
        assertEquals("the keyboard wrote its copy back over the reset", 0, k.model)
        // The pref is deliberately still absent here: the field that notices the reset is the one
        // that disarms saving, so nothing is written until the field after it. What matters is that
        // the branch does not fire a second time and that the next save goes through.
        assertEquals(null, k.stored)
        k.nextField()
        assertEquals("0", k.stored)
    }

    @Test
    fun `and the keyboard learns again afterwards`() {
        val k = Keyboard()
        k.load()
        k.type(200); k.nextField()
        k.resetFromSettings(); k.nextField()
        k.type(40); k.nextField()
        assertEquals(40, k.model)
        assertEquals("40", k.stored)
    }

    @Test
    fun `a model is never saved before it has been read`() {
        // onStartInputView calls reset() before the view is attached, so the first save of a process
        // must do nothing or it writes an empty model over a real one.
        val k = Keyboard()
        k.stored = "900"
        k.save()
        assertEquals("an unloaded view wrote over the stored model", "900", k.stored)
    }

    @Test
    fun `the rule itself`() {
        assertFalse("nothing written yet is not a reset",
            ModelStore.clearedBySettings(loaded = true, saved = null, stored = null))
        assertTrue("this one is",
            ModelStore.clearedBySettings(loaded = true, saved = "x", stored = null))
        assertFalse("a model that is still there is not a reset",
            ModelStore.clearedBySettings(loaded = true, saved = "x", stored = "x"))
        assertFalse("nothing to throw away before the load",
            ModelStore.clearedBySettings(loaded = false, saved = "x", stored = null))
    }
}
