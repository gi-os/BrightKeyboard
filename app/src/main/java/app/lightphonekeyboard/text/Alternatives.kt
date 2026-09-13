package app.lightphonekeyboard.text

/**
 * Every reading of a word the keyboard can come up with, from every engine, in one ranked list — and
 * one decision about which of them is good enough to commit without being asked.
 *
 * ## Why more than one engine
 *
 * Each engine models a different way of getting a word wrong, and each is blind to the others:
 *
 * | Engine | Models | Catches | Misses |
 * |---|---|---|---|
 * | [Corrector] | a thumb landing one key over | `gello` → `hello` | anything spelled wrong on purpose |
 * | [Phonetic] | spelling a word the way it sounds | `nite` → `night` | typos that change the sound |
 * | [WordSplitter] | a space that never got typed | `alot` → `a lot` | everything else |
 * | [Shortcuts] | facts about English | `dont` → `don't` | anything not in the table |
 * | the literal | the user being right | `Lupo` staying `Lupo` | — |
 *
 * A single scorer with a wider search does not get you the same thing. Widening the spatial search
 * until it reaches `night` from `nite` also makes it reach a hundred words from every other typo, and
 * the extra candidates are all worse. Running four narrow searches and merging is both more accurate
 * and much cheaper.
 *
 * ## The two jobs this does
 *
 * 1. **Rank.** Scores from different engines are not on one scale, so each is mapped onto [Corrector]'s
 *    — log-frequency minus a weighted edit cost — and engines that are less trustworthy per unit of
 *    score are handicapped ([PHONETIC_HANDICAP], [SPLIT_HANDICAP]). Duplicates collapse, keeping the
 *    better score and remembering every engine that proposed it: two engines independently reaching
 *    the same word is strong evidence, and [AGREEMENT_BONUS] says so.
 *
 * 2. **Decide whether to commit.** [autoCommit] is the one place that answers "is this good enough to
 *    replace what the user typed", and it is deliberately a different and much stricter question than
 *    "what is the best candidate". A suggestion the user ignores is free; a correction they have to
 *    notice and undo is not. The gate is a margin over the runner-up, not an absolute score, because
 *    what makes a correction safe is that nothing else was nearly as good.
 *
 * The whole list — not just the winner — is what the delete key walks through, which is the other half
 * of why the engines are merged rather than tried in order.
 *
 * Pure logic, no Android types. [AlternativesTest] covers it.
 */
object Alternatives {

    /** Which engine proposed a candidate. A candidate may carry several. */
    object Source {
        const val SPATIAL = 1 shl 0
        const val PHONETIC = 1 shl 1
        const val SPLIT = 1 shl 2

        /** A forced [Shortcuts] entry: `dont` is `don't`. Commits at every strength. */
        const val SHORTCUT = 1 shl 3

        /**
         * An offered [Shortcuts] entry: `thx` → `thanks`, `nite` → `night`. Deliberately **not** in any
         * [Strength.committing] set, so it can sit at the front of the list — one delete press away —
         * without ever replacing what the user typed. Someone writing `nite` meant `nite`.
         */
        const val SUGGESTION = 1 shl 4

        const val LITERAL = 1 shl 5
    }

    /**
     * One reading of the typed word.
     *
     * [forced] marks a [Shortcuts] entry, which bypasses ranking entirely — `don't` is not a better
     * guess than `dont`, it is the word.
     */
    data class Candidate(
        val word: String,
        val score: Float,
        val sources: Int,
        val forced: Boolean = false,
    ) {
        fun from(source: Int): Boolean = sources and source != 0
    }

