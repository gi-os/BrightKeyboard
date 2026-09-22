package app.lightphonekeyboard.text

/**
 * Text faces — kaomoji — and the ones the user has written down themselves.
 *
 * An emoji is one code point the font either has or does not. A kaomoji is a *sentence* of
 * punctuation, kana, box drawing and whatever else the face needs, and that difference runs through
 * everything here: it cannot be searched by a Unicode name because it has none, it cannot be
 * filtered by one [android.graphics.Paint.hasGlyph] call because it is many glyphs, and it cannot be
 * drawn at a fixed size because `^_^` and `(╯°□°)╯︵ ┻━┻` are five times apart in width.
 *
 * So each face carries a written name, and the name is the only thing search has to work with. That
 * name is doing the same job CLDR's keywords do for emoji: nobody types "reversed hand with middle
 * finger extended", they type `shrug` and expect `¯\_(ツ)_/¯`.
 *
 * Pure Kotlin, no Android types, like the rest of this package — [KaomojiTest] runs on it directly.
 * The font question is the panel's ([app.lightphonekeyboard.KaomojiPanel], which checks the face a
 * code point at a time).
 */
object Kaomoji {

    /** A face and the words that find it. [group] indexes [CATEGORIES]. */
    data class Face(val text: String, val name: String, val group: Int)

    /**
     * The built-in categories, in panel order.
     *
     * Seven, by what a face is *for* rather than by what is in it. "Unsure" holds the shrugs and the
     * confused stares together because they answer the same message, and a category called "Shrug"
     * with four entries in it is a category nobody scrolls to.
     */
    val CATEGORIES = listOf("Happy", "Love", "Sad", "Angry", "Unsure", "Animals", "Doing")

    /** Longest face kept, in characters. Past this it is a drawing, not a face, and no cell holds it. */
    const val MAX_LEN = 40

    /** How many of your own are kept. A ceiling so nothing here is unbounded, not a policy. */
    const val LIMIT = 60

    /** Below this a query matches nearly everything, which is not a search. Matches [Emoji.MIN_QUERY]. */
    const val MIN_QUERY = 2

    // ------------------------------------------------------------------ your own

    /**
     * True when [text] can be stored and drawn.
     *
     * A line break is the one thing refused outright rather than trimmed: the store is one face a
     * line, and a cell is one line tall, so a two-line face would be saved as two faces and drawn as
     * its first half. Refusing it is honest; silently keeping half of it is not.
     */
    fun isAcceptable(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.length <= MAX_LEN && t.none { it == '\n' || it == '\r' || it == '\t' }
    }

    /**
     * Your own faces, newest first.
     *
     * One a line and nothing escaped, which is only safe because [isAcceptable] has already refused
     * every character that could break the format. A line that got in some other way and does not
     * parse is dropped rather than allowed to swallow the rest of the file.
     */
    fun parseUser(stored: String?): List<String> {
        if (stored.isNullOrEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (line in stored.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() || t.length > MAX_LEN || t in out) continue
            out.add(t)
            if (out.size >= LIMIT) break
        }
        return out
    }

    fun serializeUser(faces: List<String>): String = faces.joinToString("\n")

    /** [text] at the front. An exact repeat is moved rather than added, as in [Clips.add]. */
    fun addUser(faces: List<String>, text: String): List<String> {
        val t = text.trim()
        if (!isAcceptable(t)) return faces
        val out = ArrayList<String>(minOf(faces.size + 1, LIMIT))
        out.add(t)
        for (f in faces) {
            if (out.size >= LIMIT) break
            if (f != t) out.add(f)
        }
        return out
    }

    fun withoutUser(faces: List<String>, text: String): List<String> = faces.filter { it != text }

    // ------------------------------------------------------------------ search

    /**
     * Indices into [faces] matching [query], best first.
     *
     * Ranked rather than filtered, on the same tiers [Emoji.search] uses and for the same reason:
     * `cat` matches the cat, the cat wave and every face whose name happens to contain "cat", and
     * the plain one has to come first. Within a tier the given order breaks the tie, which keeps a
     * category's own order and puts your own faces — which lead the list — ahead of the built-ins.
     *
     * A face with no name at all is still searchable by what is *in* it, which is how a user face
     * (whose name is empty) is found at all: typing `ツ` finds every face with a ツ in it.
     */
    fun search(faces: List<Face>, query: String, limit: Int = 24): List<Int> {
        val q = query.trim().lowercase()
        if (q.length < MIN_QUERY || limit <= 0) return emptyList()
        val scored = ArrayList<IntArray>()
        for (i in faces.indices) {
            val rank = rankOf(faces[i], q)
            if (rank == NO_MATCH) continue
            scored.add(intArrayOf(rank, i))
        }
        scored.sortWith(compareBy({ it[0] }, { it[1] }))
        return scored.take(limit).map { it[1] }
    }

