package app.lightphonekeyboard.text

/**
 * When an absent stored model means "somebody cleared it" rather than "nothing has been written yet".
 *
 * Four lines, in their own file, for the same reason [CrashBreaker] is: the keyboard cannot be made
 * to exercise this on a device without deliberately breaking one, and getting it wrong costs either
 * a reset that does not stick or a keyboard that never learns anything. It shipped wrong once — see
 * [clearedBySettings].
 */
object ModelStore {

    /**
     * True when the stored model is gone *and* this view is the reason it was ever there, which is
     * the only case where the copy in memory should be thrown away too.
     *
     * [saved] is the load-bearing argument. Without it, a pref that is absent because it has never
     * been written looks exactly like one the settings page just cleared, and since the same branch
     * disarms saving, the pref stays absent and the branch fires again on the next field, for ever.
     * v3.1.45 shipped that way: every fresh install lost its model at the end of the first field it
     * typed in, and learned nothing afterwards.
     */
    fun clearedBySettings(loaded: Boolean, saved: String?, stored: String?): Boolean =
        loaded && saved != null && stored == null
}