    /**
     * How hard the keyboard is allowed to try. The user picks this; it changes only [autoCommit], never
     * the list itself, so turning it down makes the keyboard quieter without making it dumber — every
     * candidate is still one delete press away.
     */
    enum class Strength(
        /** How far ahead of the runner-up the winner must be to replace what was typed. */
        val margin: Float,
        /** Which engines may commit on their own. Others only ever offer. */
        val committing: Int,
    ) {
        /** Only fixes what is unarguable: a near-miss on the keys, and the shortcut table. */
        CAUTIOUS(margin = 3.0f, committing = Source.SPATIAL or Source.SHORTCUT),

        /** The default. Adds sound-alikes and missed spaces, still with a real margin. */
        BALANCED(
            margin = 2.0f,
            committing = Source.SPATIAL or Source.SHORTCUT or Source.PHONETIC or Source.SPLIT,
        ),

        /** Commits on a slim margin. Fastest to type against, most likely to guess wrong. */
        EAGER(
            margin = 0.5f,
            committing = Source.SPATIAL or Source.SHORTCUT or Source.PHONETIC or Source.SPLIT,
        ),
    }

    /**
     * Gather and rank every reading of [typed]. The literal is always last and always present, so the
     * delete key can always get back to what was actually typed.
     *
     * Engines are passed in rather than held, because they are all owned by TextEngine and any of them
     * may still be loading — a null engine is simply one fewer source, never an error.
     */
    fun gather(
        typed: String,
        ctx: WordContext = WordContext.NONE,
        corrector: Corrector? = null,
        phonetic: PhoneticRanker? = null,
        splitter: WordSplitter? = null,
        shortcuts: Shortcuts = Shortcuts.EMPTY,
        limit: Int = MAX,
    ): List<Candidate> {
        if (typed.isEmpty()) return emptyList()
        val merged = LinkedHashMap<String, Candidate>()

        fun add(word: String, score: Float, source: Int, forced: Boolean = false) {
            if (word.isEmpty()) return
            val key = word.lowercase()
            val existing = merged[key]
            if (existing == null) {
                merged[key] = Candidate(word, score, source, forced)
            } else {
                // Two engines reaching the same word independently is the strongest signal there is.
                val sources = existing.sources or source
                val agreed = sources != existing.sources
                merged[key] = existing.copy(
                    score = maxOf(existing.score, score) + if (agreed) AGREEMENT_BONUS else 0f,
                    sources = sources,
                    forced = existing.forced || forced,
                )
            }
        }

        // Facts first. A forced shortcut is not competing with anything; an offered one goes to the
        // front of the list on its own bit, which keeps it a single delete press away and keeps
        // [autoCommit] from ever acting on it.
        shortcuts.forced(typed)?.let { add(it, FORCED_SCORE, Source.SHORTCUT, forced = true) }
        shortcuts.offered(typed)?.let { add(it, OFFERED_SCORE, Source.SUGGESTION) }

        corrector?.suggest(typed, SPATIAL_POOL, ctx, withScores = true)?.forEach { (w, s) ->
            add(w, s, Source.SPATIAL)
        }

        phonetic?.suggest(typed, PHONETIC_POOL, ctx)?.forEach { (w, s) ->
            add(w, s - PHONETIC_HANDICAP, Source.PHONETIC)
        }

        // Splits are gathered last and held to a different test, because a split is not simply another
        // candidate — it is a structurally different answer to the question. The split search has a
        // dozen cut points to choose from on a long word, so *something* usually comes back, and a
        // pair of real words is not the same thing as a phrase anyone would write. Letting those into
        // the list unconditionally does damage twice over: a bad split can win outright, and even when
        // it loses it sits in second place and blocks a good correction through the margin rule in
        // [autoCommit]. `beleave` was measured doing exactly that — `be leave` in second place kept
        // `believe` from ever committing.
        //
        // So a split has to beat the best single-word reading, not merely appear alongside it. The
        // baseline counts only what the two search engines found: a shortcut's score is a flag rather
        // than a measurement, and comparing against it would mean nothing.
        val wordBest = merged.values
            .filter { it.from(Source.SPATIAL) || it.from(Source.PHONETIC) }
            .maxOfOrNull { it.score }
        splitter?.split(typed, SPLIT_POOL, ctx)?.forEach { s ->
            val score = s.score - SPLIT_HANDICAP
            if (wordBest == null || score >= wordBest + WordSplitter.MIN_ADVANTAGE) {
                add(s.text, score, Source.SPLIT)
            }
        }

        val out = merged.values.sortedWith(
            compareByDescending<Candidate> { it.forced }.thenByDescending { it.score },
        ).filterNot { it.word.equals(typed, ignoreCase = true) }

        val capped = if (out.size > limit - 1) out.subList(0, limit - 1) else out
        // The literal, always last and always there: whatever the engines think, the user gets the
        // last word. Its score is negative infinity so it can never be ranked into the middle.
        return capped + Candidate(typed, Float.NEGATIVE_INFINITY, Source.LITERAL)
    }

