package dev.spendcodex.data.domain

/** The records of one account, one type and one currency, summed by the database (the side money leaves or enters first). */
data class SideSum(
    val accountId: String,
    val type: TxType,
    val currency: String,
    /** Σ amount_minor. */
    val amount: Long,
    /** Σ account_amount_minor over the records that have it. */
    val accountAmount: Long,
    val count: Int,
    /** Records in another currency whose movement in the account's currency is not known. */
    val missingAccountAmount: Int,
)

/** Transfers arriving at an account: Σ of what arrived (to_amount, or the amount itself when the currencies match). */
data class ArrivalSum(val accountId: String, val arrived: Long, val count: Int)

/**
 * An account's figure. [unconverted] operations were made in another currency without the account's own amount: they are left
 * out, so the figure is incomplete and says so; it is never filled in with a guessed rate. Without an opening balance
 * ([openingKnown] false) the figure is only the movement since the journal started.
 */
data class Balance(
    val accountId: String,
    val currency: String,
    val minor: Long,
    val openingKnown: Boolean,
    val unconverted: Int,
) {
    val complete: Boolean get() = unconverted == 0
}

/**
 * The one definition of a balance:
 *
 *   balance = opening
 *           + income + refund + adjustment (signed)          (in the account's currency)
 *           − expense − transfers out
 *           + transfers in (what arrived)
 *
 * An operation in another currency counts with its account_amount; without it, it is counted in [Balance.unconverted].
 */
object Balances {
    fun compute(accounts: List<Account>, sides: List<SideSum>, arrivals: List<ArrivalSum>): Map<String, Balance> {
        val bySide = sides.groupBy { it.accountId }
        val byArrival = arrivals.groupBy { it.accountId }
        return accounts.associate { account ->
            var total = account.openingMinor ?: 0L
            var unconverted = 0
            for (s in bySide[account.id].orEmpty()) {
                val sameCurrency = s.currency == account.currency
                val moved = if (sameCurrency) s.amount else s.accountAmount
                if (!sameCurrency) unconverted += s.missingAccountAmount
                total = when (s.type) {
                    TxType.Income, TxType.Refund -> Math.addExact(total, moved)
                    TxType.Expense -> Math.subtractExact(total, moved)
                    // a transfer always leaves in the account's own currency, an adjustment is in it too (the rules check both);
                    // a record that breaks that cannot be counted honestly
                    TxType.Transfer -> if (sameCurrency) Math.subtractExact(total, s.amount) else { unconverted += s.count - s.missingAccountAmount; total }
                    TxType.Adjustment -> if (sameCurrency) Math.addExact(total, s.amount) else { unconverted += s.count - s.missingAccountAmount; total }
                }
            }
            for (a in byArrival[account.id].orEmpty()) total = Math.addExact(total, a.arrived)
            account.id to Balance(account.id, account.currency, total, account.openingMinor != null, unconverted)
        }
    }
}
