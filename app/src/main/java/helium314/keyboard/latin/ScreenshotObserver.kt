// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import helium314.keyboard.latin.utils.Log

/**
 * Watches the media store for new screenshots, like Gboard does for "Save recent screenshots in clipboard".
 * Screenshots are not put on the system clipboard (only when the user taps "copy" in the screenshot preview),
 * so the only way to notice them is to look at the images collection.
 */
class ScreenshotObserver(
    private val context: Context,
    private val onScreenshot: (uri: Uri, mimeType: String, name: String) -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val resolver: ContentResolver = context.contentResolver
    private var lastHandledId = -1L
    private var registered = false

    fun register() {
        if (registered) return
        // do not report screenshots that were taken before we started watching
        lastHandledId = newestScreenshot(markOnly = true)?.id ?: -1L
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        resolver.unregisterContentObserver(this)
        registered = false
    }

    override fun onChange(selfChange: Boolean) {
        val shot = try { newestScreenshot(markOnly = false) } catch (e: Exception) {
            Log.w(TAG, "error looking for screenshots", e) // SecurityException if the permission was revoked
            null
        } ?: return
        if (shot.id <= lastHandledId) return
        lastHandledId = shot.id
        onScreenshot(shot.uri, shot.mimeType, shot.name)
    }

    private class Shot(val id: Long, val uri: Uri, val mimeType: String, val name: String)

    /** The newest finished screenshot added in the last minute (or any, if [markOnly]). */
    private fun newestScreenshot(markOnly: Boolean): Shot? {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn = if (q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, pathColumn
        )
        if (q) projection.add(MediaStore.MediaColumns.IS_PENDING)
        val minAdded = System.currentTimeMillis() / 1000 - (if (markOnly) 60 * 60 else 60)
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection.toTypedArray(),
            "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
            arrayOf(minAdded.toString()),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        )?.use {
            var checked = 0
            while (it.moveToNext() && checked++ < 20) {
                val name = it.getString(1) ?: ""
                val path = it.getString(3) ?: ""
                if (!name.startsWith("screenshot", ignoreCase = true) && !path.contains("screenshot", ignoreCase = true))
                    continue
                // screenshots are first written as pending, we get another change when they are done
                if (q && it.getInt(4) != 0) continue
                val id = it.getLong(0)
                return Shot(
                    id,
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    it.getString(2) ?: "image/png",
                    name
                )
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ScreenshotObserver"

        fun requiredPermission() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