    /**
     * The candidate to commit without asking, or null to leave the word exactly as typed.
     *
     * Three things have to hold, and the order matters because each is cheaper than the last:
     *
     *  1. A forced shortcut wins outright — `dont` is `don't` and no margin applies.
     *  2. The winner's engine must be allowed to commit at this [strength].
     *  3. The winner must clear the runner-up by [Strength.margin]. This is the guard that matters:
     *     a correction is safe when it is the *only* good reading, not when it is a good one. Two
     *     plausible candidates within a hair of each other means the keyboard does not know, and the
     *     honest move is to leave the word alone and let the delete key offer both.
     */
    fun autoCommit(
        candidates: List<Candidate>,
        typed: String,
        strength: Strength = Strength.BALANCED,
    ): Candidate? {
        val real = candidates.filterNot { it.from(Source.LITERAL) }
        val best = real.firstOrNull() ?: return null
        if (best.word.equals(typed, ignoreCase = true)) return null
        if (best.forced) return best
        if (best.sources and strength.committing == 0) return null
        // A split rewrites the shape of the sentence, so it answers to its own, higher bar as well.
        if (best.from(Source.SPLIT) && !best.from(Source.SPATIAL)) {
            val runnerUp = real.getOrNull(1)?.score ?: Float.NEGATIVE_INFINITY
            if (best.score - runnerUp < WordSplitter.MIN_ADVANTAGE) return null
        }
        val runnerUp = real.getOrNull(1)?.score ?: return best
        return if (best.score - runnerUp >= strength.margin) best else null
    }

    /** How many readings to keep. Enough that the delete key has somewhere to go, few enough to rank. */
    const val MAX = 6

    private const val SPATIAL_POOL = 4
    private const val PHONETIC_POOL = 3
    private const val SPLIT_POOL = 2

    /**
     * Sound-alikes and splits are scored on the same scale as spatial candidates but are not equally
     * trustworthy per unit of score, so they start behind. These are what to adjust if one engine
     * starts winning arguments it should be losing.
     *
     * The split handicap is the larger of the two because the split search has so much more freedom:
     * a long word offers a dozen cut points, so *something* almost always comes back, and a pair of
     * real words is not the same thing as a phrase anyone writes. Measured at 1.0, `beleave` came back
     * as `be leave` ahead of `believe` — two real words, a grammatical sequence, and not what anybody
     * meant. At 3.0 a split has to be clearly better than the best single word rather than merely
     * available.
     */
    private const val PHONETIC_HANDICAP = 1.0f
    private const val SPLIT_HANDICAP = 3.0f

    /**
     * Paid once when a second engine independently proposes a word another already had.
     *
     * Worth more than it looks. The engines share a dictionary and nothing else — one measures key
     * distance, one measures pronunciation — so agreement between them is not two views of the same
     * evidence, it is two kinds of evidence pointing the same way, and it is rare enough that it means
     * something when it happens. `beleave` is two letters from `leave` and sounds exactly like
     * `believe`; without this the two score within a hair of each other and the margin rule correctly
     * refuses to choose, leaving an obvious typo standing.
     */
    private const val AGREEMENT_BONUS = 3.0f

    /** Above anything a scorer produces, so a forced shortcut sorts first without special-casing. */
    private const val FORCED_SCORE = 1000f

    /**
     * Below [FORCED_SCORE] and above every scorer, so an offered expansion is the first thing the
     * delete key reaches. It is kept out of auto-commit by its source bit, not by its score — a
     * suggestion the user has to go and find is not much of a suggestion.
     */
    private const val OFFERED_SCORE = 500f
}
