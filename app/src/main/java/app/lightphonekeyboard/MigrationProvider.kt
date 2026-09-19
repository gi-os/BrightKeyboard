package app.lightphonekeyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Hands this app's settings and saved words to the build that replaces it. See [Migration].
 *
 * Exported, because the whole job is to be read by another package -- but behind
 * [Migration.PERMISSION], which is declared `signature` in the manifest. Android grants a signature
 * permission only to a package signed with the same certificate, so the only caller that can ever
 * succeed here is a build of this keyboard. Without that, an exported provider handing out a
 * keyboard's learned words would be a data leak with a content:// URI.
 *
 * `call` rather than `query`: what moves is a settings map and a block of text, which is a Bundle.
 * Forcing it through a Cursor would mean inventing a row format for the sake of using the method
 * with rows in it.
 */
class MigrationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != Migration.METHOD_EXPORT) return null
        val ctx = context ?: return null
        return Migration.export(ctx)
    }

    // A provider must implement these. There are no rows here, and returning empty results rather
    // than throwing keeps any tool that enumerates providers from taking the app down with it.
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
