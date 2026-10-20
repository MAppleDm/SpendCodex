package dev.spendcodex.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * Hands a file the person chose to send (an export, a backup) to the app they pick. It is written to the app's cache and
 * handed out through the FileProvider declared in the manifest, readable only by the chosen app. Only the latest file is
 * ever kept there.
 */
suspend fun shareFile(context: Context, fileName: String, mime: String, chooserTitle: String, write: suspend (OutputStream) -> Unit) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, fileName)
    file.outputStream().use { write(it) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, fileName)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri) // lets the share sheet itself read it, for the preview
    val chooser = Intent.createChooser(send, chooserTitle)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, dev.spendcodex.MainActivity::class.java)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
