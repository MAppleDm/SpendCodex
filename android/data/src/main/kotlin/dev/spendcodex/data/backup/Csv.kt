package dev.spendcodex.data.backup

import dev.spendcodex.data.domain.Balance
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal

/**
 * CSV the way RFC 4180 says: comma separated, CRLF line ends, a field in double quotes when it holds a comma, a quote, a line
 * break or spaces at its ends, quotes inside doubled. UTF-8 without a byte order mark. Numbers with a dot.
 *
 * Text a person wrote (names, descriptions, notes, tags) that starts with = + - @ or a tab is prefixed with a single quote:
 * a spreadsheet would otherwise run it as a formula (CSV injection). Only the CSV copy is changed this way; the JSON file
 * in the same archive keeps the exact text. Generated columns (ids, dates, amounts) are never prefixed.
 */
object Csv {
    private const val CRLF = "\r\n"

    class Table(val header: List<String>) {
        private val rows = ArrayList<List<String>>()

        fun row(vararg cells: Cell) {
            require(cells.size == header.size) { "row of ${cells.size} for ${header.size} columns" }
            rows += cells.map { it.render() }
        }

        override fun toString(): String = buildString {
            append(header.joinToString(",") { quote(it) }).append(CRLF)
            rows.forEach { append(it.joinToString(",") { cell -> quote(cell) }).append(CRLF) }
        }

        val size: Int get() = rows.size
    }

    /** A cell: [Text] is what a person wrote (guarded), [Value] is what the app generated (as is). */
    sealed interface Cell {
        fun render(): String
    }

    data class Text(val value: String?) : Cell {
        override fun render(): String {
            val v = value ?: return ""
            return if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        }
    }

    data class Value(val value: String?) : Cell {
        override fun render(): String = value.orEmpty()
    }

    fun quote(field: String): String {
        val needs = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            (field.isNotEmpty() && (field.first().isWhitespace() || field.last().isWhitespace()))
        return if (needs) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }

    fun t(value: String?): Cell = Text(value)
    fun v(value: Any?): Cell = Value(value?.toString())

    // ---------- the tables of an export ----------

    fun transactions(file: ExportFile): Table {
        val accounts = file.accounts.associateBy { it.id }
        val categories = file.categories.associateBy { it.id }
        val tags = file.tags.associateBy { it.id }
        val table = Table(
            listOf(
                "id", "date", "time", "time_zone", "type", "amount", "currency",
                "account_id", "account", "account_currency", "account_amount",
                "to_account_id", "to_account", "to_currency", "to_amount",
                "category_id", "category", "parent_category", "description", "payee", "tags", "note",
                "refund_of", "origin", "input_text", "created_at", "updated_at",
            ),
        )
        for (tx in file.transactions) {
            val account = accounts[tx.accountId]
            val to = tx.toAccountId?.let { accounts[it] }
            val category = tx.categoryId?.let { categories[it] }
            val parent = category?.parentId?.let { categories[it] }
            table.row(
                v(tx.id), v(tx.date), v(tx.time), v(tx.timeZone), v(tx.type), v(tx.amount), v(tx.currency),
                v(tx.accountId), t(account?.name), v(account?.currency), v(tx.accountAmount),
                v(tx.toAccountId), t(to?.name), v(to?.currency), v(tx.toAmount),
                v(tx.categoryId), t(category?.name), t(parent?.name), t(tx.description), t(tx.payee),
                t(tx.tagIds.mapNotNull { tags[it]?.name }.sorted().joinToString("; ").ifEmpty { null }), t(tx.note),
                v(tx.refundOf), v(tx.origin), t(tx.inputText), v(tx.createdAt), v(tx.updatedAt),
            )
        }
        return table
    }

    /** Accounts, with the balance as the app computes it at export time (derived: not read back by a restore). */
    fun accounts(file: ExportFile, balances: Map<String, Balance>): Table {
        val table = Table(listOf("id", "name", "kind", "currency", "opening_balance", "computed_balance", "computed_balance_complete", "archived", "sort", "created_at", "updated_at"))
        for (a in file.accounts) {
            val b = balances[a.id]
            val exponent = Currencies.of(a.currency)?.exponent ?: 2
            table.row(
                v(a.id), t(a.name), v(a.kind), v(a.currency), v(a.openingBalance),
                v(b?.let { Decimal.format(it.minor, exponent) }), v(b?.complete),
                v(a.archived), v(a.sort), v(a.createdAt), v(a.updatedAt),
            )
        }
        return table
    }

    fun categories(file: ExportFile): Table {
        val byId = file.categories.associateBy { it.id }
        val table = Table(listOf("id", "name", "kind", "parent_id", "parent", "keywords", "archived", "sort", "created_at", "updated_at"))
        for (c in file.categories) {
            table.row(
                v(c.id), t(c.name), v(c.kind), v(c.parentId), t(c.parentId?.let { byId[it]?.name }), t(c.keywords.joinToString("; ").ifEmpty { null }),
                v(c.archived), v(c.sort), v(c.createdAt), v(c.updatedAt),
            )
        }
        return table
    }

    fun tags(file: ExportFile): Table {
        val table = Table(listOf("id", "name", "archived", "created_at", "updated_at"))
        file.tags.forEach { table.row(v(it.id), t(it.name), v(it.archived), v(it.createdAt), v(it.updatedAt)) }
        return table
    }

    /** One row per tag on a record: the relation itself, for joining in a spreadsheet or a script. */
    fun transactionTags(file: ExportFile): Table {
        val table = Table(listOf("transaction_id", "tag_id"))
        file.transactions.forEach { tx -> tx.tagIds.sorted().forEach { table.row(v(tx.id), v(it)) } }
        return table
    }

    fun receipts(file: ExportFile): Table = Table(listOf("id", "transaction_id", "path", "mime_type", "bytes", "sha256", "width", "height", "created_at", "sort")).also { table ->
        file.receipts.forEach { r -> table.row(v(r.id), v(r.transactionId), v(r.path), v(r.mimeType), v(r.bytes), v(r.sha256), v(r.width), v(r.height), v(r.createdAt), v(r.sort)) }
    }
}
