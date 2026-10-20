package dev.spendcodex.data.domain

import java.time.LocalDate
import java.time.LocalTime

/** What happened to the money. The codes are the ones written to files; they never change. */
enum class TxType(val code: String) {
    /** Money left an account for something. */
    Expense("expense"),

    /** Money came into an account from outside. */
    Income("income"),

    /** Money moved between two of the user's own accounts: neither spent nor earned. */
    Transfer("transfer"),

    /** Money came back for an earlier expense: an expense undone, not an income. */
    Refund("refund"),

    /** The account's figure set right to what the bank or the wallet says: a signed correction, neither spent nor earned. */
    Adjustment("adjustment");

    /** The kind of category an operation of this type can have, if any. */
    val categoryKind: CategoryKind?
        get() = when (this) {
            Expense, Refund -> CategoryKind.Expense
            Income -> CategoryKind.Income
            Transfer, Adjustment -> null
        }

    companion object {
        fun of(code: String?): TxType? = entries.firstOrNull { it.code == code }
    }
}

/** How a record came to be: written in the line, filled in the form, read by DeepSeek, repeated, a balance check. */
enum class Origin(val code: String) {
    Line("line"), Form("form"), Ai("ai"), Repeat("repeat"), Reconcile("reconcile");

    companion object {
        fun of(code: String?): Origin? = entries.firstOrNull { it.code == code }
    }
}

enum class AccountKind(val code: String) {
    Cash("cash"), Card("card"), Bank("bank"), Savings("savings"), Other("other");

    companion object {
        fun of(code: String?): AccountKind? = entries.firstOrNull { it.code == code }
    }
}

enum class CategoryKind(val code: String) {
    Expense("expense"), Income("income");

    companion object {
        fun of(code: String?): CategoryKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Where money is kept. [openingMinor] is what was there when the journal started (null: not told, the balance then is only
 * the movement since). An account in use is never deleted, only archived: its history stays whole.
 */
data class Account(
    val id: String,
    val name: String,
    val kind: AccountKind,
    val currency: String,
    val openingMinor: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
    val sort: Int = 0,
)

/**
 * A way to group operations, not a methodology: one level of subcategories ([parentId]), and [keywords] the quick line knows
 * it by ("кофе" for "Кафе").
 */
data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val parentId: String?,
    val keywords: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
    val sort: Int = 0,
)

data class Tag(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
)

/**
 * One record of the journal.
 *
 * Money: [amountMinor] in [currency] is what was paid or received, as written (always positive, except an [TxType.Adjustment],
 * which is signed). When that currency is not the account's, [accountAmountMinor] is what the account actually moved in its
 * own currency, if known. A transfer leaves [accountId] in its currency and arrives at [toAccountId] as [toAmountMinor] (only
 * when the two currencies differ).
 *
 * Time: [date] is the day the user means; [time] is the minute, null when it is not known (an operation written for
 * "yesterday" has no time rather than an invented one); [timeZone] is the phone's zone when it was recorded.
 *
 * [createdAt] and [updatedAt] are when the record itself was made and last changed (epoch ms, UTC); they say nothing about
 * when the money moved.
 */
data class Transaction(
    val id: String,
    val type: TxType,
    val date: LocalDate,
    val time: LocalTime?,
    val timeZone: String?,
    val amountMinor: Long,
    val currency: String,
    val accountId: String,
    val accountAmountMinor: Long? = null,
    val toAccountId: String? = null,
    val toAmountMinor: Long? = null,
    val categoryId: String? = null,
    val payee: String? = null,
    val description: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val refundOf: String? = null,
    val origin: Origin = Origin.Form,
    val inputText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** What the search and the scoped exports look for. Empty sets mean "any". */
data class Filter(
    val text: String = "",
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val types: Set<TxType> = emptySet(),
    val accountIds: Set<String> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val currencies: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = text.isBlank() && from == null && to == null && types.isEmpty() && accountIds.isEmpty() &&
            categoryIds.isEmpty() && tagIds.isEmpty() && currencies.isEmpty()
}

/** Lowercase, "ё" as "е", spaces squeezed: the form names and texts are compared and searched in. */
fun normalize(text: String): String =
    text.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
