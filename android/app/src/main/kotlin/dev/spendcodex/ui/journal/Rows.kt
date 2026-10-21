package dev.spendcodex.ui.journal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.LedgerRow
import dev.spendcodex.coreui.SwipeToDelete
import dev.spendcodex.data.db.FeedRow
import dev.spendcodex.data.db.FeedSql
import dev.spendcodex.data.domain.TxType
import java.time.LocalDate
import java.time.LocalTime

/** A row of the journal or of search results, or the heading of a day. */
sealed interface FeedItem {
    val key: String

    data class Op(val row: FeedRow) : FeedItem {
        override val key: String get() = row.id
    }

    data class Day(val date: LocalDate) : FeedItem {
        override val key: String get() = "day-$date"
    }
}

/**
 * Rows newest first with each day's heading after its rows: in a list laid out from the bottom (the journal) the heading
 * comes out above its day. [headingFirst] puts it before them, for a list read from the top (search).
 */
fun feedItems(rows: List<FeedRow>, headingFirst: Boolean = false): List<FeedItem> {
    val out = ArrayList<FeedItem>(rows.size + rows.size / 4 + 1)
    var i = 0
    while (i < rows.size) {
        val date = rows[i].date
        var j = i
        while (j < rows.size && rows[j].date == date) j++
        val day = FeedItem.Day(LocalDate.parse(date))
        if (headingFirst) out += day
        for (k in i until j) out += FeedItem.Op(rows[k])
        if (!headingFirst) out += day
        i = j
    }
    return out
}

/**
 * What a row says: the thing, then when, where and how much, the way it is easiest to remember. With [onDelete] a swipe to
 * the right deletes it (and TalkBack offers the same as an action).
 */
@Composable
fun OperationRow(row: FeedRow, onClick: () -> Unit, modifier: Modifier = Modifier, onDelete: (() -> Unit)? = null) {
    val c = AppTheme.colors
    val type = TxType.of(row.type) ?: TxType.Expense
    val category = row.categoryName?.let { name -> row.parentName?.let { "$it › $name" } ?: name }
    val accounts = "${row.accountName} → ${row.toAccountName.orEmpty()}"
    val title = row.description ?: row.payee ?: when (type) {
        TxType.Transfer -> accounts
        else -> category ?: Texts.typeTitle(type)
    }
    val caption = buildList {
        when (type) {
            TxType.Transfer -> { add(Texts.type(type)); if (title != accounts) add(accounts) }
            TxType.Adjustment -> { add(Texts.type(type)); add(row.accountName) }
            else -> {
                if (type == TxType.Refund) add(Texts.type(type))
                if (category != null && title != category) add(category)
                if (row.description != null && row.payee != null) add(row.payee!!)
            }
        }
        row.tagNames?.split(FeedSql.TAG_SEPARATOR)?.filter { it.isNotEmpty() }?.sorted()?.joinToString(" ") { "#$it" }?.let { add(it) }
        if (row.receiptCount > 0) add(Texts.receiptCount(row.receiptCount))
    }.joinToString(" · ").ifEmpty { null }

    val amount = when (type) {
        TxType.Expense -> Format.money(row.amountMinor, row.currency)
        TxType.Income, TxType.Refund -> Format.money(row.amountMinor, row.currency, withSign = true)
        TxType.Transfer -> Format.money(row.amountMinor, row.currency)
        TxType.Adjustment -> Format.money(row.amountMinor, row.currency, withSign = true)
    }
    val amountColor: Color = when (type) {
        TxType.Income, TxType.Refund -> c.income
        TxType.Transfer, TxType.Adjustment -> c.secondary
        TxType.Expense -> c.foreground
    }
    // how the account itself moved, when the operation was in another currency
    val amountCaption = when {
        type == TxType.Transfer && row.toAmountMinor != null && row.toAccountCurrency != null -> "→ " + Format.money(row.toAmountMinor!!, row.toAccountCurrency!!)
        else -> null
    }
    val line: @Composable (Modifier) -> Unit = { m -> LedgerRow(
        title = title,
        amount = amount,
        onClick = onClick,
        modifier = m.testTag("operation"),
        time = row.timeMin?.let { Format.time(LocalTime.of(it / 60, it % 60)) },
        caption = caption,
        amountColor = amountColor,
        amountCaption = amountCaption,
    ) }
    if (onDelete == null) line(modifier)
    else SwipeToDelete(Texts.DELETE, onDelete, modifier) {
        line(Modifier.semantics { customActions = listOf(CustomAccessibilityAction(Texts.DELETE) { onDelete(); true }) })
    }
}
