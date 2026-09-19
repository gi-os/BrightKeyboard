package app.lightphonekeyboard.text

import java.text.Normalizer

/**
 * Folds a word onto the twenty-six letters the keyboard can actually decode.
 *
 * Everything downstream of a tap is a-z and only a-z: the trie branches twenty-six ways, the
 * character model is a 27x27x27 table, and the swipe encoder emits twenty-seven CTC classes. None of
 * that widens cheaply — a wider trie is a bigger file, a wider character model is cubic, and more
 * emission classes means retraining a model whose weights came from somebody else.
 *
 * It does not need to widen. A phone keyboard has no é key, and nobody wants one: you type `cafe` and
 * expect `café`. So a word list is stored twice over — folded, which is what a gesture or a sequence
 * of taps is matched against, and as written, which is what gets inserted. [UserWords] has always
 * worked this way for names; this is the same idea applied to a language.
 *
 * Two kinds of letter, and only the second needs a table:
 *
 *  - **A base letter with a mark on it** — é, ñ, ü, å, ç. Unicode decomposes these, so stripping the
 *    combining marks is all it takes and no table can fall out of date.
 *  - **A letter in its own right** — ø, æ, ß, þ. Unicode has nothing to decompose, because these are
 *    not an o or an a wearing a hat. They need [SINGLES], which is short and deliberately so.
 *
 * A fold may change a word's length (æ becomes ae). That is fine and it is why the display form is
 * stored rather than reconstructed: nothing here is reversible, and nothing here tries to be.
 */
object Folding {

    /** Letters Unicode will not decompose, because they are not decorated versions of anything. */
    private val SINGLES = mapOf(
        'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss",
        'đ' to "d", 'ð' to "d", 'ł' to "l", 'þ' to "th", 'ħ' to "h", 'ŋ' to "n", 'ı' to "i",
    )

    /**
     * The match key for [word]: lower case, marks stripped, the letters above spelled out, and
     * anything that is still not a-z or an apostrophe dropped.
     *
     * Returns an empty string for a word with nothing usable left in it, which the caller should
     * treat as "not a word this keyboard can hold" rather than as an error.
     */
    fun fold(word: String): String {
        val lower = word.lowercase()
        val sb = StringBuilder(lower.length)
        for (ch in lower) sb.append(SINGLES[ch] ?: ch)
        val stripped = Normalizer.normalize(sb, Normalizer.Form.NFD)
        val out = StringBuilder(stripped.length)
        for (ch in stripped) {
            // Mn is a non-spacing combining mark: the accent itself, now separated from its letter.
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch in 'a'..'z' || ch == '\'') out.append(ch)
            else if (ch == '’') out.append('\'')   // a typographic apostrophe is an apostrophe
        }
        return out.toString()
    }

    /** True when [word] folds to something the dictionary can hold. */
    fun foldable(word: String): Boolean = fold(word).isNotEmpty()

    /** True when folding changed nothing, so the word needs no display form of its own. */
    fun isPlain(word: String): Boolean = word == fold(word)
}
