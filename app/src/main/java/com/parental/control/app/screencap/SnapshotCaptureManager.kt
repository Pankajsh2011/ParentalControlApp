package com.parental.control.app.screencap

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Feature 2: Snapshot & Screen Capture Manager
 * Handles screen capture bitmap saving, compression, and local storage buffer.
 */
class SnapshotCaptureManager(private val context: Context) {

    private val tag = "SnapshotManager"

    /**
     * Compresses and saves the captured screen bitmap locally.
     */
    fun saveSnapshot(bitmap: Bitmap, callback: (File?) -> Unit) {
        try {
            val snapshotsDir = File(context.filesDir, "snapshots").apply {
                if (!exists()) mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val targetFile = File(snapshotsDir, "SNAP_${timestamp}.webp")

            FileOutputStream(targetFile).use { outStream ->
                // Compress to WebP for high quality with minimal file size
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, outStream)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outStream)
                }
            }
            Log.d(tag, "Snapshot successfully saved at: ${targetFile.absolutePath} (${targetFile.length() / 1024} KB)")
            callback(targetFile)
        } catch (e: Exception) {
            Log.e(tag, "Failed to save snapshot: ${e.message}", e)
            callback(null)
        }
    }

    /**
     * Lists all existing snapshots for the gallery / preview screen.
     */
    fun getSavedSnapshots(): List<File> {
        val snapshotsDir = File(context.filesDir, "snapshots")
        return snapshotsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
