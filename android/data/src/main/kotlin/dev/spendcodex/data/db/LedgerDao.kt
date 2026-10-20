package dev.spendcodex.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Every query of the journal. Writes that belong together are put together by [dev.spendcodex.data.repo.Ledger] in one
 * transaction. No REPLACE anywhere: it deletes the old row first, which would cascade to its tags and unlink its refunds.
 */
@Dao
interface LedgerDao {
    // Metadata queries deliberately exclude blobs: a whole photo collection must never be loaded into one cursor.
    @Query("SELECT id, transaction_id, width, height, size, sha256, created_at, sort FROM receipts WHERE transaction_id = :id ORDER BY sort, id")
    suspend fun receiptsOf(id: String): List<dev.spendcodex.data.receipts.Receipt>

    @Query("SELECT id, transaction_id, width, height, size, sha256, created_at, sort FROM receipts ORDER BY transaction_id, sort, id")
    suspend fun allReceipts(): List<dev.spendcodex.data.receipts.Receipt>

    @Query("SELECT content FROM receipts WHERE id = :id")
    suspend fun receiptBytes(id: String): ByteArray?

    @Insert
    suspend fun insertReceipt(receipt: ReceiptEntity)

    @Query("DELETE FROM receipts WHERE transaction_id = :id")
    suspend fun clearReceipts(id: String)

    @Query("SELECT COUNT(*) FROM receipts")
    fun receiptCount(): Flow<Int>

    // ---------- accounts ----------

    @Query("SELECT * FROM accounts ORDER BY archived, sort, created_at, id")
    fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY archived, sort, created_at, id")
    suspend fun accountsNow(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: String): AccountEntity?

    @Upsert
    suspend fun upsertAccount(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: String)

    @Query("SELECT COUNT(*) FROM transactions WHERE account_id = :id OR to_account_id = :id")
    suspend fun accountUsage(id: String): Int

    @Query("SELECT COALESCE(MAX(sort), 0) FROM accounts")
    suspend fun maxAccountSort(): Int

    // ---------- categories ----------

