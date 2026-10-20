package dev.spendcodex.data.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dev.spendcodex.data.backup.Archive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Local JPEG compression, including all eight EXIF orientations. No image or metadata is sent anywhere. */
object ReceiptImages {
    fun read(context: Context, uri: Uri, transactionId: String, now: Long): ReceiptPhoto {
        val temp = File.createTempFile("receipt-", ".source", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= ReceiptLimits.MAX_SOURCE_BYTES) { "source too large" }
                        out.write(buffer, 0, n)
                    }
                }
            } ?: throw IOException("image unavailable")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            temp.inputStream().buffered().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
            val (w, h) = fit(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val source = temp.inputStream().buffered().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IOException("cannot decode image")
            try {
                val orientation = runCatching { ExifInterface(temp.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
                return compress(source, orientation, transactionId, now)
            } finally { source.recycle() }
        } finally { temp.delete() }
    }

    fun fit(width: Int, height: Int): Pair<Int, Int> {
        val scale = min(1.0, min(sqrt(ReceiptLimits.MAX_PIXELS.toDouble() / (width.toLong() * height)),
            min(ReceiptLimits.MAX_LONG_SIDE.toDouble() / max(width, height), ReceiptLimits.MAX_SHORT_SIDE.toDouble() / min(width, height))))
        val w = max(1, (width * scale).roundToInt())
        val h = min(max(1, (height * scale).roundToInt()), (ReceiptLimits.MAX_PIXELS / w).toInt())
        return w to h
    }

    fun compress(source: Bitmap, orientation: Int, transactionId: String, now: Long): ReceiptPhoto {
        val (w, h) = fit(source.width, source.height)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
            postScale(w.toFloat() / source.width, h.toFloat() / source.height)
        }
        val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        var flattened: Bitmap? = null
        try {
            val image = if (!upright.hasAlpha()) upright else Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888).also {
                flattened = it
                Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(upright, 0f, 0f, null) }
            }
            var bytes = byteArrayOf()
            for (quality in listOf(85, 80, 75)) {
                bytes = ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.JPEG, quality, it)) }.toByteArray()
                if (bytes.size <= ReceiptLimits.MAX_BYTES) break
            }
            require(bytes.size <= ReceiptLimits.MAX_BYTES) { "image too detailed; crop the receipt" }
            return ReceiptPhoto(Receipt(UUID.randomUUID().toString(), transactionId, image.width, image.height,
                bytes.size, Archive.sha256(bytes), now, 0), bytes)
        } finally {
            flattened?.recycle()
            if (upright !== source) upright.recycle()
        }
    }

    fun thumbnail(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun valid(bytes: ByteArray, receipt: Receipt): Boolean {
        if (!Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(receipt.id) || receipt.transactionId.isBlank() ||
            receipt.width !in 1..ReceiptLimits.MAX_LONG_SIDE || receipt.height !in 1..ReceiptLimits.MAX_LONG_SIDE ||
            min(receipt.width, receipt.height) > ReceiptLimits.MAX_SHORT_SIDE || receipt.sort < 0) return false
        if (bytes.size !in 1..ReceiptLimits.MAX_BYTES || bytes.size != receipt.size || Archive.sha256(bytes) != receipt.sha256) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outMimeType == "image/jpeg" && bounds.outWidth == receipt.width && bounds.outHeight == receipt.height &&
            receipt.width.toLong() * receipt.height <= ReceiptLimits.MAX_PIXELS
    }
}
