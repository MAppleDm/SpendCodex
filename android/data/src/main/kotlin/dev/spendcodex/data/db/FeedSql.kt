package dev.spendcodex.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.normalize

/**
 * The journal's one SELECT with a [Filter] in it. Every value goes in as a bound argument, never into the text.
 * The order is the journal's order, newest first; ties are broken by when the record was made and then its id, so two
 * operations at the same minute always come in the same order.
 */
object FeedSql {
    const val TAG_SEPARATOR = '\u001F'

    private const val FROM = """
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        LEFT JOIN accounts ta ON ta.id = t.to_account_id
        LEFT JOIN categories c ON c.id = t.category_id
        LEFT JOIN categories p ON p.id = c.parent_id
    """

    private const val COLUMNS = """
        SELECT t.id, t.type, t.date, t.time_min, t.amount_minor, t.currency, t.account_id, a.name AS account_name,
               a.currency AS account_currency, t.account_amount_minor, t.to_account_id, ta.name AS to_account_name,
               ta.currency AS to_account_currency, t.to_amount_minor, t.category_id, c.name AS category_name, p.name AS parent_name,
               t.payee, t.description, t.note, t.refund_of,
               (SELECT group_concat(g.name, char(31)) FROM transaction_tags tt JOIN tags g ON g.id = tt.tag_id
                 WHERE tt.transaction_id = t.id) AS tag_names,
               t.created_at, (SELECT COUNT(*) FROM receipts r WHERE r.transaction_id = t.id) AS receipt_count
    """

    const val ORDER_DESC = " ORDER BY t.date DESC, COALESCE(t.time_min, 1440) DESC, t.created_at DESC, t.id DESC"
    const val ORDER_ASC = " ORDER BY t.date, COALESCE(t.time_min, 1440), t.created_at, t.id"

    fun feed(filter: Filter, limit: Int): SupportSQLiteQuery {
        val (where, args) = where(filter)
        return SimpleSQLiteQuery("$COLUMNS $FROM $where $ORDER_DESC LIMIT ?", (args + limit).toTypedArray())
    }

    fun totals(filter: Filter): SupportSQLiteQuery {
        val (where, args) = where(filter)
        return SimpleSQLiteQuery(
            "SELECT t.currency AS currency, t.type AS type, SUM(t.amount_minor) AS total, COUNT(*) AS count $FROM $where GROUP BY t.currency, t.type",
            args.toTypedArray(),
        )
    }

    /** The stored records themselves, oldest first, for a scoped export. */
    fun records(filter: Filter): SupportSQLiteQuery {
        val (where, args) = where(filter)
        return SimpleSQLiteQuery("SELECT t.* $FROM $where $ORDER_ASC", args.toTypedArray())
    }

    /** `%word%` for LIKE, with the LIKE wildcards in the word itself escaped. */
    fun contains(word: String): String = "%" + escape(word) + "%"

    fun startsWith(word: String): String = escape(word) + "%"

    private fun escape(word: String) = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    internal fun where(f: Filter): Pair<String, List<Any>> {
        val clauses = ArrayList<String>()
        val args = ArrayList<Any>()
        // every word must be found somewhere: in the record's text, its category or a tag. Not in its account's name: the
        // account is not shown, and in an older journal "карта" would find every record paid by card
        normalize(f.text).split(' ').filter { it.isNotEmpty() }.forEach { word ->
            val like = contains(word)
            clauses += """(t.search_text LIKE ? ESCAPE '\' OR c.name_norm LIKE ? ESCAPE '\' OR p.name_norm LIKE ? ESCAPE '\'
                OR EXISTS (SELECT 1 FROM transaction_tags tt JOIN tags g ON g.id = tt.tag_id
                           WHERE tt.transaction_id = t.id AND g.name_norm LIKE ? ESCAPE '\'))"""
            repeat(4) { args += like }
        }
        f.from?.let { clauses += "t.date >= ?"; args += it.toString() }
        f.to?.let { clauses += "t.date <= ?"; args += it.toString() }
        if (f.types.isNotEmpty()) {
            clauses += "t.type IN (${marks(f.types.size)})"
            args.addAll(f.types.map { it.code })
        }
        if (f.accountIds.isNotEmpty()) {
            clauses += "(t.account_id IN (${marks(f.accountIds.size)}) OR t.to_account_id IN (${marks(f.accountIds.size)}))"
            args.addAll(f.accountIds)
            args.addAll(f.accountIds)
        }
        if (f.categoryIds.isNotEmpty()) {
            // a category includes its subcategories
            clauses += "(t.category_id IN (${marks(f.categoryIds.size)}) OR c.parent_id IN (${marks(f.categoryIds.size)}))"
            args.addAll(f.categoryIds)
            args.addAll(f.categoryIds)
        }
        if (f.tagIds.isNotEmpty()) {
            clauses += "EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transaction_id = t.id AND tt.tag_id IN (${marks(f.tagIds.size)}))"
            args.addAll(f.tagIds)
        }
        if (f.currencies.isNotEmpty()) {
            clauses += "t.currency IN (${marks(f.currencies.size)})"
            args.addAll(f.currencies)
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to args
    }

    private fun marks(n: Int) = List(n) { "?" }.joinToString(",")
}