    private fun rankOf(face: Face, q: String): Int {
        val name = face.name
        if (name == q) return 0
        if (startsWithWord(name, q)) return 1
        if (name.contains(q)) return 2
        if (face.text.contains(q)) return 3
        return NO_MATCH
    }

    /** True when any space-separated word of [haystack] begins with [q]. */
    private fun startsWithWord(haystack: String, q: String): Boolean {
        var at = 0
        while (at < haystack.length) {
            if (haystack.startsWith(q, at)) return true
            val next = haystack.indexOf(' ', at)
            if (next < 0) return false
            at = next + 1
        }
        return false
    }

    private const val NO_MATCH = Int.MAX_VALUE

    // ------------------------------------------------------------------ the set

    private fun face(group: Int, text: String, name: String) = Face(text, name, group)

    /**
     * The bundled faces.
     *
     * Written out here rather than generated into an asset the way `emoji.bin` is, and that is a
     * deliberate difference. There is no Unicode file listing kaomoji and no CLDR file naming them —
     * the set is editorial, a few hundred entries, and every name is a judgement about what somebody
     * would type to find it. A generator would only be a second copy of this list.
     */
    val BUILT_IN: List<Face> = listOf(
        // Happy
        face(0, "(＾▽＾)", "happy smile"),
        face(0, "(^_^)", "smile plain"),
        face(0, "(・∀・)", "grin"),
        face(0, "(≧▽≦)", "laughing delighted"),
        face(0, "(☆▽☆)", "excited stars"),
        face(0, "(￣▽￣)", "pleased smug"),
        face(0, "(⌒‿⌒)", "content"),
        face(0, "(*^▽^*)", "joyful"),
        face(0, "(°▽°)", "bright cheerful"),
        face(0, "(•‿•)", "pleased simple"),
        face(0, "(¬‿¬)", "smirk sly"),
        face(0, "(◠‿◠)", "warm smile"),
        face(0, "(＾◡＾)", "soft smile"),
        face(0, "(✿◠‿◠)", "flower smile"),
        face(0, "(*≧ω≦*)", "squee thrilled"),
        face(0, "ヾ(≧▽≦*)o", "cheering laugh"),
        face(0, "(´∀｀)", "easy smile"),
        face(0, "(o^▽^o)", "delighted"),
        face(0, "( ͡° ͜ʖ ͡°)", "lenny knowing"),
        face(0, "(＾ｖ＾)", "cheery"),
        face(0, "(๑˃ᴗ˂)", "gleeful"),
        face(0, "(￣ω￣)", "smug quiet"),

        // Love
        face(1, "(♡°▽°♡)", "love struck"),
        face(1, "(♥ω♥)", "heart eyes"),
        face(1, "(*♡∀♡)", "in love"),
        face(1, "(づ￣ ³￣)づ", "kiss hug"),
        face(1, "(っ◕‿◕)っ", "hug"),
        face(1, "♡(◕‿◕)♡", "hearts"),
        face(1, "(˘⌣˘)", "content kiss"),
        face(1, "♡＾▽＾♡", "loving smile"),
        face(1, "(´∀｀)♡", "affection"),
        face(1, "(ღ˘⌣˘ღ)", "romantic"),
        face(1, "(*￣3￣)╭", "kiss blowing"),
        face(1, "(❤ω❤)", "adoring"),
        face(1, "(≧◡≦) ♡", "sweet"),
        face(1, "(っ˘з˘)っ", "kissing"),

        // Sad
        face(2, "(；_；)", "crying"),
        face(2, "(╥﹏╥)", "sobbing"),
        face(2, "(T_T)", "tears"),
        face(2, "(ಥ﹏ಥ)", "weeping"),
        face(2, "(ノ_<。)", "crying wiping"),
        face(2, "(ಥ_ಥ)", "cry"),
        face(2, "(◞‸◟)", "dejected"),
        face(2, "( ˘︹˘ )", "unhappy frown"),
        face(2, "(╯︵╰,)", "heartbroken"),
        face(2, "(-_-)", "disappointed"),
        face(2, "(=_=)", "weary tired"),
        face(2, "(´Д｀)", "distressed"),
        face(2, "(×_×)", "defeated"),
        face(2, "(´-ω-)", "gloomy"),
        face(2, "(っ- ‸ - ς)", "sulking"),
        face(2, "(´･_･)", "downcast"),

        // Angry
        face(3, "(╯°□°)╯︵ ┻━┻", "table flip rage"),
        face(3, "┬─┬ノ( º _ ºノ)", "table back calm"),
        face(3, "(ﾉಥ益ಥ)ﾉ", "rage tears"),
        face(3, "(╬ಠ益ಠ)", "furious"),
        face(3, "(ㆆ_ㆆ)", "annoyed"),
        face(3, "(¬_¬)", "unimpressed"),
        face(3, "(－‸ლ)", "facepalm"),
        face(3, "(≖_≖)", "glare suspicious"),
        face(3, "凸(￣ヘ￣)", "rude"),
        face(3, "(＞﹏＜)", "frustrated"),
        face(3, "(҂◡_◡)", "beaten up"),
        face(3, "ヽ(｀Д´)ﾉ", "yelling angry"),
        face(3, "(＃´Д｀)", "mad"),

        // Unsure
        face(4, "¯\\_(ツ)_/¯", "shrug whatever"),
        face(4, "┐(´ー｀)┌", "shrug dunno"),
        face(4, "╮(╯_╰)╭", "shrug sigh"),
        face(4, "(・_・;)", "nervous unsure"),
        face(4, "(°ロ°)", "shocked"),
        face(4, "(⊙_⊙)", "surprised stare"),
        face(4, "(o_O)", "confused"),
        face(4, "(ʘ_ʘ)", "wide eyed"),
        face(4, "(・・?", "puzzled question"),
        face(4, "(￣ヘ￣;)", "doubtful"),
        face(4, "(¬､¬)", "skeptical side eye"),
        face(4, "(⊙﹏⊙)", "worried"),
        face(4, "(；・∀・)", "awkward"),
        face(4, "(@_@)", "dizzy"),
        face(4, "(´･ω･)?", "curious"),
        face(4, "(°ロ°)☝", "idea"),
        face(4, "(￣～￣;)", "hesitant"),

        // Animals
        face(5, "ʕ•ᴥ•ʔ", "bear"),
        face(5, "ʕっ•ᴥ•ʔっ", "bear hug"),
        face(5, "ʕ•́ᴥ•̀ʔ", "bear grumpy"),
        face(5, "(=^･ω･^=)", "cat"),
        face(5, "(=^‥^=)", "cat face"),
        face(5, "(＾• ω •＾)", "kitten"),
        face(5, "(=ↀωↀ=)", "cat cool"),
        face(5, "~(=^‥^)ノ", "cat wave"),
        face(5, "(=･ｪ･=)", "cat whiskers"),
        face(5, "(•ㅅ•)", "bunny rabbit"),
        face(5, "(´･ᴗ･ )", "soft creature"),
        face(5, "(°ㅂ°)", "bird"),
        face(5, "(・⊝・)", "bird beak"),
        face(5, "くコ:彡", "squid"),
        face(5, "＜°))))彡", "fish"),
        face(5, "(ᵔᴥᵔ)", "dog happy"),
        face(5, "ʕ￫ᴥ￩ʔ", "bear squish"),

        // Doing
        face(6, "ヽ(•‿•)ノ", "cheer hooray"),
        face(6, "(ノ^_^)ノ", "hands up throw"),
        face(6, "ᕕ( ᐛ )ᕗ", "running"),
        face(6, "(~˘▾˘)~", "dancing"),
        face(6, "♪~ ᕕ(ᐛ)ᕗ", "dancing music"),
        face(6, "(＾-＾)ノ", "waving hi"),
        face(6, "(・ω・)ノ", "hello wave"),
        face(6, "(－ω－) zzZ", "sleeping"),
        face(6, "(-_-)zzz", "asleep"),
        face(6, "＼(^o^)／", "celebrate"),
        face(6, "ヽ(⌐■_■)ノ♪", "cool dance"),
        face(6, "(ง'̀-'́)ง", "fight ready"),
        face(6, "(っ˘ڡ˘ς)", "yummy tasty"),
        face(6, "(・ω・)b", "thumbs up ok"),
        face(6, "(＾＾)b", "good job"),
        face(6, "m(_ _)m", "sorry bow thanks"),
        face(6, "_(:3」∠)_", "lying down lazy"),
        face(6, "(๑•̀ㅂ•́)و✧", "determined"),
        face(6, "(☞ﾟヮﾟ)☞", "pointing you"),
        face(6, "☜(ﾟヮﾟ☜)", "pointing left"),
        face(6, "(⌐■_■)", "cool sunglasses"),
        face(6, "(*・ω・)ﾉ", "waving bye"),
        face(6, "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "sparkles magic"),
        face(6, "✌(◕‿-)✌", "peace wink"),
        face(6, "(^_-)≡☆", "wink shot"),
        face(6, "(・_・)ゞ", "salute"),
        face(6, "(⌒▽⌒)☆", "star pose"),
    )
}
