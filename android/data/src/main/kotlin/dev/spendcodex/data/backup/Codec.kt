package dev.spendcodex.data.backup

import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.Refs
import dev.spendcodex.data.domain.Rules
import dev.spendcodex.data.domain.Tag
import dev.spendcodex.data.domain.Transaction
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.domain.normalize
import dev.spendcodex.data.settings.Choices
import dev.spendcodex.data.settings.Language
import dev.spendcodex.data.receipts.Receipt
import dev.spendcodex.data.receipts.ReceiptLimits
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** The whole journal, or a part of it, in the app's own terms. */
data class Snapshot(
    val accounts: List<Account>,
    val categories: List<Category>,
    val tags: List<Tag>,
    val transactions: List<Transaction>,
    val choices: Choices,
    val receipts: List<Receipt> = emptyList(),
)

/** Why a file cannot be restored. [where] names the record ("transaction 3f2a…") when it is about one. */
data class ImportError(val problem: ImportProblem, val where: String? = null, val detail: String? = null)

enum class ImportProblem {
    NotReadable, NotSpendCodex, TooNew, UnsupportedVersion, Partial, BadField, DuplicateId, DuplicateName,
    MissingReference, RuleBroken, Damaged, TooLarge,
}

sealed interface Decoded {
    data class Ok(val snapshot: Snapshot, val file: ExportFile) : Decoded
    data class Failed(val errors: List<ImportError>) : Decoded
}

/**
 * Turns the journal into the data file and back. Reading is strict: an unknown field, an amount with more decimals than its
 * currency has, a reference to an id that is not in the file, a record that breaks a rule of the journal: the file is
 * refused with the reason, and nothing is half-restored.
 */
