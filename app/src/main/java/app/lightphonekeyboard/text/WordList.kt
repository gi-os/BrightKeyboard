package app.lightphonekeyboard.text

/**
 * Reads a word list somebody hands the keyboard: a plain text file, one word to a line.
 *
 * The personal list is for the handful of names you add by hand. This is for the other case — a
 * glossary, a field's jargon, the cast of something you write about constantly, a vocabulary list —
 * where the words already exist somewhere and typing them in one at a time is not a real offer.
 *
 * Deliberately the dullest format that could work, because the point is that a list you already have
 * is usable without editing it. One word per line. A `#` at the start of a line is a comment. A tab
 * or a run of spaces splits a word from a count, if the file has counts, and the count is ignored:
 * these are your words, so the keyboard treats them as equally yours rather than pretending to know
 * which you mean more often.
 *
 * Everything is checked here rather than at the file picker, so the rule lives in one place and a
 * file that is half usable imports the half that is.
 */
object WordList {

    /** Longest file worth reading, in lines. A list past this is a corpus, not a vocabulary. */
    const val MAX_LINES = 200_000

    /** Most words kept. Beyond this the suggestion strip is picking from noise. */
    const val MAX_WORDS = 60_000

    class Result(
        /** Accepted words, as written, in file order and deduplicated by their folded key. */
        val words: List<String>,
        /** Lines that held something but nothing this keyboard can type. */
        val rejected: Int,
    )

    fun parse(lines: Sequence<String>): Result {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        var rejected = 0
        var read = 0
        for (raw in lines) {
            if (read++ >= MAX_LINES || out.size >= MAX_WORDS) break
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // Split a trailing count off, if there is one. Anything after the first field goes.
            val word = line.split('\t', ' ', ',', ';').firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (word.isEmpty()) continue
            if (!UserWords.isAcceptable(word)) { rejected++; continue }
            val key = Folding.fold(word)
            // A word that folds to nothing cannot be typed on this keyboard, so it cannot be offered.
            if (key.isEmpty() || key.length > UserWords.MAX_LENGTH) { rejected++; continue }
            if (!seen.add(key)) continue
            out.add(word)
        }
        return Result(out, rejected)
    }

    fun parse(text: String): Result = parse(text.lineSequence())
}
