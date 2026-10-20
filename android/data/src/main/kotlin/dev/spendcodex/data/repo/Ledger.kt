package dev.spendcodex.data.repo

import androidx.room.withTransaction
import dev.spendcodex.data.db.AppDatabase
import dev.spendcodex.data.db.FeedRow
import dev.spendcodex.data.db.FeedSql
import dev.spendcodex.data.db.TransactionTagEntity
import dev.spendcodex.data.db.ReceiptEntity
import dev.spendcodex.data.receipts.Receipt
import dev.spendcodex.data.receipts.ReceiptPhoto
import dev.spendcodex.data.receipts.ReceiptImages
import dev.spendcodex.data.receipts.ReceiptLimits
import dev.spendcodex.data.receipts.toEntity
import dev.spendcodex.data.db.TypeTotal
import dev.spendcodex.data.db.toDomain
import dev.spendcodex.data.db.toEntity
import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.ArrivalSum
import dev.spendcodex.data.domain.Balance
import dev.spendcodex.data.domain.Balances
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftBuilder
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.Problem
import dev.spendcodex.data.domain.Refs
import dev.spendcodex.data.domain.SideSum
import dev.spendcodex.data.domain.Tag
import dev.spendcodex.data.domain.Transaction
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.domain.normalize
import dev.spendcodex.data.parse.Hint
import dev.spendcodex.data.parse.LineContext
import dev.spendcodex.data.settings.Language
import dev.spendcodex.data.settings.Settings
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

sealed interface SaveResult {
    data class Saved(val tx: Transaction, val created: Boolean) : SaveResult
    data class Invalid(val problems: List<Problem>, val index: Int = 0) : SaveResult
}

/** A deleted record, kept in memory for "отменить": the record exactly as it was and the refunds that pointed to it. */
data class Removed(val tx: Transaction, val refunds: List<String>, val receipts: List<ReceiptEntity> = emptyList())

enum class EditError { NameMissing, NameTaken, CurrencyUnknown, CurrencyLocked, AmountInvalid, ParentInvalid, KindLocked, InUse, NotFound }

sealed interface EditResult {
    data class Ok(val id: String) : EditResult
    data class Error(val error: EditError) : EditResult
}

/** An account as the forms change it. [opening] is typed text, blank for "not told". */
data class AccountEdit(
    val id: String? = null,
    val name: String,
    val kind: AccountKind,
    val currency: String,
    val opening: String,
)

data class CategoryEdit(
    val id: String? = null,
    val name: String,
    val kind: CategoryKind,
    val parentId: String?,
    val keywords: List<String>,
)

/**
 * The journal: everything the screens read and every change they make. One place decides how a record is stored, so the
 * rules (one record per draft, archived things stay attached to old records, a deletion can be undone exactly) hold
 * whichever screen made the change.
 */
