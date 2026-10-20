package dev.spendcodex.ui.operation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.ChoiceRow
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.Hint
import dev.spendcodex.coreui.PageHeader
import dev.spendcodex.coreui.StatRow
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.Token
import dev.spendcodex.coreui.UnderlineField
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Problem
import dev.spendcodex.data.domain.TxType

/** A whole operation, every field of it: for a new one when the line is not enough, and for changing a recorded one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OperationScreen(state: OperationUiState, vm: OperationViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val f = state.form
    val d = f.draft
    val names = state.names
    val t = AppTheme.type
    val c = AppTheme.colors
    val live = remember(state) { vm.livePreview(state) }
    // what the person tried to save and was told, or what is wrong already (not the empty amount of a fresh form, nor a
    // missing account: the journal gets its one account when the record is stored)
    val shown = (f.problems.ifEmpty { live.filterNot { it == Problem.AmountMissing || it == Problem.AccountMissing } })
    fun problemsOf(field: Problem.Field) = shown.filter { it.field == field }
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(f.key, f.loading) {
        if (!f.loading && f.existing == null && d.amount.isBlank()) runCatching { amountFocus.requestFocus() }
    }

    Column(modifier.fillMaxSize().background(c.background).statusBarsPadding().imePadding()) {
        PageHeader(if (f.existing != null) Texts.OPERATION else if (d.type == TxType.Refund) Texts.typeTitle(TxType.Refund) else Texts.NEW_OPERATION, onBack) {
            TextAction(
                if (f.busy) "…" else if (f.existing == null) Texts.RECORD else Texts.SAVE, vm::onSave,
                Modifier.testTag("form-save"), enabled = !f.loading && !f.busy && !f.photoBusy,
            )
        }
        if (f.loading) return@Column
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            // New entries are expenses. Existing records keep their original type for lossless editing.
            if (d.type != TxType.Expense) StatRow(Texts.TYPES, Texts.typeTitle(d.type), enabled = false)

            // the amount and its currency
            val account = names.account(d.accountId ?: state.defaultAccountId)
            val currency = (if (d.type == TxType.Transfer || d.type == TxType.Adjustment) account?.currency else d.currency ?: account?.currency) ?: ""
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(
                    d.amount, { v -> vm.update { it.copy(amount = v.filter { ch -> ch.isDigit() || ch in ".,-  " }) } },
                    Modifier.weight(1f).testTag("form-amount"),
                    placeholder = if (d.type == TxType.Adjustment) "−0" else "0",
                    keyboardType = if (d.type == TxType.Adjustment) KeyboardType.Text else KeyboardType.Decimal,
                    imeAction = ImeAction.Next, mono = true, error = problemsOf(Problem.Field.Amount).isNotEmpty(),
                    focusRequester = amountFocus,
                )
                Spacer(Modifier.width(12.dp))
                Token(Format.sign(currency).ifEmpty { "?" } + if (currency.isNotEmpty() && Format.sign(currency) != currency) " $currency" else "",
                    { if (d.type != TxType.Transfer && d.type != TxType.Adjustment) vm.onOpen(FormField.Currency) },
                    active = f.open == FormField.Currency, modifier = Modifier.testTag("form-currency"))
            }
            Errors(problemsOf(Problem.Field.Amount) + problemsOf(Problem.Field.Currency))
            if (f.open == FormField.Currency) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
                    (listOfNotNull(currency.ifEmpty { null }) + Currencies.common + Currencies.all.map { it.code }).distinct().forEach { code ->
                        // what the unseen account moved in its own currency is not known for another currency
                        Token(code, { vm.update { it.copy(currency = code, accountAmount = if (code == currency) it.accountAmount else "") }; vm.onOpen(null) }, active = code == currency)
                    }
                }
            }

            // No payment method: the journal counts spending, not what is left on a card or in cash, so a record has no
            // account to choose (it is booked to the journal's one, unseen). Only an old transfer or adjustment, from before
            // 0.3, is about accounts by nature and still says which.
            Hairline(Modifier.padding(top = 8.dp))
            if (d.type != TxType.Transfer && d.type != TxType.Adjustment) {
                Errors(problemsOf(Problem.Field.Account) + problemsOf(Problem.Field.AccountAmount))
            } else {
                StatRow(Texts.ACCOUNT, account?.name ?: "?", Modifier.testTag("form-account"), onClick = { vm.onOpen(FormField.Account) })
                if (f.open == FormField.Account) AccountChoices(names, d.accountId ?: state.defaultAccountId) { id -> vm.update { it.copy(accountId = id) }; vm.onOpen(null) }
                Errors(problemsOf(Problem.Field.Account))
            }
            if (d.type == TxType.Transfer) {
                val to = names.account(d.toAccountId)
                StatRow(Texts.TO_ACCOUNT, to?.name ?: "?", Modifier.testTag("form-to-account"), onClick = { vm.onOpen(FormField.ToAccount) })
                if (f.open == FormField.ToAccount) AccountChoices(names, d.toAccountId) { id -> vm.update { it.copy(toAccountId = id) }; vm.onOpen(null) }
                Errors(problemsOf(Problem.Field.ToAccount))
                if (to != null && account != null && to.currency != account.currency) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        UnderlineField(d.toAmount, { v -> vm.update { it.copy(toAmount = v) } }, Modifier.testTag("form-to-amount"),
                            placeholder = Texts.toAmount(to.currency), keyboardType = KeyboardType.Decimal, mono = true,
                            error = problemsOf(Problem.Field.ToAmount).isNotEmpty())
                    }
                    Errors(problemsOf(Problem.Field.ToAmount))
                }
            }

            // the category
            val kind = d.type.categoryKind
            if (kind != null) {
                StatRow(Texts.CATEGORY, names.categoryPath(d.categoryId) ?: Texts.NO_CATEGORY, Modifier.testTag("form-category"),
                    valueColor = if (d.categoryId == null) c.tertiary else c.foreground, onClick = { vm.onOpen(FormField.Category) })
                if (f.open == FormField.Category) CategoryChoices(names, kind, d.categoryId) { id -> vm.update { it.copy(categoryId = id) }; vm.onOpen(null) }
                Errors(problemsOf(Problem.Field.Category))
            }

            // when
            val date = d.date ?: state.today
            StatRow(Texts.DATE, Format.shortDate(date, state.today), Modifier.testTag("form-date"), onClick = { vm.onOpen(FormField.Date) })
            if (f.open == FormField.Date) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    (0L..13L).map { state.today.minusDays(it) }.forEach { day -> Token(Format.shortDate(day, state.today), { vm.onDate(day) }, active = day == date) }
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    UnderlineField(f.dateText, vm::onDateText, placeholder = "${Texts.OTHER_DATE}: ${Texts.DATE_HINT}", keyboardType = KeyboardType.Number,
                        mono = true, onImeAction = { vm.onOpen(null) })
                }
            }
            Errors(problemsOf(Problem.Field.Date))
            val timeValue = when (val tm = d.time) {
                is DraftTime.At -> Format.time(tm.time)
                DraftTime.Now -> if (date == state.today) Texts.TIME_NOW else Texts.TIME_UNKNOWN
                DraftTime.Unknown -> Texts.TIME_UNKNOWN
            }
            StatRow(Texts.TIME, timeValue, Modifier.testTag("form-time"), onClick = { vm.onOpen(FormField.Time) })
            if (f.open == FormField.Time) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    UnderlineField(f.timeText, vm::onTimeText, Modifier.width(96.dp), placeholder = Texts.TIME_HINT, keyboardType = KeyboardType.Number,
                        mono = true, onImeAction = { vm.onOpen(null) })
                    Spacer(Modifier.weight(1f))
                    TextAction(Texts.TIME_NOW, { vm.onTime(DraftTime.Now) })
                    TextAction(Texts.TIME_UNKNOWN, { vm.onTime(DraftTime.Unknown) }, color = c.secondary)
                }
            }

            // a refund's purchase
            if (d.type == TxType.Refund) {
                StatRow(Texts.REFUND_OF, f.refundTarget?.let { tx -> (tx.description ?: tx.payee ?: names.categoryPath(tx.categoryId) ?: "—") + " · " + Format.shortDate(tx.date, state.today) } ?: Texts.REFUND_NONE,
                    Modifier.testTag("form-refund-of"), valueColor = if (f.refundTarget == null) c.tertiary else c.foreground, onClick = { vm.onOpen(FormField.RefundOf) })
                if (f.open == FormField.RefundOf) {
                    ChoiceRow(Texts.REFUND_NONE, d.refundOf == null, { vm.onRefundOf(null) })
                    f.recentExpenses.forEach { tx ->
                        ChoiceRow(tx.description ?: tx.payee ?: names.categoryPath(tx.categoryId) ?: Texts.typeTitle(tx.type), d.refundOf == tx.id, { vm.onRefundOf(tx) },
                            caption = Format.shortDate(tx.date, state.today) + " · " + Format.money(-tx.amountMinor, tx.currency))
                    }
                }
                Errors(problemsOf(Problem.Field.RefundOf))
            }

            // words
            Hairline(Modifier.padding(top = 8.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                UnderlineField(d.description, { v -> vm.update { it.copy(description = v) } }, Modifier.testTag("form-description"),
                    placeholder = Texts.DESCRIPTION, imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences)
                UnderlineField(d.payee, { v -> vm.update { it.copy(payee = v) } }, Modifier.testTag("form-payee"),
                    placeholder = Texts.PAYEE, imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences)
                UnderlineField(f.tagsText, vm::onTags, Modifier.testTag("form-tags"), placeholder = "${Texts.TAGS}: ${Texts.TAGS_HINT}", imeAction = ImeAction.Next)
                val used = names.tags.filter { !it.archived && it.name !in d.tags }.take(12)
                if (used.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        used.forEach { tag -> Token("#${tag.name}", { vm.onTags((f.tagsText + " " + tag.name).trim()) }, weak = true) }
                    }
                }
                UnderlineField(d.note, { v -> vm.update { it.copy(note = v) } }, Modifier.testTag("form-note"), placeholder = Texts.NOTE,
                    singleLine = false, imeAction = ImeAction.Default, capitalization = KeyboardCapitalization.Sentences)
            }
            Errors(problemsOf(Problem.Field.Tags) + problemsOf(Problem.Field.Note))
            ReceiptsBlock(f, vm)

            // a stored record: where it came from and what else can be done with it
            f.existing?.let { tx ->
                Hairline(Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    if (tx.type == TxType.Expense) TextAction(Texts.REPEAT, vm::onRepeat, Modifier.testTag("form-repeat"))
                    if (tx.type == TxType.Expense) TextAction(Texts.MAKE_REFUND, vm::onMakeRefund, Modifier.testTag("form-refund"))
                    Spacer(Modifier.weight(1f))
                    TextAction(Texts.DELETE, vm::onDelete, Modifier.testTag("form-delete"), color = c.error)
                }
                val origin = Texts.origin(tx.origin.code, tx.inputText)
                Text(
                    Texts.created(Format.moment(tx.createdAt), origin) + if (tx.updatedAt != tx.createdAt) " · " + Texts.updated(Format.moment(tx.updatedAt)) else "",
                    style = t.caption.copy(color = c.tertiary, fontSize = 12.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun Errors(problems: List<Problem>) {
    problems.distinct().forEach {
        Text(Texts.problem(it), style = AppTheme.type.caption.copy(color = AppTheme.colors.error),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("form-problem"))
    }
}

@Composable
private fun AccountChoices(names: dev.spendcodex.ui.journal.Names, selected: String?, onPick: (String) -> Unit) {
    names.active.forEach { a -> ChoiceRow(a.name, a.id == selected, { onPick(a.id) }, caption = "${Texts.accountKind(a.kind)} · ${a.currency}") }
}

@Composable
private fun CategoryChoices(names: dev.spendcodex.ui.journal.Names, kind: CategoryKind, selected: String?, onPick: (String?) -> Unit) {
    ChoiceRow(Texts.NO_CATEGORY, selected == null, { onPick(null) })
    val active = names.categories.filter { (!it.archived || it.id == selected) && it.kind == kind }
    active.filter { it.parentId == null }.forEach { parent ->
        ChoiceRow(parent.name, parent.id == selected, { onPick(parent.id) })
        active.filter { it.parentId == parent.id }.forEach { child -> ChoiceRow("    " + child.name, child.id == selected, { onPick(child.id) }) }
    }
}
