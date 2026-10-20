package dev.spendcodex.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The journal on disk. Foreign keys are NO ACTION (checked at the end of each statement, or at commit while a restore defers
 * them): an account, a category or a tag that records point to cannot be deleted, only archived. Names are kept twice, as
 * written and normalised (lowercase, "ё" as "е"), because SQLite's own lower() does not know Cyrillic.
 */

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_norm") val nameNorm: String,
    val kind: String,
    val currency: String,
    @ColumnInfo(name = "opening_minor") val openingMinor: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val archived: Boolean,
    val sort: Int,
)

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["parent_id"])],
    indices = [Index("parent_id")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_norm") val nameNorm: String,
    val kind: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    /** One word or phrase per line. */
    val keywords: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val archived: Boolean,
    val sort: Int,
)

@Entity(tableName = "tags", indices = [Index(value = ["name_norm"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_norm") val nameNorm: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val archived: Boolean,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["account_id"]),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["to_account_id"]),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"]),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["refund_of"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["date", "time_min", "created_at"]),
        Index("account_id"),
        Index("to_account_id"),
        Index("category_id"),
        Index("refund_of"),
        Index("description_norm"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val type: String,
    /** ISO date, "2026-10-20": sorts as text. */
    val date: String,
    /** Minute of the day, 0..1439; null when the time is not known. */
    @ColumnInfo(name = "time_min") val timeMin: Int?,
    @ColumnInfo(name = "time_zone") val timeZone: String?,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "account_amount_minor") val accountAmountMinor: Long?,
    @ColumnInfo(name = "to_account_id") val toAccountId: String?,
    @ColumnInfo(name = "to_amount_minor") val toAmountMinor: Long?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    val payee: String?,
    val description: String?,
    val note: String?,
    @ColumnInfo(name = "refund_of") val refundOf: String?,
    val origin: String,
    @ColumnInfo(name = "input_text") val inputText: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Normalised description, payee, note, what was typed and the amount: what the search looks in. */
    @ColumnInfo(name = "search_text") val searchText: String,
    /** The normalised description alone: what the quick line remembers categories by. */
    @ColumnInfo(name = "description_norm") val descriptionNorm: String?,
)

@Entity(
    tableName = "transaction_tags",
    primaryKeys = ["transaction_id", "tag_id"],
    foreignKeys = [
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transaction_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tag_id"]),
    ],
    indices = [Index("tag_id")],
)
data class TransactionTagEntity(
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)

/** Compressed JPEGs, one at a time on reads. Keeping them in SQLite makes save/delete/undo/restore atomic. */
@Entity(
    tableName = "receipts",
    foreignKeys = [ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transaction_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("transaction_id"), Index(value = ["transaction_id", "sha256"], unique = true)],
)
data class ReceiptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    val width: Int,
    val height: Int,
    val size: Int,
    val sha256: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val sort: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val content: ByteArray,
)

/** A row of the journal as the screens show it: the record with the names it points to. */
data class FeedRow(
    val id: String,
    val type: String,
    val date: String,
    @ColumnInfo(name = "time_min") val timeMin: Int?,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "account_currency") val accountCurrency: String,
    @ColumnInfo(name = "account_amount_minor") val accountAmountMinor: Long?,
    @ColumnInfo(name = "to_account_id") val toAccountId: String?,
    @ColumnInfo(name = "to_account_name") val toAccountName: String?,
    @ColumnInfo(name = "to_account_currency") val toAccountCurrency: String?,
    @ColumnInfo(name = "to_amount_minor") val toAmountMinor: Long?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "parent_name") val parentName: String?,
    val payee: String?,
    val description: String?,
    val note: String?,
    @ColumnInfo(name = "refund_of") val refundOf: String?,
    @ColumnInfo(name = "tag_names") val tagNames: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "receipt_count") val receiptCount: Int = 0,
)

/** Sums of a set of records by currency and type, for the line above search results. */
data class TypeTotal(val currency: String, val type: String, val total: Long, val count: Int)

/** The database's half of a balance (see [dev.spendcodex.data.domain.Balances]). */
data class SideRow(
    @ColumnInfo(name = "account_id") val accountId: String,
    val type: String,
    val currency: String,
    val amount: Long,
    @ColumnInfo(name = "account_amount") val accountAmount: Long,
    val count: Int,
    val missing: Int,
)

data class ArrivalRow(@ColumnInfo(name = "account_id") val accountId: String, val arrived: Long, val count: Int)

data class HintRow(@ColumnInfo(name = "category_id") val categoryId: String?, @ColumnInfo(name = "account_id") val accountId: String)

data class UsageRow(val id: String, val count: Int)
