package dev.spendcodex.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * One photo from the camera app: it writes into a cache file handed to it through the FileProvider, which is read once and
 * removed. Only the latest such file is kept.
 */
class CameraCapture(private val context: Context) {
    private var file: File? = null

    /** Where the camera app is to write, or null when no file could be made for it. */
    fun target(): Uri? = try {
        clear()
        val dir = File(context.cacheDir, "camera").also { check(it.isDirectory || it.mkdirs()) }
        val next = File.createTempFile("receipt-", ".jpg", dir)
        file = next
        FileProvider.getUriForFile(context, "${context.packageName}.files", next)
    } catch (e: Exception) {
        clear()
        android.util.Log.w("Receipts", "Could not prepare camera output", e)
        null
    }

    /** The photo the camera app wrote, when it says it did; whoever takes it deletes it once read. */
    fun taken(success: Boolean): File? {
        val taken = file ?: return null
        file = null
        if (!success) { taken.delete(); return null }
        return taken
    }

    fun clear() {
        file?.delete()
        file = null
    }
}
