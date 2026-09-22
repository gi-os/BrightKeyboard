package app.lightphonekeyboard.text

/**
 * What a text field says about itself, read from `EditorInfo.inputType`, and whether a word typed
 * into it is a word at all.
 *
 * Autocorrect and the suggestion strip assume the typist is writing prose. An address bar, an email
 * field or a password box is not prose: `lightphone.com` is not a misspelling of anything, and a
 * correction there is a wrong page, a bounced email or a login that fails with no explanation.
 * Android's own keyboard reads the same bits and goes quiet on these fields; this one did not, and
 * a reader on Discord said so (2026-09-21: "the keyboard doesn't respect the input field type").
 *
 * Pure Kotlin so the rule can be tested here. The constants are `android.text.InputType`'s values,
 * spelled out because that class is not on the test classpath; they are part of the platform's
 * public contract and have not changed since API 3.
 */
object FieldKind {
    private const val MASK_CLASS = 0x0000000f
    private const val MASK_VARIATION = 0x00000ff0
    private const val CLASS_TEXT = 0x00000001
    private const val VARIATION_URI = 0x00000010
    private const val VARIATION_EMAIL_ADDRESS = 0x00000020
    private const val VARIATION_PASSWORD = 0x00000080
    private const val VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val VARIATION_FILTER = 0x000000b0
    private const val VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    private const val VARIATION_WEB_PASSWORD = 0x000000e0
    private const val FLAG_NO_SUGGESTIONS = 0x00080000

    /**
     * True when words typed into a field of this [inputType] may be corrected or completed.
     *
     * False for anything that is not a text field at all (numbers, phone, dates: the letters layer
     * is not even the default there), for the addresses and secrets listed above, for a list
     * filter (what you type is matched literally against the list), and for any field whose app
     * asked for no suggestions outright. Zero — an app that declared nothing — is treated as
     * prose, which is what it has always been.
     */
    fun correctable(inputType: Int): Boolean {
        if (inputType == 0) return true
        if (inputType and MASK_CLASS != CLASS_TEXT) return false
        if (inputType and FLAG_NO_SUGGESTIONS != 0) return false
        return when (inputType and MASK_VARIATION) {
            VARIATION_URI,
            VARIATION_EMAIL_ADDRESS,
            VARIATION_WEB_EMAIL_ADDRESS,
            VARIATION_PASSWORD,
            VARIATION_VISIBLE_PASSWORD,
            VARIATION_WEB_PASSWORD,
            VARIATION_FILTER,
            -> false
            else -> true
        }
    }
}
