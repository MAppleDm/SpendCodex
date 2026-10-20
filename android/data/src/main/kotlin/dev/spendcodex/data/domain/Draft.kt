package dev.spendcodex.data.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

/** When an operation happened, as far as it is known. */
sealed interface DraftTime {
    /** Now: the minute it is recorded, when it is recorded for today. Another day then has no time. */
    data object Now : DraftTime

    data class At(val time: LocalTime) : DraftTime

    /** Not known, and not made up. */
    data object Unknown : DraftTime
}

/**
 * A record being written: what the quick line understood, what the form holds, what DeepSeek read. Amounts are the text
 * as typed, read only when the record is built, in the currency it ends up with. The [id] is made when the draft starts
 * and kept to the end, so sending the same draft twice stores one record.
 */
data class Draft(
    val id: String = UUID.randomUUID().toString(),
    val type: TxType = TxType.Expense,
    val amount: String = "",
    /** Null: the account's currency. */
    val currency: String? = null,
    /** Null: the default account. */
    val accountId: String? = null,
    val accountAmount: String = "",
    val toAccountId: String? = null,
    val toAmount: String = "",
    val categoryId: String? = null,
    val payee: String = "",
    val description: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
    /** Null: today. */
    val date: LocalDate? = null,
    val time: DraftTime = DraftTime.Now,
    val refundOf: String? = null,
    val origin: Origin = Origin.Form,
    val inputText: String? = null,
    /** Set when the draft edits a stored record. */
    val createdAt: Long? = null,
) {
    val isEdit: Boolean get() = createdAt != null

    companion object {
        /** The form of a stored record, for editing it. [currencyOf] gives an account's currency. */
        fun of(tx: Transaction, tagNames: List<String>, currencyOf: (String) -> String?, decimalSeparator: Char = '.'): Draft {
            fun text(minor: Long?, code: String?): String {
                if (minor == null || code == null) return ""
                val c = Currencies.of(code) ?: return ""
                return Decimal.format(minor, c.exponent).let { if (decimalSeparator == '.') it else it.replace('.', decimalSeparator) }
            }
            return Draft(
                id = tx.id, type = tx.type, amount = text(tx.amountMinor, tx.currency), currency = tx.currency, accountId = tx.accountId,
                accountAmount = text(tx.accountAmountMinor, currencyOf(tx.accountId)), toAccountId = tx.toAccountId,
                toAmount = text(tx.toAmountMinor, tx.toAccountId?.let(currencyOf)),
                categoryId = tx.categoryId, payee = tx.payee.orEmpty(), description = tx.description.orEmpty(), note = tx.note.orEmpty(),
                tags = tagNames, date = tx.date, time = tx.time?.let { DraftTime.At(it) } ?: DraftTime.Unknown, refundOf = tx.refundOf,
                origin = tx.origin, inputText = tx.inputText, createdAt = tx.createdAt,
            )
        }
    }
}

/** A built record, with the names of its tags (new ones are made when it is stored), or why it cannot be built. */
data class Built(val tx: Transaction?, val tagNames: List<String>, val problems: List<Problem>)

/**
 * Turns a draft into a record by the [Rules]. Pure: the clock, the zone and the journal's references come in.
 */
object DraftBuilder {
    fun build(
        draft: Draft,
        refs: Refs,
        defaultAccountId: String?,
        now: ZonedDateTime,
        previous: Transaction? = null,
        accountCurrencyOf: (String) -> String? = { refs.accounts[it]?.currency },
    ): Built {
        val problems = ArrayList<Problem>()
        val accountId = draft.accountId ?: defaultAccountId
        val account = accountId?.let { refs.accounts[it] }
        if (accountId == null) problems += Problem.AccountMissing else if (account == null) problems += Problem.AccountUnknown

        // a transfer and an adjustment are always in the account's own currency
        val currencyCode = when (draft.type) {
            TxType.Transfer, TxType.Adjustment -> account?.currency ?: draft.currency
            else -> draft.currency ?: account?.currency
        }
        if (draft.type == TxType.Transfer && draft.currency != null && account != null && draft.currency != account.currency) {
            problems += Problem.TransferCurrency
        }
        val currency = Currencies.of(currencyCode)
        if (currency == null && accountId != null) problems += Problem.CurrencyUnknown

        val amount: Long? = when {
            draft.amount.isBlank() -> { problems += Problem.AmountMissing; null }
            currency == null -> null
            else -> Decimal.parseHuman(draft.amount, currency.exponent).also { if (it == null) problems += Problem.AmountInvalid }
        }

        val accountAmount: Long? = if (draft.type != TxType.Transfer && draft.type != TxType.Adjustment && account != null &&
            currency != null && currency.code != account.currency && draft.accountAmount.isNotBlank()
        ) {
            val ac = Currencies.of(account.currency)
            ac?.let { Decimal.parseHuman(draft.accountAmount, it.exponent) }.also { if (it == null) problems += Problem.AccountAmountInvalid }
        } else null

        val toAccount = if (draft.type == TxType.Transfer) draft.toAccountId else null
        val toAmount: Long? = if (draft.type == TxType.Transfer && toAccount != null && account != null) {
            val toCurrency = accountCurrencyOf(toAccount)?.let { Currencies.of(it) }
            if (toCurrency != null && toCurrency.code != account.currency) {
                if (draft.toAmount.isBlank()) null
                else Decimal.parseHuman(draft.toAmount, toCurrency.exponent).also { if (it == null) problems += Problem.TransferAmountInvalid }
            } else null
        } else null

        val date = draft.date ?: now.toLocalDate()
        val time = when (val t = draft.time) {
            is DraftTime.At -> t.time.withSecond(0).withNano(0)
            DraftTime.Unknown -> null
            DraftTime.Now -> if (date == now.toLocalDate()) now.toLocalTime().withSecond(0).withNano(0) else null
        }
        val nowMs = now.toInstant().toEpochMilli()
        val tagNames = draft.tags.map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }.distinctBy { normalize(it) }

        if (amount == null || account == null || currency == null) return Built(null, tagNames, problems.distinct())

        val tx = Transaction(
            id = draft.id,
            type = draft.type,
            date = date,
            time = time,
            timeZone = previous?.takeIf { it.date == date && it.time == time }?.timeZone ?: now.zone.id,
            amountMinor = if (draft.type == TxType.Adjustment) amount else amount.let { if (it < 0) -it else it },
            currency = currency.code,
            accountId = account.id,
            accountAmountMinor = accountAmount,
            toAccountId = toAccount,
            toAmountMinor = toAmount,
            categoryId = if (draft.type.categoryKind == null) null else draft.categoryId,
            payee = draft.payee.trim().ifEmpty { null },
            description = draft.description.trim().ifEmpty { null },
            note = draft.note.trim().ifEmpty { null },
            // tags are checked by name here and turned into ids when stored
            tagIds = emptyList(),
            refundOf = if (draft.type == TxType.Refund) draft.refundOf else null,
            origin = draft.origin,
            inputText = draft.inputText?.trim()?.ifEmpty { null },
            createdAt = draft.createdAt ?: nowMs,
            updatedAt = nowMs,
        )
        problems += Rules.validate(tx, refs, previous)
        if (draft.type == TxType.Transfer && toAccount != null && toAmount == null) {
            // the rules ask for it only across currencies; say it at the right field
            val toCurrency = accountCurrencyOf(toAccount)
            if (toCurrency != null && toCurrency != account.currency && draft.toAmount.isBlank()) problems += Problem.TransferAmountMissing
        }
        val distinct = problems.distinct()
        return Built(if (distinct.isEmpty()) tx else null, tagNames, distinct)
    }
}
