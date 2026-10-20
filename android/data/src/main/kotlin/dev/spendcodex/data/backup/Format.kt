package dev.spendcodex.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The SpendCodex data file, format version 1 (docs/format.md). Field names are snake_case and never change within a
 * version. Amounts are decimal strings ("4.50", "-20.00"), never JSON numbers: a reader that turns numbers into doubles
 * cannot lose a cent. Every reference is an id that is in the same file. Times of records ("created_at", "updated_at") are
 * UTC instants with milliseconds; the moment of an operation is its local "date", "time" and "time_zone".
 */

const val FORMAT_NAME = "spendcodex"
const val FORMAT_VERSION = 2

@Serializable
data class ExportFile(
    val format: String = FORMAT_NAME,
    @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
    /** The app's database schema the data came from. */
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("exported_at") val exportedAt: String,
    val app: AppDto,
    val scope: ScopeDto,
    val settings: SettingsDto,
    val currencies: List<CurrencyDto>,
    val accounts: List<AccountDto>,
    val categories: List<CategoryDto>,
    val tags: List<TagDto>,
    val transactions: List<TransactionDto>,
    val receipts: List<ReceiptDto> = emptyList(),
)

@Serializable
data class AppDto(val name: String, val version: String)

/**
 * What the file holds. "full": everything, restorable. "partial": a selection for analysis (a period, some accounts, maybe
 * without notes): it is never offered as a backup.
 */
@Serializable
data class ScopeDto(
    val kind: String,
    val from: String? = null,
    val to: String? = null,
    @SerialName("account_ids") val accountIds: List<String>? = null,
    @SerialName("category_ids") val categoryIds: List<String>? = null,
    @SerialName("tag_ids") val tagIds: List<String>? = null,
    val types: List<String>? = null,
    val currencies: List<String>? = null,
    val text: String? = null,
    @SerialName("notes_included") val notesIncluded: Boolean = true,
    @SerialName("receipts_included") val receiptsIncluded: Boolean = false,
)

@Serializable
data class ReceiptDto(
    val id: String,
    @SerialName("transaction_id") val transactionId: String,
    val path: String,
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
    val bytes: Int,
    val sha256: String,
    val width: Int,
    val height: Int,
    @SerialName("created_at") val createdAt: String,
    val sort: Int,
)

@Serializable
data class SettingsDto(
    val language: String,
    @SerialName("default_currency") val defaultCurrency: String?,
    @SerialName("default_account_id") val defaultAccountId: String?,
    /** Only whether the user turned DeepSeek reading on. The key itself is never exported. */
    @SerialName("ai_parsing_enabled") val aiParsingEnabled: Boolean,
)

@Serializable
data class CurrencyDto(val code: String, val exponent: Int, val symbol: String? = null)

@Serializable
data class AccountDto(
    val id: String,
    val name: String,
    val kind: String,
    val currency: String,
    @SerialName("opening_balance") val openingBalance: String?,
    val archived: Boolean,
    val sort: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("parent_id") val parentId: String?,
    val keywords: List<String>,
    val archived: Boolean,
    val sort: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val archived: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TransactionDto(
    val id: String,
    val type: String,
    val date: String,
    val time: String?,
    @SerialName("time_zone") val timeZone: String?,
    val amount: String,
    val currency: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("account_amount") val accountAmount: String?,
    @SerialName("to_account_id") val toAccountId: String?,
    @SerialName("to_amount") val toAmount: String?,
    @SerialName("category_id") val categoryId: String?,
    val description: String?,
    val payee: String?,
    val note: String?,
    @SerialName("tag_ids") val tagIds: List<String>,
    @SerialName("refund_of") val refundOf: String?,
    val origin: String,
    @SerialName("input_text") val inputText: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** manifest.json of an archive: what is in it, with a checksum of every file, so a damaged copy is noticed before use. */
@Serializable
data class Manifest(
    val format: String = "spendcodex-archive",
    @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
    /** "backup", "ai" or "csv". */
    val kind: String,
    @SerialName("created_at") val createdAt: String,
    val app: AppDto,
    val counts: Counts,
    val files: List<ManifestFile>,
)

@Serializable
data class Counts(val accounts: Int, val categories: Int, val tags: Int, val transactions: Int, val receipts: Int = 0)

@Serializable
data class ManifestFile(val path: String, val bytes: Long, val sha256: String)
