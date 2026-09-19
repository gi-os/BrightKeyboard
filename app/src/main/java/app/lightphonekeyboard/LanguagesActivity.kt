package app.lightphonekeyboard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Which dictionaries are switched on, and where to get more.
 *
 * Two lists. What is on the phone, each with a tick you can turn on and off, and a search over the
 * hundred-odd word lists published at codeberg.org/Helium314/aosp-dictionaries. Nothing ships
 * installed beyond English, and nothing is rehosted: the phone asks that project what it has and
 * fetches the one you pick straight from it.
 *
 * More than one can be on at once. That is an honest trade and worth knowing about: each list says
 * how common a word is *within its own language*, so two at once asserts that either could be the one
 * being typed. Anyone switching on a second language is making that claim about themselves, which is
 * why this is a tick rather than a single choice.
 */
class LanguagesActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var installedList: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText
    private var catalogue: List<AospRepo.Item> = emptyList()
    private var busy = false
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, pad, side, pad)
            setBackgroundColor(getColor(R.color.black))
        }

        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.lang_title), 28f, R.color.white))
        root.addView(label(getString(R.string.lang_blurb), 14f, R.color.gray))

        installedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(installedList)

        root.addView(label(getString(R.string.lang_add), 20f, R.color.white))
        root.addView(label(getString(R.string.lang_add_blurb), 13f, R.color.gray))

        search = EditText(this).apply {
            hint = getString(R.string.lang_search)
            setHintTextColor(getColor(R.color.gray))
            setTextColor(getColor(R.color.white))
            textSize = 20f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            setPadding(0, pad / 2, 0, pad / 2)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = showResults()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search)

        status = label("", 14f, R.color.gray)
        root.addView(status)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)

        root.addView(label(getString(R.string.lang_note), 13f, R.color.gray))

        setContentView(
            LightScrollView(this).apply {
                setBackgroundColor(getColor(R.color.black))
                addView(root)
            },
        )
        refreshInstalled()
        loadCatalogue()
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(color))
        textSize = size
        setPadding(0, pad / 4, 0, pad / 4)
    }

    // ---------------------------------------------------------------- what is on the phone

    private fun refreshInstalled() {
        installedList.removeAllViews()
        val active = Prefs.languages(this)
        val codes = ArrayList<String>()
        codes.add(LangPack.ENGLISH)
        codes.addAll(LangPack.AVAILABLE.map { it.code }.filter { LangPack.isInstalled(this, it) })
        for (code in Prefs.languages(this)) {
            if (code !in codes && LangPack.isInstalled(this, code)) codes.add(code)
        }
        for (code in installedExtras()) if (code !in codes) codes.add(code)
        for (code in codes) installedRow(code, code in active)
    }

    /** Anything installed from the repository, which is not in the curated list. */
    private fun installedExtras(): List<String> {
        val packs = java.io.File(filesDir, "packs")
        return packs.listFiles()?.filter { it.isDirectory && !it.name.endsWith(".part") }
            ?.map { it.name }?.filter { LangPack.isInstalled(this, it) }?.sorted().orEmpty()
    }

    private fun installedRow(code: String, on: Boolean) {
        val name = if (code == LangPack.ENGLISH) getString(R.string.lang_english)
        else LangPack.AVAILABLE.firstOrNull { it.code == code }?.name ?: AospRepo.displayName(code)
        val tick = TextView(this).apply {
            text = if (on) "✓" else ""
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        val title = TextView(this).apply {
            text = name
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        val sub = TextView(this).apply {
            text = if (code == LangPack.ENGLISH) getString(R.string.lang_builtin)
            else getString(R.string.lang_hold_to_remove)
            setTextColor(getColor(R.color.gray))
            textSize = 13f
        }
        installedList.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 4, 0, pad / 4)
                isClickable = true
                setOnClickListener { toggle(code) }
                setOnLongClickListener { removeInstalled(code); true }
                addView(
                    LinearLayout(this@LanguagesActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(title)
                        addView(sub)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(tick)
            },
        )
    }

    /** Switching everything off would leave nothing to type against, so English comes back on. */
    private fun toggle(code: String) {
        val set = Prefs.languages(this).toMutableSet()
        if (!set.remove(code)) set.add(code)
        if (set.isEmpty()) set.add(LangPack.ENGLISH)
        Prefs.setLanguages(this, set)
        refreshInstalled()
    }

    private fun removeInstalled(code: String) {
        if (busy || code == LangPack.ENGLISH) return
        LangPack.remove(this, code)
        val set = Prefs.languages(this).toMutableSet()
        set.remove(code)
        Prefs.setLanguages(this, if (set.isEmpty()) setOf(LangPack.ENGLISH) else set)
        refreshInstalled()
        showResults()
    }

    // ---------------------------------------------------------------- finding more

    private fun loadCatalogue() {
        status.text = getString(R.string.lang_loading)
        Thread({
            val items = AospRepo.list()
            main.post {
                catalogue = items
                status.text = if (items.isEmpty()) getString(R.string.lang_offline)
                else getString(R.string.lang_found, items.size)
                showResults()
            }
        }, "aosp-list").start()
    }

    /** Only the first handful: a list of eighty rows on a 3.9 inch screen is not a list, it is a wall. */
    private fun showResults() {
        results.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val matches = catalogue
            .filter { !LangPack.isInstalled(this, it.code) }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.startsWith(q) }
            .take(if (q.isEmpty()) 6 else 12)
        for (item in matches) resultRow(item)
    }

    private fun resultRow(item: AospRepo.Item) {
        val title = TextView(this).apply {
            text = item.name
            setTextColor(getColor(R.color.white))
            textSize = 20f
        }
        val sub = TextView(this).apply {
            text = getString(R.string.lang_size, item.bytes / 1_000_000f)
            setTextColor(getColor(R.color.gray))
            textSize = 13f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 4, 0, pad / 4)
            isClickable = true
            addView(
                LinearLayout(this@LanguagesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(sub)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        row.setOnClickListener { install(item, sub) }
        results.addView(row)
    }

    private fun install(item: AospRepo.Item, sub: TextView) {
        if (busy) return
        busy = true
        sub.text = getString(R.string.lang_downloading)
        Thread({
            // The curated six are built and hosted; everything else is parsed from the repository's
            // own word list on the phone, which is also what builds its character model.
            val curated = LangPack.AVAILABLE.any { it.code == item.code }
            val error = if (curated) LangPack.download(this, item.code)
            else LangPack.installCombined(this, item.code, item.name, item.url)
            main.post {
                busy = false
                if (error == null) {
                    val set = Prefs.languages(this).toMutableSet()
                    set.add(item.code)
                    Prefs.setLanguages(this, set)
                    search.setText("")
                    refreshInstalled()
                    showResults()
                } else {
                    sub.text = error
                }
            }
        }, "install-${item.code}").start()
    }
}
