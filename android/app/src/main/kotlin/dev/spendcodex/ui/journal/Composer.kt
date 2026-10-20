package dev.spendcodex.ui.journal

import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftBuilder
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Problem
import dev.spendcodex.data.domain.Refs
import dev.spendcodex.data.domain.Tag
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.parse.CategorySource
import dev.spendcodex.data.parse.LineIssue
import dev.spendcodex.data.parse.LineResult
import java.time.LocalDate
import java.time.ZonedDateTime

/** What a tap on a word of the preview opens. */
enum class PickerKind { Type, Amount, Account, ToAccount, Category, Date }

data class Picker(val index: Int, val kind: PickerKind)

sealed interface CategoryChoice {
    data object None : CategoryChoice
    data class Of(val id: String) : CategoryChoice
}

/** What the person changed by tapping the preview; it beats what the line says, until the line is sent or cleared. */
data class LineOverride(
    val type: TxType? = null,
    val currency: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val category: CategoryChoice? = null,
    val date: LocalDate? = null,
)

data class TokenUi(val kind: PickerKind?, val text: String, val weak: Boolean = false, val problem: Boolean = false)

/** One operation of the line as it will be recorded: its words, what is doubtful, what is wrong. */
data class PreviewOp(
    val index: Int,
    val draft: Draft,
    val description: String,
    val tokens: List<TokenUi>,
    val notes: List<String>,
    val problems: List<String>,
    val note: String?,
    val ok: Boolean,
)

/** The journal's names, for building and showing drafts. */
data class Names(val accounts: List<Account>, val categories: List<Category>, val tags: List<Tag>) {
    val active: List<Account> get() = accounts.filter { !it.archived }
    fun account(id: String?) = accounts.firstOrNull { it.id == id }
    fun category(id: String?) = categories.firstOrNull { it.id == id }

    fun refs(): Refs = Refs(accounts.associateBy { it.id }, categories.associateBy { it.id }, tags.map { it.id }.toSet()) { null }

    /** "Кафе" or "Еда › Кафе". */
    fun categoryPath(id: String?): String? {
        val c = category(id) ?: return null
        val parent = c.parentId?.let { category(it) }
        return if (parent != null) "${parent.name} › ${c.name}" else c.name
    }
}

object Composer {
    /** The line's draft with what was tapped on top of it. */
    fun apply(result: LineResult, o: LineOverride?, id: String, names: Names, today: LocalDate): Draft {
        var d = result.draft.copy(id = id)
        if (o == null) return d
        o.type?.let { t ->
            d = d.copy(type = t)
            // a category of the other kind does not survive a change of type
            if (t.categoryKind == null || names.category(d.categoryId)?.kind != t.categoryKind) d = d.copy(categoryId = null)
            if (t != TxType.Transfer) d = d.copy(toAccountId = null, toAmount = "")
        }
        o.currency?.let { d = d.copy(currency = it) }
        o.accountId?.let { d = d.copy(accountId = it) }
        o.toAccountId?.let { d = d.copy(toAccountId = it) }
        when (val c = o.category) {
            null -> Unit
            CategoryChoice.None -> d = d.copy(categoryId = null)
            is CategoryChoice.Of -> d = d.copy(categoryId = c.id)
        }
        o.date?.let { date ->
            val time = d.time as? DraftTime.At ?: if (date == today) DraftTime.Now else DraftTime.Unknown
            d = d.copy(date = date, time = time)
        }
        return d
    }

    fun preview(
        index: Int,
        result: LineResult,
        draft: Draft,
        o: LineOverride?,
        names: Names,
        defaultAccountId: String?,
        now: ZonedDateTime,
    ): PreviewOp {
        val today = now.toLocalDate()
        val account = names.account(draft.accountId ?: defaultAccountId)
        val currency = (if (draft.type == TxType.Transfer || draft.type == TxType.Adjustment) null else draft.currency) ?: account?.currency
        val tokens = ArrayList<TokenUi>()
        tokens += TokenUi(PickerKind.Type, Texts.type(draft.type), weak = !result.typeWritten && o?.type == null)

        val c = Currencies.of(currency)
        val minor = c?.let { Decimal.parseHuman(draft.amount, it.exponent) }
        tokens += when {
            draft.amount.isBlank() -> TokenUi(PickerKind.Amount, "? ${currency?.let(Format::sign).orEmpty()}".trim(), problem = true)
            minor == null || c == null -> TokenUi(PickerKind.Amount, "${draft.amount} ${currency?.let(Format::sign).orEmpty()}".trim(), problem = true)
            else -> TokenUi(PickerKind.Amount, Format.money(minor, c.code, withSign = draft.type == TxType.Adjustment && minor > 0))
        }

        val accountWeak = (!result.accountWritten || result.accountLearned) && o?.accountId == null
        tokens += TokenUi(PickerKind.Account, account?.name ?: "?", weak = accountWeak, problem = account == null)
        if (draft.type == TxType.Transfer) {
            val to = names.account(draft.toAccountId)
            tokens += TokenUi(PickerKind.ToAccount, "→ " + (to?.name ?: "?"), problem = to == null)
            if (to != null && account != null && to.currency != account.currency) {
                val tc = Currencies.require(to.currency)
                val arrived = Decimal.parseHuman(draft.toAmount, tc.exponent)
                tokens += TokenUi(null, if (arrived != null) "→ " + Format.money(arrived, tc.code) else "→ ? ${tc.sign}", problem = arrived == null)
            }
        }
        if (draft.type.categoryKind != null) {
            val path = names.categoryPath(draft.categoryId)
            val weak = (result.categorySource != CategorySource.Written && o?.category == null) || path == null
            tokens += TokenUi(PickerKind.Category, path ?: Texts.NO_CATEGORY, weak = weak)
        }
        val date = draft.date ?: today
        val timeText = when (val t = draft.time) {
            is DraftTime.At -> " " + Format.time(t.time)
            DraftTime.Now -> if (date == today) " " + Format.time(now.toLocalTime()) else ""
            DraftTime.Unknown -> ""
        }
        tokens += TokenUi(PickerKind.Date, Format.shortDate(date, today) + timeText, weak = !result.dateWritten && o?.date == null)
        if (draft.tags.isNotEmpty()) tokens += TokenUi(null, draft.tags.joinToString(" ") { "#$it" })

        val built = DraftBuilder.build(draft, names.refs(), defaultAccountId, now)
        val notes = result.issues.filterNot { it == LineIssue.NoAmount || it == LineIssue.TransferTarget }.map(Texts::issue)
        val problems = (if (result.issues.contains(LineIssue.NoAmount)) listOf(Texts.issue(LineIssue.NoAmount)) else emptyList()) +
            built.problems.filterNot { it == Problem.AmountMissing && result.issues.contains(LineIssue.NoAmount) }.map(Texts::problem)
        return PreviewOp(
            index = index,
            draft = draft,
            description = draft.description,
            tokens = tokens,
            notes = notes,
            problems = problems.distinct(),
            note = draft.note.ifBlank { null },
            ok = built.tx != null,
        )
    }
}
