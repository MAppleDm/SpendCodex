package dev.spendcodex.data.backup

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spendcodex.data.db.AppDatabase
import dev.spendcodex.data.db.FeedSql
import dev.spendcodex.data.db.TransactionTagEntity
import dev.spendcodex.data.db.toDomain
import dev.spendcodex.data.db.toEntity
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.Transaction
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.receipts.ReceiptImages
import dev.spendcodex.data.receipts.toEntity
import dev.spendcodex.data.settings.Lang
import dev.spendcodex.data.settings.Settings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an export covers. The full backup is always everything; analysis exports may be a part. */
data class ExportScope(val filter: Filter = Filter(), val includeNotes: Boolean = true, val includeReceipts: Boolean = false) {
    val isFull: Boolean get() = filter.isEmpty && includeNotes
}

enum class ExportKind(val mime: String) {
    /** Everything, restorable: the data file, the tables, the description. */
    Backup("application/zip"),

    /** The package for an AI: instructions, a ready request, the tables and the data file of the selection. */
    Ai("application/zip"),

    Json("application/json"),

    /** All the tables with the description. */
    CsvPackage("application/zip"),

    /** The operations as one table. */
    TransactionsCsv("text/csv"),
}

/** What an export is about to hand over, shown before it is saved or shared. */
data class ExportPreview(
    val transactions: Int,
    val first: LocalDate?,
    val last: LocalDate?,
    val accounts: List<String>,
    val currencies: List<String>,
    val notes: Boolean,
    val receipts: Int = 0,
)

/** A backup that was read and checked, waiting for "заменить". */
data class RestorePreview(
    val snapshot: Snapshot,
    val exportedAt: String,
    val appVersion: String,
    val first: LocalDate?,
    val last: LocalDate?,
    /** How many operations the journal has now: all of them are replaced. */
    val currentTransactions: Int,
    val currentReceipts: Int = 0,
    internal val token: Any = Any(),
    internal val archive: Archive.Opened.Data? = null,
) : Closeable {
    override fun close() { archive?.close() }
}

sealed interface ReadResult {
    data class Ok(val preview: RestorePreview) : ReadResult
    data class Failed(val errors: List<ImportError>) : ReadResult
}

/**
 * Exports and restores. Every export is built from the database as it is at that moment, in one read, so the tables and the
 * data file in an archive always agree. A restore replaces the whole journal in one transaction: it either all happens or
 * nothing changes; before it, what was there is saved to a safety copy on the phone.
 */
