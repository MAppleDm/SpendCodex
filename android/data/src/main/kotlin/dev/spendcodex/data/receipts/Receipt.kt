package dev.spendcodex.data.receipts

import androidx.room.ColumnInfo

/** Metadata only. Lists never load image blobs into a CursorWindow. */
data class Receipt(
    val id: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    val width: Int,
    val height: Int,
    val size: Int,
    val sha256: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val sort: Int,
) {
    val path: String get() = "receipts/$id.jpg"
}

/** A form holds bytes only for newly selected images; saved images are read on demand. */
data class ReceiptPhoto(val receipt: Receipt, val bytes: ByteArray? = null)

fun Receipt.toEntity(bytes: ByteArray) = dev.spendcodex.data.db.ReceiptEntity(
    id, transactionId, width, height, size, sha256, createdAt, sort, bytes,
)

object ReceiptLimits {
    const val PER_OPERATION = 10
    // Each blob stays comfortably inside Android's 2 MiB CursorWindow, even on older devices.
    const val MAX_BYTES = 1_500_000
    const val MAX_LONG_SIDE = 8192
    const val MAX_SHORT_SIDE = 1600
    const val MAX_PIXELS = 6_000_000L
    const val MAX_SOURCE_BYTES = 50L * 1024 * 1024
}
