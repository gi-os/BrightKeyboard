package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Moving BrightKeyboard off an applicationId that was never ours to take.
 *
 * This keyboard is a fork of `adam-weber/light-keyboard`, and the fork kept the upstream's
 * applicationId. Android identifies an app by (applicationId, signing certificate), so for as long
 * as we hold `app.lightphonekeyboard` the author of the original cannot ship his keyboard to a
 * phone that has ours -- and could not even list it, because BrightMarket keys its catalogue on
 * that id. We are giving it back and taking [CURRENT_ID].
 *
 * **Why this is an install and not an update.** A new applicationId is, to Android, a different
 * app. Nothing carries over on its own: not the settings, not the saved words, not the per-key
 * touch model the keyboard spent weeks learning. So this file carries them.
 *
 * **The shape of the handoff.** Both builds are signed with the same certificate, which is the one
 * thing that makes a clean transfer possible at all. The legacy build exposes [MigrationProvider]
 * behind a `signature` permission; the new build reads it once and writes the contents into its
 * own storage. Nothing is written to disk in between, nothing passes through the user, and no
 * other app can read it -- a signature permission is granted only to a package carrying the same
 * key, and that key is not in this repository.
 *
 * **The two builds coexist while it happens**, which is the point of the rename rather than a side
 * effect of it: the new app can only read the old one's data while the old one is still installed.
 * The notice asks for the uninstall last, never first.
 */
object Migration {

    /** The id this keyboard shipped under, and is giving back. */
    const val LEGACY_ID = "app.lightphonekeyboard"

    /** The id it is moving to. */
    const val CURRENT_ID = "com.gios.brightkeyboard"

    /** Signature-level, so only a build carrying our certificate can read any of this. */
    const val PERMISSION = "app.lightphonekeyboard.permission.MIGRATE"

    val CONTENT_URI: Uri = Uri.parse("content://app.lightphonekeyboard.migration/data")

    const val METHOD_EXPORT = "export"

    const val KEY_PREFS = "prefs"
    const val KEY_WORDS = "words"
    const val KEY_VERSION = "version"

    /** This build is the one being moved away from. One source, two applicationIds. */
    val isLegacyBuild: Boolean get() = BuildConfig.APPLICATION_ID == LEGACY_ID

