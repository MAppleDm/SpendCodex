package dev.spendcodex.ui.operation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.ChevronIcon
import dev.spendcodex.coreui.ChoiceRow
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

/** The editable card for an existing operation or a draft from DeepSeek. */
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
    val detailsHaveErrors = shown.any {
        when (it.field) {
            Problem.Field.Amount, Problem.Field.Currency, Problem.Field.Note, Problem.Field.Tags -> false
            else -> true
        }
    }
    var showDetails by rememberSaveable(f.key) { mutableStateOf(false) }
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
            val account = names.account(d.accountId ?: state.defaultAccountId)
            val currency = (if (d.type == TxType.Transfer || d.type == TxType.Adjustment) account?.currency else d.currency ?: account?.currency) ?: ""
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnderlineField(
                    d.amount, { v -> vm.update { it.copy(amount = v.filter { ch -> ch.isDigit() || ch in ".,-  " }) } },
                    Modifier.weight(1f).testTag("form-amount"),
                    placeholder = if (d.type == TxType.Adjustment) "−0" else "0",
                    keyboardType = if (d.type == TxType.Adjustment) KeyboardType.Text else KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                    focusRequester = amountFocus,
                    textStyle = t.mono.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium),
                    underline = false,
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

            UnderlineField(
                d.description, { value -> vm.update { it.copy(description = value) } },
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp).testTag("form-description"),
                placeholder = Texts.DESCRIPTION, imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences,
                textStyle = t.body.copy(fontSize = 19.sp, lineHeight = 26.sp), underline = false,
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(22.dp)).background(lerp(c.background, c.pressed, 0.65f))
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            ) {
                Text(Texts.COMMENT, style = t.body.copy(fontWeight = FontWeight.Medium))
                UnderlineField(
                    d.note, { value -> vm.update { it.copy(note = value) } },
                    Modifier.fillMaxWidth().testTag("form-note"), placeholder = Texts.COMMENT_HINT,
                    singleLine = false, imeAction = ImeAction.Default, capitalization = KeyboardCapitalization.Sentences,
                    minLines = 3, underline = false,
                )
            }
            Errors(problemsOf(Problem.Field.Note))
            ReceiptsBlock(f, vm)

            val detailsOpen = showDetails || detailsHaveErrors
            DisclosureRow(
                Texts.OPERATION_DETAILS, Format.shortDate(d.date ?: state.today, state.today), detailsOpen,
                onClick = { showDetails = !detailsOpen }, enabled = !detailsHaveErrors,
                modifier = Modifier.testTag("form-details"),
            )
            if (detailsOpen) OperationDetails(state, vm, shown)

            val tagsErrors = problemsOf(Problem.Field.Tags)
            val tagsOpen = f.open == FormField.Tags || tagsErrors.isNotEmpty()
            DisclosureRow(
                Texts.TAGS, d.tags.joinToString(" ") { "#$it" }.ifBlank { "—" }, tagsOpen,
                onClick = { vm.onOpen(FormField.Tags) }, enabled = tagsErrors.isEmpty(),
                modifier = Modifier.testTag("form-tags-toggle"),
            )
            if (tagsOpen) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    UnderlineField(
                        f.tagsText, vm::onTags, Modifier.fillMaxWidth().testTag("form-tags"),
                        placeholder = Texts.TAGS_HINT, imeAction = ImeAction.Done, onImeAction = { vm.onOpen(null) },
                    )
                    val suggested = names.tags.filter { !it.archived && it.name !in d.tags }.take(12)
                    if (suggested.isNotEmpty()) {
                        FlowRow(
                            Modifier.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            suggested.forEach { tag ->
                                Token("#${tag.name}", { vm.onTags((f.tagsText + " " + tag.name).trim()) }, weak = true)
                            }
                        }
                    }
                }
                Errors(tagsErrors)
            }

            f.existing?.let { tx ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val origin = Texts.origin(tx.origin.code, tx.inputText)
                    val updated = if (tx.updatedAt != tx.createdAt) " · " + Texts.updated(Format.moment(tx.updatedAt)) else ""
                    Text(
                        Texts.created(Format.moment(tx.createdAt), origin) + updated,
                        style = t.caption, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    TextAction(Texts.DELETE, vm::onDelete, Modifier.testTag("form-delete"), color = c.error, enabled = !f.busy && !f.photoBusy)
                }
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OperationDetails(state: OperationUiState, vm: OperationViewModel, problems: List<Problem>) {
    val f = state.form
    val d = f.draft
    val names = state.names
    val c = AppTheme.colors
    val account = names.account(d.accountId ?: state.defaultAccountId)
    fun problemsOf(field: Problem.Field) = problems.filter { it.field == field }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))
            .background(lerp(c.background, c.pressed, 0.45f)),
    ) {
        if (d.type != TxType.Expense) StatRow(Texts.TYPES, Texts.typeTitle(d.type), enabled = false)
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
                        caption = Format.shortDate(tx.date, state.today) + " · " + Format.money(tx.amountMinor, tx.currency))
                }
            }
            Errors(problemsOf(Problem.Field.RefundOf))
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            UnderlineField(d.payee, { value -> vm.update { it.copy(payee = value) } }, Modifier.testTag("form-payee"),
                placeholder = Texts.PAYEE, imeAction = ImeAction.Done, underline = false)
        }
    }
}

@Composable
private fun DisclosureRow(
    label: String,
    value: String,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = AppTheme.type
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = t.secondary)
        Text(
            value, style = t.caption, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
        )
        ChevronIcon(AppTheme.colors.secondary, up = open)
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
