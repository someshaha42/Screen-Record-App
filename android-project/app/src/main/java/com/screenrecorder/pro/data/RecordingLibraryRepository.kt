package com.screenrecorder.pro.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.screenrecorder.pro.model.RecordingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecordingLibraryRepository(private val context: Context) {

    suspend fun loadRecordings(): List<RecordingItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<RecordingItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("%${Environment.DIRECTORY_MOVIES}/ScreenRecorder%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                list.add(
                    RecordingItem(
                        id = id,
                        uri = uri,
                        displayName = cursor.getString(nameCol) ?: "Recording_$id.mp4",
                        sizeBytes = cursor.getLong(sizeCol),
                        durationMs = cursor.getLong(durCol),
                        dateAddedSeconds = cursor.getLong(dateCol),
                        width = cursor.getInt(wCol),
                        height = cursor.getInt(hCol)
                    )
                )
            }
        }
        list
    }

    suspend fun loadThumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.loadThumbnail(uri, Size(320, 180), null)
        }.getOrNull()
    }

    suspend fun renameRecording(item: RecordingItem, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val clean = if (newName.endsWith(".mp4", ignoreCase = true)) {
                newName.trim()
            } else {
                "${newName.trim()}.mp4"
            }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, clean)
            }
            runCatching {
                context.contentResolver.update(item.uri, values, null, null) > 0
            }.getOrDefault(false)
        }

    suspend fun deleteRecording(item: RecordingItem): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.delete(item.uri, null, null) > 0
        }.getOrDefault(false)
    }

    fun buildShareIntent(item: RecordingItem): Intent {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(share, "Share ${item.displayName}")
    }
}