@Singleton
class Ledger @Inject constructor(
    private val db: AppDatabase,
    private val settings: Settings,
    private val clock: Clock,
) {
    private val dao = db.dao()

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    // ---------- reading ----------

    val accounts: Flow<List<Account>> = dao.accounts().map { rows -> rows.map { it.toDomain() } }
    val categories: Flow<List<Category>> = dao.categories().map { rows -> rows.map { it.toDomain() } }
    val tags: Flow<List<Tag>> = dao.tags().map { rows -> rows.map { it.toDomain() } }
    val count: Flow<Int> = dao.count()
    val receiptCount: Flow<Int> = dao.receiptCount()

    suspend fun receiptsOf(id: String): List<Receipt> = dao.receiptsOf(id)
    suspend fun receiptBytes(id: String): ByteArray? = dao.receiptBytes(id)
    val categoryUsage: Flow<Map<String, Int>> = dao.categoryCounts().map { rows -> rows.associate { it.id to it.count } }
    val tagUsage: Flow<Map<String, Int>> = dao.tagCounts().map { rows -> rows.associate { it.id to it.count } }

    val balances: Flow<Map<String, Balance>> = combine(accounts, dao.sides(), dao.arrivals()) { accounts, sides, arrivals ->
        Balances.compute(accounts, sides.map { it.toSum() }, arrivals.map { ArrivalSum(it.accountId, it.arrived, it.count) })
    }

    suspend fun balancesNow(): Map<String, Balance> = Balances.compute(
        dao.accountsNow().map { it.toDomain() },
        dao.sidesNow().map { it.toSum() },
        dao.arrivalsNow().map { ArrivalSum(it.accountId, it.arrived, it.count) },
    )

    private fun dev.spendcodex.data.db.SideRow.toSum() =
        SideSum(accountId, TxType.of(type) ?: TxType.Expense, currency, amount, accountAmount, count, missing)

    fun feed(filter: Filter, limit: Int): Flow<List<FeedRow>> = dao.feed(FeedSql.feed(filter, limit))

    suspend fun feedNow(filter: Filter, limit: Int): List<FeedRow> = dao.feedNow(FeedSql.feed(filter, limit))

    fun totals(filter: Filter): Flow<List<TypeTotal>> = dao.totals(FeedSql.totals(filter))

    suspend fun totalsNow(filter: Filter): List<TypeTotal> = dao.totalsNow(FeedSql.totals(filter))

    fun dayTotals(date: LocalDate): Flow<List<TypeTotal>> = dao.dayTotals(date.toString())

    suspend fun get(id: String): Transaction? = dao.transaction(id)?.toDomain(dao.tagIdsOf(id))

    fun observe(id: String): Flow<Transaction?> = dao.observeTransaction(id).map { it?.toDomain(dao.tagIdsOf(it.id)) }

    suspend fun accountsNow(): List<Account> = dao.accountsNow().map { it.toDomain() }
    suspend fun categoriesNow(): List<Category> = dao.categoriesNow().map { it.toDomain() }
    suspend fun tagsNow(): List<Tag> = dao.tagsNow().map { it.toDomain() }

    /**
     * The account a record goes to when none is named: the chosen default if it is still active, else the first active one.
     * Payment methods are not shown (the journal counts spending, not balances), so nobody could fix a journal without an
     * active account: the default one is brought back from the archive, or, in a journal with none, the starter one is made.
     */
    suspend fun defaultAccountId(): String = db.withTransaction {
        val all = dao.accountsNow()
        val chosen = settings.choices.value.defaultAccountId
        val active = all.filter { !it.archived }
        active.firstOrNull { it.id == chosen }?.id ?: active.firstOrNull()?.id ?: run {
            val nowMs = now().toInstant().toEpochMilli()
            val archived = all.firstOrNull { it.id == chosen } ?: all.firstOrNull()
            if (archived != null) {
                dao.upsertAccount(archived.copy(archived = false, updatedAt = nowMs))
                archived.id
            } else {
                val a = Starter.accounts.first()
                val code = settings.choices.value.defaultCurrency?.let(Currencies::of)?.code ?: "EUR"
                val account = Account(UUID.randomUUID().toString(), Starter.name(a.ru, a.en, settings.choices.value.language), a.kind, code, null, nowMs, nowMs)
                dao.upsertAccount(account.toEntity())
                account.id
            }
        }
    }

    /** [id] when that account can still take new records, else null (the default one). */
    private suspend fun activeAccount(id: String): String? = dao.account(id)?.takeIf { !it.archived }?.id

    /**
     * What the quick line reads against. Its [LineContext.hint] asks the database while the line is read, so the line must
     * be read off the main thread.
     */
    suspend fun lineContext(today: LocalDate = now().toLocalDate()): LineContext = LineContext(
        accounts = dao.accountsNow().map { it.toDomain() }.filter { !it.archived },
        categories = dao.categoriesNow().map { it.toDomain() }.filter { !it.archived },
        today = today,
        hint = { key -> dao.hint(key)?.let { Hint(it.categoryId, it.accountId) } },
    )

    /** What the quick line remembers about a description. Blocking: call it off the main thread, while the line is read. */
    fun hint(key: String): Hint? = dao.hint(key)?.let { Hint(it.categoryId, it.accountId) }

    /** Earlier records whose description starts with [prefix]: the latest of each, to repeat with one tap. */
    suspend fun suggestions(prefix: String, limit: Int = 3): List<Transaction> {
        val key = normalize(prefix)
        if (key.length < 2) return emptyList()
        return dao.suggestions(FeedSql.startsWith(key), limit).map { it.toDomain(emptyList()) }
    }

    suspend fun recentExpenses(limit: Int = 30): List<Transaction> = dao.recentExpenses(limit).map { it.toDomain(emptyList()) }

    suspend fun tagNames(ids: List<String>): List<String> = ids.mapNotNull { dao.tag(it)?.name }

    private suspend fun refs(refundOf: String?): Refs {
        val refundType = refundOf?.let { dao.typeOf(it) }
        return Refs(
            accounts = dao.accountsNow().associate { it.id to it.toDomain() },
            categories = dao.categoriesNow().associate { it.id to it.toDomain() },
            tagIds = dao.tagsNow().map { it.id }.toSet(),
            typeOf = { id -> if (id == refundOf) TxType.of(refundType) else null },
        )
    }

    // ---------- writing records ----------

    /** Stores one draft: a new record, or the edit of the stored one with the same id. */
    suspend fun save(draft: Draft, photos: List<ReceiptPhoto>? = null): SaveResult {
        if (photos == null) return saveAll(listOf(draft)).single()
        return db.withTransaction {
            val result = saveAll(listOf(draft)).single()
            if (result !is SaveResult.Saved) return@withTransaction result
            val old = dao.receiptsOf(result.tx.id)
            val next = photos.mapIndexed { i, p -> p.receipt.copy(sort = i) }
            require(next.size <= ReceiptLimits.PER_OPERATION && next.map { it.id }.distinct().size == next.size)
            require(next.map { it.sha256 }.distinct().size == next.size)
            require(next.all { it.transactionId == result.tx.id })
            if (old == next) return@withTransaction result
            // Validate and collect at most ten bounded blobs before removing any old links.
            val rows = photos.mapIndexed { i, photo ->
                val meta = next[i]
                val bytes = photo.bytes ?: run {
                    require(old.any { it.id == meta.id && it.copy(sort = meta.sort) == meta })
                    checkNotNull(dao.receiptBytes(meta.id))
                }
                require(ReceiptImages.valid(bytes, meta)) { "invalid receipt" }
                meta.toEntity(bytes)
            }
            dao.clearReceipts(result.tx.id)
            rows.forEach { dao.insertReceipt(it) }
            val tx = result.tx.copy(updatedAt = clock.millis())
            dao.upsertTransaction(tx.toEntity())
            result.copy(tx = tx)
        }
    }

    /**
     * Stores several drafts together (one line, several operations): all of them, or none when one is not valid; then the
     * result is the one [SaveResult.Invalid] that says which and why. A draft whose record is already stored (sent twice) is
     * not stored again. An edit that changes nothing leaves the record untouched, its "updated" time included.
     */
    suspend fun saveAll(drafts: List<Draft>): List<SaveResult> {
        val now = now()
        val defaultAccount = defaultAccountId()
        val built = drafts.mapIndexed { index, draft ->
            val previous = if (draft.isEdit) get(draft.id) else null
            val refs = refs(draft.refundOf)
            Triple(index, previous, DraftBuilder.build(draft, refs, defaultAccount, now, previous))
        }
        built.firstOrNull { it.third.tx == null }?.let { (index, _, b) ->
            return listOf(SaveResult.Invalid(b.problems, index))
        }
        val results = db.withTransaction {
            built.map { (_, previous, b) ->
                val tagIds = b.tagNames.map { ensureTag(it, now.toInstant().toEpochMilli()) }.distinct().sorted()
                var tx = b.tx!!.copy(tagIds = tagIds)
                if (previous == null) {
                    if (dao.insertTransaction(tx.toEntity()) == -1L) {
                        return@map SaveResult.Saved(get(tx.id)!!, created = false)
                    }
                } else {
                    if (sameContent(previous, tx)) return@map SaveResult.Saved(previous, created = false)
                    dao.upsertTransaction(tx.toEntity())
                    dao.clearTags(tx.id)
                }
                dao.insertTransactionTags(tagIds.map { TransactionTagEntity(tx.id, it) })
                SaveResult.Saved(tx, created = previous == null)
            }
        }
        return results
    }

    /** The builder keeps the old time zone while the date and time stay; so equal content is equal records but for "updated". */
    private fun sameContent(a: Transaction, b: Transaction) = a.copy(updatedAt = 0) == b.copy(updatedAt = 0)

    /** The tag of this name, made when it does not exist yet; an archived tag comes back into use. */
    private suspend fun ensureTag(name: String, nowMs: Long): String {
        val norm = normalize(name)
        dao.tagByNorm(norm)?.let { existing ->
            if (existing.archived) dao.upsertTag(existing.copy(archived = false, updatedAt = nowMs))
            return existing.id
        }
        val tag = Tag(UUID.randomUUID().toString(), name.trim(), nowMs, nowMs)
        dao.upsertTag(tag.toEntity())
        return tag.id
    }

    suspend fun delete(id: String): Removed? = db.withTransaction {
        val tx = get(id) ?: return@withTransaction null
        val refunds = dao.refundsOf(id)
        val receipts = dao.receiptsOf(id).map { it.toEntity(checkNotNull(dao.receiptBytes(it.id))) }
        // the refunds stay; their link to this purchase is cleared by the database (ON DELETE SET NULL)
        dao.deleteTransaction(id)
        Removed(tx, refunds, receipts)
    }

    /** Puts a deleted record back exactly as it was, with its tags and the refunds that pointed to it. */
    suspend fun undo(removed: Removed) {
        db.withTransaction {
            val tx = removed.tx
            val tagIds = tx.tagIds.filter { dao.tag(it) != null }
            dao.insertTransaction(tx.copy(tagIds = tagIds).toEntity())
            dao.insertTransactionTags(tagIds.map { TransactionTagEntity(tx.id, it) })
            removed.receipts.forEach { dao.insertReceipt(it) }
            val refunds = removed.refunds.filter { dao.transaction(it) != null }
            if (refunds.isNotEmpty()) dao.relinkRefunds(refunds, tx.id)
        }
    }

    /** The same operation again, now: a new record with what the old one said, except when and its link to a purchase. */
    suspend fun repeat(id: String): SaveResult? {
        val tx = get(id) ?: return null
        // an old record's payment method may be archived by now; nobody can see or choose it, so the default one takes it
        val accountId = activeAccount(tx.accountId)
        val draft = Draft(
            type = tx.type,
            amount = Currencies.of(tx.currency)?.let { Decimal.format(tx.amountMinor, it.exponent) }.orEmpty(),
            currency = tx.currency,
            accountId = accountId,
            accountAmount = tx.accountAmountMinor?.takeIf { accountId != null }?.let { m ->
                dao.account(tx.accountId)?.currency?.let(Currencies::of)?.let { Decimal.format(m, it.exponent) }
            }.orEmpty(),
            toAccountId = tx.toAccountId,
            toAmount = tx.toAmountMinor?.let { m ->
                tx.toAccountId?.let { dao.account(it) }?.currency?.let(Currencies::of)?.let { Decimal.format(m, it.exponent) }
            }.orEmpty(),
            categoryId = tx.categoryId,
            payee = tx.payee.orEmpty(),
            description = tx.description.orEmpty(),
            note = tx.note.orEmpty(),
            tags = tagNames(tx.tagIds),
            date = null,
            time = DraftTime.Now,
            origin = Origin.Repeat,
        )
        return save(draft)
    }

    /** A refund of this purchase, to be checked and sent: the same amount and category, linked to it. */
    suspend fun refundDraft(id: String): Draft? {
        val tx = get(id) ?: return null
        if (tx.type != TxType.Expense) return null
        return Draft(
            type = TxType.Refund,
            amount = Currencies.of(tx.currency)?.let { Decimal.format(tx.amountMinor, it.exponent) }.orEmpty(),
            currency = tx.currency,
            accountId = activeAccount(tx.accountId),
            categoryId = tx.categoryId,
            payee = tx.payee.orEmpty(),
            description = tx.description.orEmpty(),
            refundOf = tx.id,
            origin = Origin.Form,
        )
    }

    // ---------- accounts ----------

    suspend fun saveAccount(edit: AccountEdit): EditResult {
        val name = edit.name.trim()
        if (name.isEmpty()) return EditResult.Error(EditError.NameMissing)
        val currency = Currencies.of(edit.currency) ?: return EditResult.Error(EditError.CurrencyUnknown)
        val opening = if (edit.opening.isBlank()) null else Decimal.parseHuman(edit.opening, currency.exponent)
            ?: return EditResult.Error(EditError.AmountInvalid)
        val nowMs = now().toInstant().toEpochMilli()
        return db.withTransaction {
            val all = dao.accountsNow()
            if (all.any { it.id != edit.id && it.nameNorm == normalize(name) }) return@withTransaction EditResult.Error(EditError.NameTaken)
            if (edit.id == null) {
                val account = Account(UUID.randomUUID().toString(), name, edit.kind, currency.code, opening, nowMs, nowMs, sort = dao.maxAccountSort() + 1)
                dao.upsertAccount(account.toEntity())
                EditResult.Ok(account.id)
            } else {
                val old = dao.account(edit.id)?.toDomain() ?: return@withTransaction EditResult.Error(EditError.NotFound)
                // the amounts of its records are in its currency: changing it would change what they mean
                if (old.currency != currency.code && dao.accountUsage(edit.id) > 0) return@withTransaction EditResult.Error(EditError.CurrencyLocked)
                val next = old.copy(name = name, kind = edit.kind, currency = currency.code, openingMinor = opening)
                if (next != old) dao.upsertAccount(next.copy(updatedAt = nowMs).toEntity())
                EditResult.Ok(old.id)
            }
        }
    }

    suspend fun setAccountArchived(id: String, archived: Boolean) {
        val old = dao.account(id) ?: return
        if (old.archived == archived) return
        dao.upsertAccount(old.copy(archived = archived, updatedAt = now().toInstant().toEpochMilli()))
    }

    /** Deletes an account nothing points to; an account with records can only be archived. */
    suspend fun deleteAccount(id: String): EditResult = db.withTransaction {
        if (dao.accountUsage(id) > 0) return@withTransaction EditResult.Error(EditError.InUse)
        dao.deleteAccount(id)
        if (settings.choices.value.defaultAccountId == id) settings.setDefaultAccount(null)
        EditResult.Ok(id)
    }

    /**
     * Sets an account right to what it really holds: an adjustment of the difference, dated now. Nothing is changed when
     * the figure already matches.
     */
    suspend fun reconcile(id: String, actual: String): SaveResult? {
        val account = dao.account(id)?.toDomain() ?: return null
        val currency = Currencies.of(account.currency) ?: return null
        val target = Decimal.parseHuman(actual, currency.exponent) ?: return SaveResult.Invalid(listOf(Problem.AmountInvalid))
        val balance = balancesNow()[id] ?: return null
        val delta = target - balance.minor
        if (delta == 0L) return null
        return save(Draft(type = TxType.Adjustment, amount = Decimal.format(delta, currency.exponent), accountId = id, origin = Origin.Reconcile))
    }

    // ---------- categories ----------

    suspend fun saveCategory(edit: CategoryEdit): EditResult {
        val name = edit.name.trim()
        if (name.isEmpty()) return EditResult.Error(EditError.NameMissing)
        val nowMs = now().toInstant().toEpochMilli()
        return db.withTransaction {
            val all = dao.categoriesNow().map { it.toDomain() }
            val parent = edit.parentId?.let { pid -> all.firstOrNull { it.id == pid } }
            // one level: a parent of the same kind that is not itself a subcategory, and not the category itself
            if (edit.parentId != null && (parent == null || parent.kind != edit.kind || parent.parentId != null || parent.id == edit.id)) {
                return@withTransaction EditResult.Error(EditError.ParentInvalid)
            }
            if (edit.parentId != null && edit.id != null && all.any { it.parentId == edit.id }) {
                return@withTransaction EditResult.Error(EditError.ParentInvalid)
            }
            if (all.any { it.id != edit.id && it.kind == edit.kind && it.parentId == edit.parentId && normalize(it.name) == normalize(name) }) {
                return@withTransaction EditResult.Error(EditError.NameTaken)
            }
            val keywords = edit.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { normalize(it) }
            if (edit.id == null) {
                val c = Category(UUID.randomUUID().toString(), name, edit.kind, edit.parentId, keywords, nowMs, nowMs, sort = dao.maxCategorySort() + 1)
                dao.upsertCategory(c.toEntity())
                EditResult.Ok(c.id)
            } else {
                val old = all.firstOrNull { it.id == edit.id } ?: return@withTransaction EditResult.Error(EditError.NotFound)
                if (old.kind != edit.kind && (dao.categoryUsage(old.id) > 0 || dao.childCount(old.id) > 0)) {
                    return@withTransaction EditResult.Error(EditError.KindLocked)
                }
                val next = old.copy(name = name, kind = edit.kind, parentId = edit.parentId, keywords = keywords)
                if (next != old) dao.upsertCategory(next.copy(updatedAt = nowMs).toEntity())
                EditResult.Ok(old.id)
            }
        }
    }

    suspend fun setCategoryArchived(id: String, archived: Boolean) {
        val old = dao.category(id) ?: return
        if (old.archived == archived) return
        dao.upsertCategory(old.copy(archived = archived, updatedAt = now().toInstant().toEpochMilli()))
    }

    suspend fun deleteCategory(id: String): EditResult = db.withTransaction {
        if (dao.categoryUsage(id) > 0 || dao.childCount(id) > 0) return@withTransaction EditResult.Error(EditError.InUse)
        dao.deleteCategory(id)
        EditResult.Ok(id)
    }

    // ---------- tags ----------

    suspend fun renameTag(id: String, name: String): EditResult {
        val clean = name.trim().removePrefix("#").trim()
        if (clean.isEmpty()) return EditResult.Error(EditError.NameMissing)
        return db.withTransaction {
            val old = dao.tag(id) ?: return@withTransaction EditResult.Error(EditError.NotFound)
            val other = dao.tagByNorm(normalize(clean))
            if (other != null && other.id != id) return@withTransaction EditResult.Error(EditError.NameTaken)
            if (old.name != clean) dao.upsertTag(old.copy(name = clean, nameNorm = normalize(clean), updatedAt = now().toInstant().toEpochMilli()))
            EditResult.Ok(id)
        }
    }

    suspend fun setTagArchived(id: String, archived: Boolean) {
        val old = dao.tag(id) ?: return
        if (old.archived == archived) return
        dao.upsertTag(old.copy(archived = archived, updatedAt = now().toInstant().toEpochMilli()))
    }

    suspend fun deleteTag(id: String): EditResult = db.withTransaction {
        if (dao.tagUsage(id) > 0) return@withTransaction EditResult.Error(EditError.InUse)
        dao.deleteTag(id)
        EditResult.Ok(id)
    }

    // ---------- the journal as a whole ----------

    /**
     * A new journal: the starter categories and the one account in [language], in [currency]. Only into an empty journal; a
     * journal that already has accounts is left as it is.
     */
    suspend fun seed(language: Language, currency: String) {
        val code = Currencies.require(currency).code
        val nowMs = now().toInstant().toEpochMilli()
        db.withTransaction {
            if (dao.accountsNow().isNotEmpty()) return@withTransaction
            Starter.accounts.forEachIndexed { i, a ->
                dao.upsertAccount(Account(UUID.randomUUID().toString(), Starter.name(a.ru, a.en, language), a.kind, code, null, nowMs, nowMs, sort = i).toEntity())
            }
            if (dao.categoriesNow().isEmpty()) {
                Starter.categories.filter { it.kind == CategoryKind.Expense }.forEachIndexed { i, c ->
                    dao.upsertCategory(Category(UUID.randomUUID().toString(), Starter.name(c.ru, c.en, language), c.kind, null, c.keywords, nowMs, nowMs, sort = i).toEntity())
                }
            }
        }
        settings.setDefaultCurrency(code)
        settings.setDefaultAccount(defaultAccountId())
    }

    /** Everything gone: records, accounts, categories, tags. Used by "Стереть всё". */
    suspend fun eraseAll() {
        db.withTransaction {
            dao.wipeTransactionTags()
            dao.wipeTransactions()
            dao.wipeTags()
            dao.detachCategories()
            dao.wipeCategories()
            dao.wipeAccounts()
        }
    }
}