@Singleton
class Backups @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val ledger: Ledger,
    private val settings: Settings,
    private val clock: Clock,
    private val docs: DocAssets,
    @Named("appVersion") private val appVersion: String,
) {
    private val dao = db.dao()
    private val schemaVersion = 2

    private val day: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun fileName(kind: ExportKind, date: LocalDate = LocalDate.now(clock)): String {
        val d = date.format(day)
        return when (kind) {
            ExportKind.Backup -> "spendcodex-backup-$d.zip"
            ExportKind.Ai -> "spendcodex-ai-$d.zip"
            ExportKind.Json -> "spendcodex-$d.json"
            ExportKind.CsvPackage -> "spendcodex-csv-$d.zip"
            ExportKind.TransactionsCsv -> "spendcodex-transactions-$d.csv"
        }
    }

    // ---------- what goes out ----------

    suspend fun snapshot(scope: ExportScope = ExportScope()): Snapshot = db.withTransaction {
        val allAccounts = dao.accountsNow().map { it.toDomain() }
        val categories = dao.categoriesNow().map { it.toDomain() }
        val allTags = dao.tagsNow().map { it.toDomain() }
        val tagsOf = dao.allTransactionTags().groupBy({ it.transactionId }, { it.tagId })
        val rows = if (scope.filter.isEmpty) dao.allTransactions() else dao.transactionsRaw(FeedSql.records(scope.filter))
        var transactions = rows.map { it.toDomain(tagsOf[it.id].orEmpty()) }
        if (!scope.includeNotes) transactions = transactions.map { it.copy(note = null, inputText = null) }
        val f = scope.filter
        // a part keeps what its records point to, and of the rest only what was asked for
        val accounts = if (f.accountIds.isEmpty()) allAccounts else {
            val wanted = f.accountIds + transactions.flatMap { listOfNotNull(it.accountId, it.toAccountId) }
            allAccounts.filter { it.id in wanted }
        }
        val tags = if (f.isEmpty) allTags else {
            val wanted = f.tagIds + transactions.flatMap { it.tagIds }
            allTags.filter { it.id in wanted }
        }
        // a refund whose purchase is outside the selection keeps its link only as an id the file does not resolve: drop it
        val inside = transactions.map { it.id }.toSet()
        transactions = transactions.map { if (it.refundOf != null && it.refundOf !in inside) it.copy(refundOf = null) else it }
        val choices = settings.choices.value.let { c -> c.copy(defaultAccountId = c.defaultAccountId?.takeIf { id -> accounts.any { it.id == id } }) }
        Snapshot(accounts, categories, tags, transactions, choices, dao.allReceipts().filter { it.transactionId in inside })
    }

    private fun scopeDto(scope: ExportScope, hasReceipts: Boolean): ScopeDto {
        if (scope.isFull && (!hasReceipts || scope.includeReceipts)) return ScopeDto(kind = "full", notesIncluded = true, receiptsIncluded = scope.includeReceipts)
        val f = scope.filter
        return ScopeDto(
            kind = "partial",
            from = f.from?.toString(),
            to = f.to?.toString(),
            accountIds = f.accountIds.sorted().ifEmpty { null },
            categoryIds = f.categoryIds.sorted().ifEmpty { null },
            tagIds = f.tagIds.sorted().ifEmpty { null },
            types = f.types.map { it.code }.sorted().ifEmpty { null },
            currencies = f.currencies.sorted().ifEmpty { null },
            text = f.text.trim().ifEmpty { null },
            notesIncluded = scope.includeNotes,
            receiptsIncluded = scope.includeReceipts,
        )
    }

    suspend fun file(scope: ExportScope = ExportScope()): ExportFile {
        val snapshot = snapshot(scope)
        return Codec.toFile(snapshot, scopeDto(scope, snapshot.receipts.isNotEmpty()), clock.millis(), appVersion, schemaVersion)
    }

    suspend fun preview(scope: ExportScope): ExportPreview {
        val s = snapshot(scope)
        return ExportPreview(
            transactions = s.transactions.size,
            first = s.transactions.minOfOrNull { it.date },
            last = s.transactions.maxOfOrNull { it.date },
            accounts = s.accounts.filter { !it.archived || s.transactions.any { t -> t.accountId == it.id || t.toAccountId == it.id } }.map { it.name },
            currencies = s.transactions.map { it.currency }.distinct().sorted(),
            notes = scope.includeNotes,
            receipts = s.receipts.size,
        )
    }

    /** Writes the export of [kind] to [out]. A [ExportKind.Backup] is always the whole journal, whatever [scope] says. */
    suspend fun write(kind: ExportKind, scope: ExportScope, out: OutputStream) = db.withTransaction {
        val effective = if (kind == ExportKind.Backup) ExportScope(includeReceipts = true) else scope.copy(includeReceipts = false)
        val file = file(effective)
        withContext(Dispatchers.IO) {
            val app = file.app
            val counts = Counts(file.accounts.size, file.categories.size, file.tags.size, file.transactions.size, file.receipts.size)
            val date = LocalDate.now(clock).format(day)
            fun csv(table: Csv.Table) = table.toString().toByteArray(Charsets.UTF_8)
            val dataBytes = { ByteArrayOutputStream().also { Codec.encodeTo(file, it) }.toByteArray() }
            when (kind) {
                ExportKind.Json -> Codec.encodeTo(file, out)
                ExportKind.TransactionsCsv -> out.write(csv(Csv.transactions(file)))
                ExportKind.Backup -> Archive.write(
                    out, "spendcodex-backup-$date", "backup", file.exportedAt, app, counts,
                    listOf(
                        Archive.Entry(Archive.DATA, dataBytes()),
                        Archive.Entry("csv/transactions.csv", csv(Csv.transactions(file))),
                        Archive.Entry("csv/accounts.csv", csv(Csv.accounts(file))),
                        Archive.Entry("csv/categories.csv", csv(Csv.categories(file))),
                        Archive.Entry("csv/tags.csv", csv(Csv.tags(file))),
                        Archive.Entry("csv/transaction_tags.csv", csv(Csv.transactionTags(file))),
                        Archive.Entry("csv/receipts.csv", csv(Csv.receipts(file))),
                        Archive.Entry("docs/format.md", docs.format.toByteArray(Charsets.UTF_8)),
                        Archive.Entry("docs/schema.json", docs.schema.toByteArray(Charsets.UTF_8)),
                    ) + file.receipts.map { r -> Archive.Entry(r.path, r.bytes.toLong(), r.sha256) {
                        ByteArrayInputStream(checkNotNull(dao.receiptBytes(r.id)) { "missing receipt ${r.id}" })
                    } },
                )
                ExportKind.CsvPackage -> Archive.write(
                    out, "spendcodex-csv-$date", "csv", file.exportedAt, app, counts,
                    listOf(
                        Archive.Entry("transactions.csv", csv(Csv.transactions(file))),
                        Archive.Entry("accounts.csv", csv(Csv.accounts(file))),
                        Archive.Entry("categories.csv", csv(Csv.categories(file))),
                        Archive.Entry("tags.csv", csv(Csv.tags(file))),
                        Archive.Entry("transaction_tags.csv", csv(Csv.transactionTags(file))),
                        Archive.Entry("receipts.csv", csv(Csv.receipts(file))),
                        Archive.Entry("format.md", docs.format.toByteArray(Charsets.UTF_8)),
                    ),
                )
                ExportKind.Ai -> {
                    val english = Lang.en
                    Archive.write(
                        out, "spendcodex-ai-$date", "ai", file.exportedAt, app, counts,
                        listOf(
                            Archive.Entry("README_FOR_AI.md", aiReadme(file, effective, english).toByteArray(Charsets.UTF_8)),
                            Archive.Entry("PROMPT.txt", docs.prompt(english).toByteArray(Charsets.UTF_8)),
                            Archive.Entry("transactions.csv", csv(Csv.transactions(file))),
                            Archive.Entry("accounts.csv", csv(Csv.accounts(file))),
                            Archive.Entry("categories.csv", csv(Csv.categories(file))),
                            Archive.Entry("tags.csv", csv(Csv.tags(file))),
                            Archive.Entry("receipts.csv", csv(Csv.receipts(file))),
                            Archive.Entry("data.json", dataBytes()),
                        ),
                    )
                }
            }
            out.flush()
        }
    }

    /** The AI instructions with this selection described in them. */
    private fun aiReadme(file: ExportFile, scope: ExportScope, english: Boolean): String {
        fun t(ru: String, en: String) = if (english) en else ru
        val dates = file.transactions.map { it.date }
        val from = scope.filter.from?.toString() ?: dates.minOrNull()
        val to = scope.filter.to?.toString() ?: dates.maxOrNull()
        val period = when {
            scope.filter.from == null && scope.filter.to == null -> t("всё время", "all time") + (if (from != null) " ($from … $to)" else "")
            else -> "${from ?: "…"} … ${to ?: "…"}"
        }
        val accounts = if (scope.filter.accountIds.isEmpty()) t("все", "all") + " (${file.accounts.size}): " + file.accounts.joinToString(", ") { "${it.name} (${it.currency})" }
        else file.accounts.joinToString(", ") { "${it.name} (${it.currency})" }
        val currencies = file.transactions.map { it.currency }.distinct().sorted().joinToString(", ").ifEmpty { "—" }
        val exported = Instant.parse(file.exportedAt).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' ')
        val extra = buildList {
            if (scope.filter.types.isNotEmpty()) add(t("типы: ", "types: ") + scope.filter.types.joinToString(", ") { it.code })
            if (scope.filter.categoryIds.isNotEmpty()) add(t("только выбранные категории", "selected categories only"))
            if (scope.filter.tagIds.isNotEmpty()) add(t("только с выбранными тегами", "selected tags only"))
            if (scope.filter.currencies.isNotEmpty()) add(t("валюты: ", "currencies: ") + scope.filter.currencies.sorted().joinToString(", "))
            if (scope.filter.text.isNotBlank()) add(t("поиск: ", "search: ") + "«${scope.filter.text.trim()}»")
        }
        return docs.aiReadme(english)
            .replace("{{period}}", period + if (extra.isEmpty()) "" else "; " + extra.joinToString("; "))
            .replace("{{accounts}}", accounts)
            .replace("{{count}}", file.transactions.size.toString())
            .replace("{{currencies}}", currencies)
            .replace("{{notes}}", if (scope.includeNotes) t("включены", "included") else t("не включены", "not included"))
            .replace("{{exported_at}}", exported)
    }

    // ---------- what comes back ----------

    /** Reads and checks a backup without changing anything. */
    suspend fun read(input: InputStream): ReadResult = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}")
        check(staging.mkdirs())
        var retained = false
        try {
            when (val opened = Archive.open(input, staging)) {
                is Archive.Opened.Failed -> ReadResult.Failed(listOf(ImportError(opened.problem, detail = opened.detail)))
                is Archive.Opened.Data -> when (val decoded = Codec.decode(ByteArrayInputStream(opened.bytes))) {
                    is Decoded.Failed -> ReadResult.Failed(decoded.errors)
                    is Decoded.Ok -> {
                        val s = decoded.snapshot
                        val expected = s.receipts.map { it.path }.toSet()
                        if (opened.files.keys.filter { it.startsWith("receipts/") }.toSet() != expected ||
                            s.receipts.any { r -> opened.files[r.path]?.let { ReceiptImages.valid(it.readBytes(), r) } != true }) {
                            ReadResult.Failed(listOf(ImportError(ImportProblem.Damaged, detail = "receipt files")))
                        } else {
                            retained = true
                            ReadResult.Ok(RestorePreview(s, decoded.file.exportedAt, decoded.file.app.version,
                                s.transactions.minOfOrNull { it.date }, s.transactions.maxOfOrNull { it.date }, dao.countNow(), currentReceipts = dao.allReceipts().size, archive = opened))
                        }
                    }
                }
            }
        } finally { if (!retained) staging.deleteRecursively() }
    }

    /** Where the journal as it was before the last restore is kept, if there was anything. */
    val safetyCopy: File get() = File(context.filesDir, "safety/before-restore.zip")

    /**
     * Replaces the whole journal with [preview]'s. What was there first goes to [safetyCopy]; then everything is removed and
     * the backup's records are inserted, in one transaction, parents before what points to them.
     */
    suspend fun restore(preview: RestorePreview) {
        val s = preview.snapshot
        try {
          withContext(Dispatchers.IO) {
           db.withTransaction {
            if (dao.countNow() > 0 || dao.accountsNow().isNotEmpty()) {
                val target = safetyCopy
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "before-restore.tmp")
                try {
                    temp.outputStream().use { write(ExportKind.Backup, ExportScope(), it) }
                    try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                } finally { temp.delete() }
            }
            dao.wipeTransactionTags()
            dao.wipeTransactions()
            dao.wipeTags()
            dao.detachCategories()
            dao.wipeCategories()
            dao.wipeAccounts()
            dao.insertAccounts(s.accounts.map { it.toEntity() })
            val (parents, children) = s.categories.partition { it.parentId == null }
            dao.insertCategories((parents + children).map { it.toEntity() })
            dao.insertTags(s.tags.map { it.toEntity() })
            val (plain, refunds) = s.transactions.partition { it.refundOf == null }
            (plain + refunds).chunked(500).forEach { chunk -> dao.insertTransactions(chunk.map(Transaction::toEntity)) }
            s.transactions.flatMap { t -> t.tagIds.map { TransactionTagEntity(t.id, it) } }.chunked(500).forEach { dao.insertTransactionTags(it) }
            // Bounded one-image reads. A lost staging file or changed byte rolls the entire database transaction back.
            for (r in s.receipts) {
                val bytes = checkNotNull(preview.archive?.files?.get(r.path)) { "missing receipt" }.readBytes()
                check(ReceiptImages.valid(bytes, r)) { "damaged receipt" }
                dao.insertReceipt(r.toEntity(bytes))
            }
           }
           settings.update { s.choices }
           settings.setSetupDone(true)
          }
        } finally { preview.close() }
    }

    fun hasSafetyCopy(): Boolean = safetyCopy.isFile
}