    @Query("SELECT * FROM categories ORDER BY archived, kind, sort, created_at, id")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY archived, kind, sort, created_at, id")
    suspend fun categoriesNow(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun category(id: String): CategoryEntity?

    @Upsert
    suspend fun upsertCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("SELECT COUNT(*) FROM transactions WHERE category_id = :id")
    suspend fun categoryUsage(id: String): Int

    @Query("SELECT COUNT(*) FROM categories WHERE parent_id = :id")
    suspend fun childCount(id: String): Int

    @Query("SELECT COALESCE(MAX(sort), 0) FROM categories")
    suspend fun maxCategorySort(): Int

    @Query("SELECT category_id AS id, COUNT(*) AS count FROM transactions WHERE category_id IS NOT NULL GROUP BY category_id")
    fun categoryCounts(): Flow<List<UsageRow>>

    // ---------- tags ----------

    @Query("SELECT * FROM tags ORDER BY archived, name_norm, id")
    fun tags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY archived, name_norm, id")
    suspend fun tagsNow(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun tag(id: String): TagEntity?

    @Query("SELECT * FROM tags WHERE name_norm = :norm")
    suspend fun tagByNorm(norm: String): TagEntity?

    @Upsert
    suspend fun upsertTag(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: String)

    @Query("SELECT COUNT(*) FROM transaction_tags WHERE tag_id = :id")
    suspend fun tagUsage(id: String): Int

    @Query("SELECT tag_id AS id, COUNT(*) AS count FROM transaction_tags GROUP BY tag_id")
    fun tagCounts(): Flow<List<UsageRow>>

    // ---------- transactions ----------

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeTransaction(id: String): Flow<TransactionEntity?>

    @Query("SELECT type FROM transactions WHERE id = :id")
    suspend fun typeOf(id: String): String?

    @Query("SELECT tag_id FROM transaction_tags WHERE transaction_id = :id")
    suspend fun tagIdsOf(id: String): List<String>

    /** -1 when a record with this id is already there: the same draft sent twice is stored once. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(tx: TransactionEntity): Long

    @Upsert
    suspend fun upsertTransaction(tx: TransactionEntity)

    @Query("DELETE FROM transaction_tags WHERE transaction_id = :id")
    suspend fun clearTags(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionTags(links: List<TransactionTagEntity>)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: String)

    @Query("SELECT id FROM transactions WHERE refund_of = :id")
    suspend fun refundsOf(id: String): List<String>

    @Query("UPDATE transactions SET refund_of = :original WHERE id IN (:ids)")
    suspend fun relinkRefunds(ids: List<String>, original: String)

    @Query("SELECT COUNT(*) FROM transactions")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun countNow(): Int

    @Query("SELECT MIN(date) FROM transactions")
    suspend fun firstDate(): String?

    @Query("SELECT MAX(date) FROM transactions")
    suspend fun lastDate(): String?

    @RawQuery(observedEntities = [TransactionEntity::class, TransactionTagEntity::class, AccountEntity::class, CategoryEntity::class, TagEntity::class, ReceiptEntity::class])
    fun feed(query: SupportSQLiteQuery): Flow<List<FeedRow>>

    @RawQuery
    suspend fun feedNow(query: SupportSQLiteQuery): List<FeedRow>

    @RawQuery(observedEntities = [TransactionEntity::class, TransactionTagEntity::class, AccountEntity::class, CategoryEntity::class, TagEntity::class])
    fun totals(query: SupportSQLiteQuery): Flow<List<TypeTotal>>

    @RawQuery
    suspend fun totalsNow(query: SupportSQLiteQuery): List<TypeTotal>

    @RawQuery
    suspend fun transactionsRaw(query: SupportSQLiteQuery): List<TransactionEntity>

    @Query("SELECT currency, type, SUM(amount_minor) AS total, COUNT(*) AS count FROM transactions WHERE date = :date GROUP BY currency, type")
    fun dayTotals(date: String): Flow<List<TypeTotal>>

    // ---------- balances ----------

    @Query(
        """SELECT account_id, type, currency, SUM(amount_minor) AS amount, COALESCE(SUM(account_amount_minor), 0) AS account_amount,
           COUNT(*) AS count, SUM(CASE WHEN account_amount_minor IS NULL THEN 1 ELSE 0 END) AS missing
           FROM transactions GROUP BY account_id, type, currency""",
    )
    fun sides(): Flow<List<SideRow>>

    @Query(
        """SELECT account_id, type, currency, SUM(amount_minor) AS amount, COALESCE(SUM(account_amount_minor), 0) AS account_amount,
           COUNT(*) AS count, SUM(CASE WHEN account_amount_minor IS NULL THEN 1 ELSE 0 END) AS missing
           FROM transactions GROUP BY account_id, type, currency""",
    )
    suspend fun sidesNow(): List<SideRow>

    @Query(
        """SELECT to_account_id AS account_id, SUM(COALESCE(to_amount_minor, amount_minor)) AS arrived, COUNT(*) AS count
           FROM transactions WHERE type = 'transfer' AND to_account_id IS NOT NULL GROUP BY to_account_id""",
    )
    fun arrivals(): Flow<List<ArrivalRow>>

    @Query(
        """SELECT to_account_id AS account_id, SUM(COALESCE(to_amount_minor, amount_minor)) AS arrived, COUNT(*) AS count
           FROM transactions WHERE type = 'transfer' AND to_account_id IS NOT NULL GROUP BY to_account_id""",
    )
    suspend fun arrivalsNow(): List<ArrivalRow>

    // ---------- what the quick line remembers ----------

    /** Blocking on purpose: the quick line asks while it reads, off the main thread. */
    @Query(
        """SELECT category_id, account_id FROM transactions
           WHERE description_norm = :key AND type IN ('expense', 'income', 'refund')
           ORDER BY date DESC, created_at DESC LIMIT 1""",
    )
    fun hint(key: String): HintRow?

    /** The latest record of each description that starts with [prefix] (already escaped for LIKE). */
    @Query(
        """SELECT * FROM transactions t
           WHERE t.description_norm LIKE :prefix ESCAPE '\' AND t.type IN ('expense', 'income')
             AND t.created_at = (SELECT MAX(created_at) FROM transactions WHERE description_norm = t.description_norm)
           ORDER BY t.created_at DESC LIMIT :limit""",
    )
    suspend fun suggestions(prefix: String, limit: Int): List<TransactionEntity>

    /** The most recent expenses, for linking a refund to the purchase it returns. */
    @Query("SELECT * FROM transactions WHERE type = 'expense' ORDER BY date DESC, COALESCE(time_min, 1440) DESC, created_at DESC LIMIT :limit")
    suspend fun recentExpenses(limit: Int): List<TransactionEntity>

    @Query("SELECT account_id FROM transactions WHERE type != 'adjustment' ORDER BY created_at DESC LIMIT 1")
    suspend fun lastUsedAccount(): String?

    // ---------- everything, for the backup ----------

    @Query("SELECT * FROM transactions ORDER BY date, COALESCE(time_min, 1440), created_at, id")
    suspend fun allTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM transaction_tags ORDER BY transaction_id, tag_id")
    suspend fun allTransactionTags(): List<TransactionTagEntity>

    @Insert
    suspend fun insertAccounts(rows: List<AccountEntity>)

    @Insert
    suspend fun insertCategories(rows: List<CategoryEntity>)

    @Insert
    suspend fun insertTags(rows: List<TagEntity>)

    @Insert
    suspend fun insertTransactions(rows: List<TransactionEntity>)

    @Query("DELETE FROM transaction_tags")
    suspend fun wipeTransactionTags()

    @Query("DELETE FROM transactions")
    suspend fun wipeTransactions()

    @Query("DELETE FROM tags")
    suspend fun wipeTags()

    @Query("UPDATE categories SET parent_id = NULL")
    suspend fun detachCategories()

    @Query("DELETE FROM categories")
    suspend fun wipeCategories()

    @Query("DELETE FROM accounts")
    suspend fun wipeAccounts()
}