@OptIn(ExperimentalSerializationApi::class)
object Codec {
    private val writer = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = true
    }
    private val reader = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        isLenient = false
    }

    private val INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun instant(ms: Long): String = INSTANT.format(Instant.ofEpochMilli(ms))

    // ---------- writing ----------

    fun toFile(snapshot: Snapshot, scope: ScopeDto, exportedAt: Long, appVersion: String, schemaVersion: Int): ExportFile {
        val currencyOf = snapshot.accounts.associate { it.id to it.currency }
        fun amount(minor: Long?, code: String?): String? {
            if (minor == null || code == null) return null
            return Decimal.format(minor, Currencies.require(code).exponent)
        }
        val used = (snapshot.accounts.map { it.currency } + snapshot.transactions.map { it.currency } + listOfNotNull(snapshot.choices.defaultCurrency))
            .distinct().sorted()
        return ExportFile(
            schemaVersion = schemaVersion,
            exportedAt = instant(exportedAt),
            app = AppDto("SpendCodex", appVersion),
            scope = scope,
            settings = SettingsDto(
                language = snapshot.choices.language.code,
                defaultCurrency = snapshot.choices.defaultCurrency,
                defaultAccountId = snapshot.choices.defaultAccountId,
                aiParsingEnabled = snapshot.choices.aiEnabled,
            ),
            currencies = used.mapNotNull { Currencies.of(it) }.map { CurrencyDto(it.code, it.exponent, it.symbol) },
            accounts = snapshot.accounts.map { a ->
                AccountDto(a.id, a.name, a.kind.code, a.currency, amount(a.openingMinor, a.currency), a.archived, a.sort, instant(a.createdAt), instant(a.updatedAt))
            },
            categories = snapshot.categories.map { c ->
                CategoryDto(c.id, c.name, c.kind.code, c.parentId, c.keywords, c.archived, c.sort, instant(c.createdAt), instant(c.updatedAt))
            },
            tags = snapshot.tags.map { t -> TagDto(t.id, t.name, t.archived, instant(t.createdAt), instant(t.updatedAt)) },
            transactions = snapshot.transactions.map { t ->
                TransactionDto(
                    id = t.id,
                    type = t.type.code,
                    date = t.date.toString(),
                    time = t.time?.format(TIME),
                    timeZone = t.timeZone,
                    amount = amount(t.amountMinor, t.currency)!!,
                    currency = t.currency,
                    accountId = t.accountId,
                    accountAmount = amount(t.accountAmountMinor, currencyOf[t.accountId]),
                    toAccountId = t.toAccountId,
                    toAmount = amount(t.toAmountMinor, t.toAccountId?.let { currencyOf[it] }),
                    categoryId = t.categoryId,
                    description = t.description,
                    payee = t.payee,
                    note = t.note,
                    tagIds = t.tagIds.sorted(),
                    refundOf = t.refundOf,
                    origin = t.origin.code,
                    inputText = t.inputText,
                    createdAt = instant(t.createdAt),
                    updatedAt = instant(t.updatedAt),
                )
            },
            receipts = snapshot.receipts.map { r -> ReceiptDto(r.id, r.transactionId, r.path, bytes = r.size,
                sha256 = r.sha256, width = r.width, height = r.height, createdAt = instant(r.createdAt), sort = r.sort) },
        )
    }

    fun encode(file: ExportFile): String = writer.encodeToString(ExportFile.serializer(), file) + "\n"

    fun encodeTo(file: ExportFile, out: OutputStream) {
        writer.encodeToStream(ExportFile.serializer(), file, out)
        out.write('\n'.code)
    }

    // ---------- reading ----------

    fun decode(text: String): Decoded = decodeWith { reader.decodeFromString(ExportFile.serializer(), text) }

    fun decode(input: InputStream): Decoded = decodeWith { reader.decodeFromStream(ExportFile.serializer(), input) }

    private inline fun decodeWith(parse: () -> ExportFile): Decoded {
        val file = try {
            parse()
        } catch (e: SerializationException) {
            return Decoded.Failed(listOf(ImportError(ImportProblem.NotReadable, detail = e.message?.take(300))))
        } catch (e: IllegalArgumentException) {
            return Decoded.Failed(listOf(ImportError(ImportProblem.NotReadable, detail = e.message?.take(300))))
        }
        return check(file)
    }

    /** Everything the journal itself would insist on, checked before anything is changed. */
    fun check(file: ExportFile): Decoded {
        val errors = ArrayList<ImportError>()
        fun fail(problem: ImportProblem, where: String? = null, detail: String? = null) {
            if (errors.size < 50) errors += ImportError(problem, where, detail)
        }
        if (file.format != FORMAT_NAME) return Decoded.Failed(listOf(ImportError(ImportProblem.NotSpendCodex, detail = file.format)))
        if (file.formatVersion > FORMAT_VERSION) return Decoded.Failed(listOf(ImportError(ImportProblem.TooNew, detail = file.formatVersion.toString())))
        if (file.formatVersion < 1) return Decoded.Failed(listOf(ImportError(ImportProblem.UnsupportedVersion, detail = file.formatVersion.toString())))
        if (file.scope.kind != "full") return Decoded.Failed(listOf(ImportError(ImportProblem.Partial)))
        if (file.receipts.isNotEmpty() && (!file.scope.receiptsIncluded || file.formatVersion < 2)) {
            return Decoded.Failed(listOf(ImportError(ImportProblem.Partial)))
        }

        fun ms(text: String, where: String): Long = try {
            Instant.parse(text).toEpochMilli()
        } catch (e: Exception) {
            fail(ImportProblem.BadField, where, "time $text"); 0L
        }

        fun dup(ids: List<String>, what: String) {
            ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { fail(ImportProblem.DuplicateId, "$what $it") }
            ids.filter { it.isBlank() }.forEach { _ -> fail(ImportProblem.BadField, what, "empty id") }
        }
        dup(file.accounts.map { it.id }, "account")
        dup(file.categories.map { it.id }, "category")
        dup(file.tags.map { it.id }, "tag")
        dup(file.transactions.map { it.id }, "transaction")
        dup(file.receipts.map { it.id }, "receipt")

        val accounts = file.accounts.mapNotNull { a ->
            val where = "account ${a.id}"
            val kind = AccountKind.of(a.kind) ?: run { fail(ImportProblem.BadField, where, "kind ${a.kind}"); null }
            val currency = Currencies.of(a.currency)?.takeIf { it.code == a.currency } ?: run { fail(ImportProblem.BadField, where, "currency ${a.currency}"); null }
            if (a.name.isBlank()) fail(ImportProblem.BadField, where, "empty name")
            val opening = a.openingBalance?.let { text ->
                currency?.let { Decimal.parseStrict(text, it.exponent) } ?: run { fail(ImportProblem.BadField, where, "opening_balance $text"); null }
            }
            if (kind == null || currency == null) null
            else Account(a.id, a.name, kind, a.currency, opening, ms(a.createdAt, where), ms(a.updatedAt, where), a.archived, a.sort)
        }

        val categories = file.categories.mapNotNull { c ->
            val where = "category ${c.id}"
            val kind = CategoryKind.of(c.kind) ?: run { fail(ImportProblem.BadField, where, "kind ${c.kind}"); null }
            if (c.name.isBlank()) fail(ImportProblem.BadField, where, "empty name")
            if (c.keywords.any { it.contains('\n') || it.contains('\r') || it.isBlank() }) fail(ImportProblem.BadField, where, "keywords")
            kind?.let { Category(c.id, c.name, it, c.parentId, c.keywords, ms(c.createdAt, "$where created_at"), ms(c.updatedAt, where), c.archived, c.sort) }
        }
        val categoryById = categories.associateBy { it.id }
        for (c in categories) {
            val pid = c.parentId ?: continue
            val parent = categoryById[pid]
            when {
                parent == null -> fail(ImportProblem.MissingReference, "category ${c.id}", "parent_id $pid")
                parent.id == c.id || parent.parentId != null || parent.kind != c.kind -> fail(ImportProblem.RuleBroken, "category ${c.id}", "parent_id $pid")
            }
        }

        val tags = file.tags.map { t ->
            if (t.name.isBlank()) fail(ImportProblem.BadField, "tag ${t.id}", "empty name")
            Tag(t.id, t.name, ms(t.createdAt, "tag ${t.id}"), ms(t.updatedAt, "tag ${t.id}"), t.archived)
        }
        tags.groupBy { normalize(it.name) }.filter { it.value.size > 1 }.keys.forEach { fail(ImportProblem.DuplicateName, "tag", it) }

        val accountById = accounts.associateBy { it.id }
        val typeById = file.transactions.associate { it.id to TxType.of(it.type) }
        val refs = Refs(accountById, categoryById, tags.map { it.id }.toSet()) { id -> typeById[id] }

        val transactions = file.transactions.mapNotNull { t ->
            val where = "transaction ${t.id}"
            val type = TxType.of(t.type) ?: run { fail(ImportProblem.BadField, where, "type ${t.type}"); return@mapNotNull null }
            val origin = Origin.of(t.origin) ?: run { fail(ImportProblem.BadField, where, "origin ${t.origin}"); return@mapNotNull null }
            val date = try { LocalDate.parse(t.date) } catch (e: Exception) { fail(ImportProblem.BadField, where, "date ${t.date}"); return@mapNotNull null }
            val time = t.time?.let {
                try { LocalTime.parse(it, TIME) } catch (e: Exception) { fail(ImportProblem.BadField, where, "time $it"); return@mapNotNull null }
            }
            t.timeZone?.let { z -> if (runCatching { ZoneId.of(z) }.isFailure) { fail(ImportProblem.BadField, where, "time_zone $z"); return@mapNotNull null } }
            val currency = Currencies.of(t.currency)?.takeIf { it.code == t.currency }
                ?: run { fail(ImportProblem.BadField, where, "currency ${t.currency}"); return@mapNotNull null }
            val amount = Decimal.parseStrict(t.amount, currency.exponent)
                ?: run { fail(ImportProblem.BadField, where, "amount ${t.amount}"); return@mapNotNull null }
            val account = accountById[t.accountId]
                ?: run { fail(ImportProblem.MissingReference, where, "account_id ${t.accountId}"); return@mapNotNull null }
            val accountAmount = t.accountAmount?.let { text ->
                Decimal.parseStrict(text, Currencies.require(account.currency).exponent)
                    ?: run { fail(ImportProblem.BadField, where, "account_amount $text"); return@mapNotNull null }
            }
            if (t.toAccountId != null && t.toAccountId !in accountById) { fail(ImportProblem.MissingReference, where, "to_account_id ${t.toAccountId}"); return@mapNotNull null }
            val toAmount = t.toAmount?.let { text ->
                val toCurrency = t.toAccountId?.let { accountById[it] }?.currency?.let { Currencies.require(it) }
                    ?: run { fail(ImportProblem.RuleBroken, where, "to_amount without to_account_id"); return@mapNotNull null }
                Decimal.parseStrict(text, toCurrency.exponent) ?: run { fail(ImportProblem.BadField, where, "to_amount $text"); return@mapNotNull null }
            }
            if (t.categoryId != null && t.categoryId !in categoryById) { fail(ImportProblem.MissingReference, where, "category_id ${t.categoryId}"); return@mapNotNull null }
            t.tagIds.filter { id -> tags.none { it.id == id } }.forEach { fail(ImportProblem.MissingReference, where, "tag_id $it") }
            if (t.refundOf != null && t.refundOf !in typeById) { fail(ImportProblem.MissingReference, where, "refund_of ${t.refundOf}"); return@mapNotNull null }
            if (t.tagIds.size != t.tagIds.distinct().size) fail(ImportProblem.DuplicateId, where, "tag_ids")
            val tx = Transaction(
                id = t.id, type = type, date = date, time = time, timeZone = t.timeZone, amountMinor = amount, currency = currency.code,
                accountId = t.accountId, accountAmountMinor = accountAmount, toAccountId = t.toAccountId, toAmountMinor = toAmount,
                categoryId = t.categoryId, payee = t.payee, description = t.description, note = t.note, tagIds = t.tagIds.sorted(),
                refundOf = t.refundOf, origin = origin, inputText = t.inputText,
                createdAt = ms(t.createdAt, where), updatedAt = ms(t.updatedAt, where),
            )
            // archived accounts and categories are fine on old records: the record is its own "previous"
            Rules.validate(tx, refs, previous = tx).forEach { fail(ImportProblem.RuleBroken, where, it.name) }
            tx
        }

        val receiptIds = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val receipts = file.receipts.map { r ->
            val where = "receipt ${r.id}"
            if (!receiptIds.matches(r.id) || r.path != "receipts/${r.id}.jpg" || r.mimeType != "image/jpeg" ||
                r.bytes !in 1..ReceiptLimits.MAX_BYTES || !Regex("[a-f0-9]{64}").matches(r.sha256) ||
                r.width !in 1..ReceiptLimits.MAX_LONG_SIDE || r.height !in 1..ReceiptLimits.MAX_LONG_SIDE ||
                minOf(r.width, r.height) > ReceiptLimits.MAX_SHORT_SIDE || r.width.toLong() * r.height > ReceiptLimits.MAX_PIXELS || r.sort < 0) fail(ImportProblem.BadField, where)
            if (r.transactionId !in typeById) fail(ImportProblem.MissingReference, where, "transaction_id")
            Receipt(r.id, r.transactionId, r.width, r.height, r.bytes, r.sha256, ms(r.createdAt, where), r.sort)
        }
        receipts.groupBy { it.transactionId }.forEach { (id, rows) ->
            if (rows.size > ReceiptLimits.PER_OPERATION || rows.map { it.sha256 }.distinct().size != rows.size ||
                rows.map { it.sort }.distinct().size != rows.size) fail(ImportProblem.RuleBroken, "receipts $id")
        }

        val language = Language.of(file.settings.language)
        if (language == null) fail(ImportProblem.BadField, "settings", "language ${file.settings.language}")
        if (file.settings.defaultCurrency != null && Currencies.of(file.settings.defaultCurrency) == null) {
            fail(ImportProblem.BadField, "settings", "default_currency ${file.settings.defaultCurrency}")
        }
        if (errors.isNotEmpty()) return Decoded.Failed(errors)
        val choices = Choices(
            language = language!!,
            defaultCurrency = file.settings.defaultCurrency,
            defaultAccountId = file.settings.defaultAccountId?.takeIf { it in accountById },
            aiEnabled = file.settings.aiParsingEnabled,
        )
        return Decoded.Ok(Snapshot(accounts, categories, tags, transactions, choices, receipts), file)
    }
}