    /**
     * Whether the package that replaces this one is on the phone.
     *
     * `getPackageInfo` throws rather than returning null for a package that is absent, and on
     * Android 11+ it throws the same way for one this app simply cannot see -- which is why
     * [CURRENT_ID] is named in the manifest's `<queries>`. Without that entry the answer would be
     * a permanent, silent "no" and the notice would keep nagging somebody who had already done
     * exactly what it asked.
     */
    fun replacementInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(CURRENT_ID, 0)
        true
    }.getOrDefault(false)

    enum class Notice { MOVE, DONE }

    /**
     * What the keyboard should be saying about this, or null when it should say nothing.
     *
     * Only the legacy build ever speaks. The new build compiles this file too and must stay quiet
     * in it.
     */
    fun notice(context: Context): Notice? = when {
        !isLegacyBuild -> null
        replacementInstalled(context) -> Notice.DONE
        else -> Notice.MOVE
    }

    /**
     * Everything worth carrying, as a Bundle.
     *
     * Deliberately not everything the app owns. The language packs, the swipe model and the Vosk
     * voice model run to tens of megabytes and every one of them can be fetched again from where it
     * came from; pushing them through a binder transaction would risk TransactionTooLarge for data
     * that is not lost either way. What cannot be fetched again is what travels: the settings, the
     * words the user taught it, and the per-key touch model -- all three in the one prefs file, the
     * touch model included.
     */
    fun export(context: Context): Bundle {
        val out = Bundle()
        out.putInt(KEY_VERSION, 1)
        out.putBundle(KEY_PREFS, prefsToBundle(context))
        CustomWords.raw(context)?.let { out.putString(KEY_WORDS, it) }
        return out
    }

    /**
     * Write an exported bundle into this app's storage. False when there was nothing to take, so
     * the caller can leave the offer up rather than report a success that moved no settings.
     */
    fun import(context: Context, data: Bundle?): Boolean {
        val prefs = data?.getBundle(KEY_PREFS) ?: return false
        if (prefs.isEmpty) return false
        val editor = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
        for (key in prefs.keySet()) {
            when (val v = prefs.get(key)) {
                is Boolean -> editor.putBoolean(key, v)
                is Int -> editor.putInt(key, v)
                is Long -> editor.putLong(key, v)
                is Float -> editor.putFloat(key, v)
                is String -> editor.putString(key, v)
                is ArrayList<*> -> editor.putStringSet(key, v.filterIsInstance<String>().toSet())
                // A type this build cannot store is skipped rather than guessed at.
                else -> Unit
            }
        }
        // commit, not apply: the next thing that happens is the user opening the keyboard, and a
        // settings file still being written when the IME reads it is exactly the failure that cost
        // a release once already -- a pref read at launch needs commit().
        editor.commit()
        data.getString(KEY_WORDS)?.let { CustomWords.restore(context, it) }
        return true
    }

    /**
     * SharedPreferences as a Bundle, by type.
     *
     * A Set<String> has no Bundle primitive that survives a binder round trip predictably, so it
     * travels as an ArrayList and is read back as one by [import], which is its only reader.
     */
    private fun prefsToBundle(context: Context): Bundle {
        val b = Bundle()
        for ((k, v) in context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).all) {
            when (v) {
                is Boolean -> b.putBoolean(k, v)
                is Int -> b.putInt(k, v)
                is Long -> b.putLong(k, v)
                is Float -> b.putFloat(k, v)
                is String -> b.putString(k, v)
                is Set<*> -> b.putStringArrayList(k, ArrayList(v.filterIsInstance<String>()))
                else -> Unit
            }
        }
        return b
    }

    /**
     * Pull the old app's data across. Called by the NEW build.
     *
     * An unresolvable authority throws from the resolver rather than returning null -- the trap
     * [LoginCode] already documents -- so the whole call is wrapped. A phone that no longer has the
     * old app gets a quiet false rather than a crash on first launch, which would be a spectacular
     * way to greet somebody who had just installed the thing.
     */
    fun pullFromLegacy(context: Context): Boolean = runCatching {
        import(context, context.contentResolver.call(CONTENT_URI, METHOD_EXPORT, null, null))
    }.getOrDefault(false)

    private const val KEY_IMPORTED = "migration_imported_v1"

    /**
     * Take the old app's data, once, in the NEW build.
     *
     * Once is the whole of it. A pull that ran on every launch would reach across and overwrite
     * whatever the user had since changed here with whatever the old app still remembers -- and the
     * old app is frozen, so its copy never stops being wrong from the moment the first setting is
     * touched. The flag is only set on a pull that actually found something, so somebody who
     * installs this one first and updates the old one afterwards still gets their settings when
     * they next open it.
     */
    fun importOnceFromLegacy(context: Context) {
        if (isLegacyBuild) return
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IMPORTED, false)) return
        if (pullFromLegacy(context)) prefs.edit().putBoolean(KEY_IMPORTED, true).commit()
    }

    /**
     * Somewhere the user can install the replacement, best first.
     *
     * BrightMarket leads, and that it is installed is not a guess: the installs this move has to
     * carry were counted by BrightMarket, which counts only its own. It already holds the install
     * permission, already checks the download against the signer pinned in the index, and is one
     * tap from finishing the job. The web listing is the fallback for a phone that came by this
     * keyboard some other way.
     */
    fun installIntents(): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("brightmarket://app/$CURRENT_ID")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://brightmarket.gzl.dev/app/$CURRENT_ID/")),
    )

    /** The system page for this app, which is where the uninstall button lives. */
    fun uninstallIntent(context: Context): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
}
