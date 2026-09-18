package app.lightphonekeyboard.text

/**
 * Whether to try loading something that might take the whole process down with it.
 *
 * The swipe model is a native library. A fault inside it raises a signal, not an exception: the
 * process is gone, and no `catch` anywhere in this app runs. The only way to know it happened is to
 * write a flag to disk *before* the attempt and see, on the next launch, whether anything ever
 * cleared it — nothing else can leave it set, because the clearing is unconditional.
 *
 * This is the rule that reads the two numbers. Four lines, and every one of them is an off-by-one
 * waiting to happen: a rule that gives up one attempt too early takes a working feature away from
 * everybody, and one that gives up too late is a keyboard that dies in every text field on the
 * phone, over and over, including the ones needed to turn it off. Neither can be tested on a device
 * without deliberately crashing one, so it lives here, free of Android, with a test.
 *
 * See [app.lightphonekeyboard.SwipeEncoder] for the flag itself.
 */
object CrashBreaker {

    /**
     * [strikes] is the count to store, whether or not it changed. [proceed] is whether to attempt
     * the load at all.
     */
    data class Outcome(val strikes: Int, val proceed: Boolean)

    /**
     * @param armed what was found on disk: true means the last attempt never came back.
     * @param strikes how many crashes have been recorded before this launch.
     * @param max how many to tolerate before leaving it alone.
     */
    fun decide(armed: Boolean, strikes: Int, max: Int): Outcome {
        // A launch that finds the flag set is a launch after a crash, and that is the only thing
        // that increments the count. Two launches in a row without one leave it exactly as it was:
        // the count is of crashes, not of attempts.
        val now = if (armed) strikes + 1 else strikes
        return Outcome(now, now < max)
    }
}
