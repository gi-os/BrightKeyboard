package app.lightphonekeyboard

import app.lightphonekeyboard.text.TouchModel

/**
 * Lends the live touch model to the settings screen that draws it.
 *
 * The keyboard and the settings screen are the same app in the same process on the same main thread,
 * so this is a plain reference and a plain callback, read and written from one thread only. Nothing
 * here is synchronized and nothing here should be: the moment it is touched from anywhere else, that
 * stops being true and the fix is a message to the main thread, not a lock.
 *
 * It is a window onto the keyboard's own object rather than a copy. A copy would go stale on the
 * first tap, which is the one thing [TouchActivity] exists to show.
 *
 * Both fields are cleared by whoever set them. The view clears [model] when it detaches and the
 * activity clears [onChange] when it stops, so neither outlives the other and a finished activity
 * cannot be called back into.
 */
object TouchInsight {

    /** Set by [LightKeyboardView] while it is attached and its model has been loaded. */
    var model: TouchModel? = null

    /** Set by [TouchActivity] while it is on screen. */
    var onChange: (() -> Unit)? = null

    /** Set by [LightKeyboardView] while it is attached. */
    var onRefresh: (() -> Unit)? = null

    /** The keyboard saying a tap has just moved something. */
    fun changed() {
        onChange?.invoke()
    }

    /**
     * The settings page saying a toggle moved. The keyboard caches these prefs with the rest, in
     * applyPrefs, which only runs when a field changes — and the field the typist is looking at is
     * the one on the settings page, so without this a toggle would do nothing until they left it.
     */
    fun refresh() {
        onRefresh?.invoke()
    }
}
