package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether to load the swipe model.
 *
 * Written as the sequence a phone actually goes through, because that is the only way the
 * off-by-ones show themselves: a rule can be individually right at every input and still give a
 * user three crashes, or none and no model either.
 */
class CrashBreakerTest {

    private val max = 2

    @Test
    fun `a clean phone tries, and keeps trying`() {
        var strikes = 0
        repeat(5) {
            val out = CrashBreaker.decide(armed = false, strikes = strikes, max = max)
            assertTrue(out.proceed)
            assertEquals("a launch with no crash must not count as one", 0, out.strikes)
            strikes = out.strikes
        }
    }

    /**
     * The sequence that matters: crash, crash, stop. Two is the most the user ever sees, and the
     * third launch is the one that has to refuse.
     */
    @Test
    fun `two crashes and it gives up`() {
        // Launch 1: nothing recorded yet, so it tries. (It then dies, leaving the flag armed.)
        var out = CrashBreaker.decide(armed = false, strikes = 0, max = max)
        assertTrue(out.proceed)

        // Launch 2: the flag was still set. One crash recorded, and it is allowed one more go.
        out = CrashBreaker.decide(armed = true, strikes = out.strikes, max = max)
        assertEquals(1, out.strikes)
        assertTrue("one crash must not be enough to give up", out.proceed)

        // Launch 3: still set. That is two, and it stops.
        out = CrashBreaker.decide(armed = true, strikes = out.strikes, max = max)
        assertEquals(2, out.strikes)
        assertFalse(out.proceed)
    }

    @Test
    fun `once it has given up it stays given up, crash or no crash`() {
        assertFalse(CrashBreaker.decide(armed = false, strikes = 2, max = max).proceed)
        assertFalse(CrashBreaker.decide(armed = true, strikes = 2, max = max).proceed)
        assertFalse(CrashBreaker.decide(armed = false, strikes = 9, max = max).proceed)
    }

    /** A crash after a clean run is still the first one. The count is of crashes, not of launches. */
    @Test
    fun `a clean launch between crashes does not forgive one`() {
        var out = CrashBreaker.decide(armed = true, strikes = 0, max = max)
        assertEquals(1, out.strikes)
        out = CrashBreaker.decide(armed = false, strikes = out.strikes, max = max)
        assertEquals(1, out.strikes)
        assertTrue(out.proceed)
        out = CrashBreaker.decide(armed = true, strikes = out.strikes, max = max)
        assertEquals(2, out.strikes)
        assertFalse(out.proceed)
    }

    /** Clearing the strikes is what the settings toggle does, and it has to restore the first state. */
    @Test
    fun `clearing the count starts over`() {
        assertTrue(CrashBreaker.decide(armed = false, strikes = 0, max = max).proceed)
    }

    @Test
    fun `a max of one gives up on the first crash`() {
        assertTrue(CrashBreaker.decide(armed = false, strikes = 0, max = 1).proceed)
        assertFalse(CrashBreaker.decide(armed = true, strikes = 0, max = 1).proceed)
    }
}
