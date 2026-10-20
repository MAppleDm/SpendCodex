package dev.spendcodex.data.domain

import java.time.LocalDate

/** Why a record cannot be kept as it is. Each one names the field it is about, so a form can point at it. */
enum class Problem(val field: Field) {
    AmountMissing(Field.Amount),
    AmountInvalid(Field.Amount),
    AmountNotPositive(Field.Amount),
    AmountTooLarge(Field.Amount),
    AdjustmentZero(Field.Amount),
    CurrencyUnknown(Field.Currency),
    AccountMissing(Field.Account),
    AccountUnknown(Field.Account),
    AccountArchived(Field.Account),
    AccountAmountInvalid(Field.AccountAmount),
    AccountAmountUnneeded(Field.AccountAmount),
    AdjustmentCurrency(Field.Currency),
    TransferTargetMissing(Field.ToAccount),
    TransferTargetUnknown(Field.ToAccount),
    TransferTargetArchived(Field.ToAccount),
    TransferSameAccount(Field.ToAccount),
    TransferCurrency(Field.Currency),
    TransferAmountMissing(Field.ToAmount),
    TransferAmountInvalid(Field.ToAmount),
    TransferAmountUnneeded(Field.ToAmount),
    CategoryUnknown(Field.Category),
    CategoryWrongKind(Field.Category),
    CategoryNotAllowed(Field.Category),
    CategoryArchived(Field.Category),
    RefundTargetUnknown(Field.RefundOf),
    RefundTargetNotExpense(Field.RefundOf),
    RefundLinkNotRefund(Field.RefundOf),
    TagUnknown(Field.Tags),
    DateOutOfRange(Field.Date),
    TextTooLong(Field.Note),
    ;

    enum class Field { Amount, Currency, Account, AccountAmount, ToAccount, ToAmount, Category, RefundOf, Tags, Date, Note }
}

/** What a record is checked against: the journal's accounts, categories and tags, and the type of another record by id. */
class Refs(
    val accounts: Map<String, Account>,
    val categories: Map<String, Category>,
    val tagIds: Set<String>,
    val typeOf: (String) -> TxType?,
)

object Rules {
    val EARLIEST: LocalDate = LocalDate.of(1900, 1, 1)
    val LATEST: LocalDate = LocalDate.of(2199, 12, 31)
    const val MAX_TEXT = 2_000
    const val MAX_NOTE = 20_000

    /**
     * Every rule a stored record keeps. [previous] is the record as it was before an edit: an archived account or category it
     * already had may stay (archiving must not lock old records), a new one may not be chosen.
     */
    fun validate(tx: Transaction, refs: Refs, previous: Transaction? = null): List<Problem> {
        val problems = ArrayList<Problem>()
        val currency = Currencies.of(tx.currency)
        if (currency == null) problems += Problem.CurrencyUnknown

        when {
            tx.type == TxType.Adjustment && tx.amountMinor == 0L -> problems += Problem.AdjustmentZero
            tx.type != TxType.Adjustment && tx.amountMinor <= 0L -> problems += Problem.AmountNotPositive
        }
        if (kotlin.math.abs(tx.amountMinor) > Decimal.MAX_MINOR) problems += Problem.AmountTooLarge

        val account = refs.accounts[tx.accountId]
        if (account == null) {
            problems += Problem.AccountUnknown
        } else if (account.archived && previous?.accountId != tx.accountId) {
            problems += Problem.AccountArchived
        }

        // the account's own movement, when the operation was in another currency
        when {
            tx.accountAmountMinor == null -> Unit
            tx.type == TxType.Transfer || tx.type == TxType.Adjustment -> problems += Problem.AccountAmountUnneeded
            account != null && account.currency == tx.currency -> problems += Problem.AccountAmountUnneeded
            tx.accountAmountMinor <= 0L || tx.accountAmountMinor > Decimal.MAX_MINOR -> problems += Problem.AccountAmountInvalid
        }

        if (tx.type == TxType.Adjustment && account != null && account.currency != tx.currency) problems += Problem.AdjustmentCurrency

        if (tx.type == TxType.Transfer) {
            val to = tx.toAccountId?.let { refs.accounts[it] }
            when {
                tx.toAccountId == null -> problems += Problem.TransferTargetMissing
                to == null -> problems += Problem.TransferTargetUnknown
                tx.toAccountId == tx.accountId -> problems += Problem.TransferSameAccount
                to.archived && previous?.toAccountId != tx.toAccountId -> problems += Problem.TransferTargetArchived
            }
            if (account != null && account.currency != tx.currency) problems += Problem.TransferCurrency
            if (to != null && account != null) {
                val crossCurrency = to.currency != account.currency
                when {
                    crossCurrency && tx.toAmountMinor == null -> problems += Problem.TransferAmountMissing
                    crossCurrency && (tx.toAmountMinor!! <= 0L || tx.toAmountMinor > Decimal.MAX_MINOR) -> problems += Problem.TransferAmountInvalid
                    !crossCurrency && tx.toAmountMinor != null -> problems += Problem.TransferAmountUnneeded
                }
            }
        } else {
            if (tx.toAccountId != null) problems += Problem.TransferTargetUnknown
            if (tx.toAmountMinor != null) problems += Problem.TransferAmountUnneeded
        }

        val categoryKind = tx.type.categoryKind
        if (tx.categoryId != null) {
            val category = refs.categories[tx.categoryId]
            when {
                categoryKind == null -> problems += Problem.CategoryNotAllowed
                category == null -> problems += Problem.CategoryUnknown
                category.kind != categoryKind -> problems += Problem.CategoryWrongKind
                category.archived && previous?.categoryId != tx.categoryId -> problems += Problem.CategoryArchived
            }
        }

        if (tx.refundOf != null) {
            if (tx.type != TxType.Refund) {
                problems += Problem.RefundLinkNotRefund
            } else {
                when (refs.typeOf(tx.refundOf)) {
                    null -> problems += Problem.RefundTargetUnknown
                    TxType.Expense -> Unit
                    else -> problems += Problem.RefundTargetNotExpense
                }
            }
        }

        if (tx.tagIds.any { it !in refs.tagIds }) problems += Problem.TagUnknown
        if (tx.date < EARLIEST || tx.date > LATEST) problems += Problem.DateOutOfRange
        if ((tx.payee?.length ?: 0) > MAX_TEXT || (tx.description?.length ?: 0) > MAX_TEXT || (tx.note?.length ?: 0) > MAX_NOTE) {
            problems += Problem.TextTooLong
        }
        return problems.distinct()
    }
}
