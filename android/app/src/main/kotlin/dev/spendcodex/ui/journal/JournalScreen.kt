package dev.spendcodex.ui.journal

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.ChoiceRow
import dev.spendcodex.coreui.DateRow
import dev.spendcodex.coreui.FeedNote
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.IconAction
import dev.spendcodex.coreui.InputBar
import dev.spendcodex.coreui.MoreIcon
import dev.spendcodex.coreui.NoteTone
import dev.spendcodex.coreui.PanelTitle
import dev.spendcodex.coreui.SearchIcon
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.Token
import dev.spendcodex.coreui.rememberGlow
import dev.spendcodex.coreui.tap
import dev.spendcodex.data.db.TypeTotal
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Transaction
import dev.spendcodex.data.domain.TxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The one main page: today and the balances on top, the journal in the middle (newest at the bottom, like the family's
 * diary), the line at the bottom with what it understood shown word by word above it.
 */
@Composable
fun JournalScreen(
    state: JournalUiState,
    vm: JournalViewModel,
    effects: Flow<JournalEffect>,
    onOpen: (String) -> Unit,
    onForm: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(effects) {
        effects.collect {
            when (it) {
                JournalEffect.Recorded -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    listState.animateScrollToItem(0)
                }
            }
        }
    }
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().imePadding()) {
        Header(state, vm::onToggleSummary, onSearch, onSettings, onAccount)
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded && state.rows.isEmpty()) {
                Empty(Modifier.align(Alignment.BottomStart))
            } else {
                Feed(state, listState, vm, onOpen)
            }
        }
        Panels(state, vm)
        InputBar(
            value = state.composer.text,
            onValueChange = vm::onText,
            onSend = vm::onSend,
            onForm = onForm,
            canSend = state.canSend,
            placeholder = Texts.INPUT_PLACEHOLDER,
            modifier = Modifier.navigationBarsPadding().testTag("input"),
        )
    }
}

@Composable
private fun Header(state: JournalUiState, onToggle: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit, onAccount: (String) -> Unit) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val line = todayLine(state.todayTotals)
    val glow = rememberGlow(line)
    Column(Modifier.fillMaxWidth().animateContentSize(tween(120))) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).tap(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp).testTag("summary")) {
                Text(Format.dayTitle(state.today, state.today), style = t.body.copy(fontFeatureSettings = "tnum"))
                Text(line, style = t.secondary.copy(fontFeatureSettings = "tnum", color = androidx.compose.ui.graphics.lerp(c.secondary, c.time, glow)),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            IconAction(onSearch, Texts.CD_SEARCH, Modifier.padding(top = 4.dp).testTag("open-search")) { SearchIcon(it) }
            IconAction(onSettings, Texts.CD_SETTINGS, Modifier.padding(top = 4.dp, end = 4.dp).testTag("open-settings")) { MoreIcon(it) }
        }
        if (state.composer.summaryOpen) {
            Column(Modifier.padding(bottom = 8.dp).testTag("balances")) {
                val active = state.names.active
                if (active.isEmpty()) Text(Texts.NO_ACCOUNTS, style = t.secondary, modifier = Modifier.padding(horizontal = 16.dp))
                active.forEach { a ->
                    val b = state.balances[a.id]
                    Row(
                        Modifier.fillMaxWidth().tap { onAccount(a.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = t.secondary.copy(color = c.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val notes = listOfNotNull(
                                b?.takeIf { !it.openingKnown }?.let { Texts.BALANCE_NO_OPENING },
                                b?.takeIf { !it.complete }?.let { Texts.balanceIncomplete(it.unconverted) },
                            )
                            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = t.caption.copy(color = if (b?.complete == false) c.error else c.tertiary))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            (if (b?.complete == false) "≈ " else "") + Format.money(b?.minor ?: 0, a.currency),
                            style = t.mono,
                        )
                    }
                }
            }
        }
    }
}

/** "−16,40 € · +3 000,00 €" under "Сегодня": spent and received today, per currency, never added across currencies. */
private fun todayLine(totals: List<TypeTotal>): String {
    val spent = totals.filter { it.type == TxType.Expense.code && it.total != 0L }.sortedBy { it.currency }.map { Format.money(-it.total, it.currency) }
    val earned = totals.filter { it.type == TxType.Income.code && it.total != 0L }.sortedBy { it.currency }.map { Format.money(it.total, it.currency, withSign = true) }
    if (spent.isEmpty() && earned.isEmpty()) return Texts.TODAY_NOTHING
    return (spent + earned).joinToString(" · ")
}

@Composable
private fun Empty(modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp).testTag("empty")) {
        Text(Texts.EMPTY_TITLE, style = AppTheme.type.body)
        Text(Texts.EMPTY_HINT, style = AppTheme.type.secondary.copy(color = AppTheme.colors.tertiary), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Feed(state: JournalUiState, listState: androidx.compose.foundation.lazy.LazyListState, vm: JournalViewModel, onOpen: (String) -> Unit) {
    val items = remember(state.rows) { feedItems(state.rows) }
    val showAccount = state.names.active.size > 1
    // reaching the oldest loaded rows asks for more
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= items.size - 8) vm.onNeedMore() }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("feed"), state = listState, reverseLayout = true) {
        items(items, key = { it.key }) { item ->
            val animate = Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(120), fadeOutSpec = null)
            when (item) {
                is FeedItem.Op -> OperationRow(item.row, showAccount, onClick = { onOpen(item.row.id) }, modifier = animate)
                is FeedItem.Day -> DateRow(Format.dayTitle(item.date, state.today), animate)
            }
        }
    }
}

@Composable
private fun Panels(state: JournalUiState, vm: JournalViewModel) {
    val c = state.composer
    state.notice?.let { notice ->
        LaunchedEffect(notice.id) {
            delay(6_000)
            vm.dismissNotice(notice.id)
        }
        Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("notice")) {
            Hairline()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice.text, style = AppTheme.type.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                if (notice.action != null) TextAction(notice.action, { vm.onNotice(notice) }, Modifier.testTag("notice-action"))
            }
        }
    }
    AiPanel(c.ai, c.busy, vm)
    if (c.picker == null && c.suggestions.isNotEmpty() && c.ai == AiUi.Idle) Suggestions(c.suggestions, state, vm)
    c.picker?.let { p -> state.preview.getOrNull(p.index)?.let { op -> PickerPanel(p, op, state, vm) } }
    if (state.preview.isNotEmpty() && c.ai == AiUi.Idle) Preview(state, vm)
    c.problem?.let { FeedNote(it, NoteTone.Error, Modifier.testTag("send-problem")) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Preview(state: JournalUiState, vm: JournalViewModel) {
    val t = AppTheme.type
    val many = state.preview.size > 1
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).heightIn(max = 260.dp).verticalScroll(rememberScrollState()).testTag("preview")) {
        Hairline()
        state.preview.forEach { op ->
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                if (op.description.isNotBlank() || many) {
                    Text(
                        (if (many) "${op.index + 1}. " else "") + op.description,
                        style = t.secondary.copy(color = AppTheme.colors.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    op.tokens.forEach { token ->
                        val kind = token.kind
                        Token(
                            token.text,
                            onClick = { if (kind != null) vm.onPick(Picker(op.index, kind)) },
                            weak = token.weak,
                            problem = token.problem,
                            active = kind != null && state.composer.picker == Picker(op.index, kind),
                            modifier = Modifier.testTag("token-${kind?.name?.lowercase() ?: "info"}"),
                        )
                    }
                }
                op.note?.let { Text("// $it", style = t.caption, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                op.notes.forEach { Text(it, style = t.caption, modifier = Modifier.padding(top = 4.dp)) }
                op.problems.forEach { Text(it, style = t.caption.copy(color = AppTheme.colors.error), modifier = Modifier.padding(top = 4.dp).testTag("preview-problem")) }
            }
        }
        if (state.willAskAi) {
            Text(Texts.SEND_TO_AI, style = t.caption, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("will-ask-ai"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerPanel(p: Picker, op: PreviewOp, state: JournalUiState, vm: JournalViewModel) {
    val d = op.draft
    val names = state.names
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).heightIn(max = 300.dp).verticalScroll(rememberScrollState()).testTag("picker")) {
        Hairline()
        when (p.kind) {
            PickerKind.Type -> TxType.entries.forEach { type ->
                ChoiceRow(Texts.typeTitle(type), d.type == type, { vm.onOverride(p.index) { it.copy(type = type) } })
            }
            PickerKind.Amount -> {
                val account = names.account(d.accountId ?: state.defaultAccountId)
                val current = d.currency ?: account?.currency
                val codes = (listOfNotNull(account?.currency) + names.active.map { it.currency } + Currencies.common).distinct()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
                    codes.forEach { code ->
                        val cur = Currencies.of(code) ?: return@forEach
                        Token(if (cur.symbol != null) "${cur.code} ${cur.symbol}" else cur.code, { vm.onOverride(p.index) { it.copy(currency = code) } }, active = code == current)
                    }
                }
            }
            PickerKind.Account, PickerKind.ToAccount -> names.active.forEach { a ->
                val selected = if (p.kind == PickerKind.Account) (d.accountId ?: state.defaultAccountId) == a.id else d.toAccountId == a.id
                ChoiceRow(a.name, selected, {
                    vm.onOverride(p.index) { if (p.kind == PickerKind.Account) it.copy(accountId = a.id) else it.copy(toAccountId = a.id) }
                }, caption = a.currency)
            }
            PickerKind.Category -> {
                val kind = d.type.categoryKind ?: CategoryKind.Expense
                ChoiceRow(Texts.NO_CATEGORY, d.categoryId == null, { vm.onOverride(p.index) { it.copy(category = CategoryChoice.None) } })
                val active = names.categories.filter { !it.archived && it.kind == kind }
                active.filter { it.parentId == null }.forEach { parent ->
                    ChoiceRow(parent.name, d.categoryId == parent.id, { vm.onOverride(p.index) { it.copy(category = CategoryChoice.Of(parent.id)) } })
                    active.filter { it.parentId == parent.id }.forEach { child ->
                        ChoiceRow("    " + child.name, d.categoryId == child.id, { vm.onOverride(p.index) { it.copy(category = CategoryChoice.Of(child.id)) } })
                    }
                }
            }
            PickerKind.Date -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
                val current = d.date ?: state.today
                (0L..13L).map { state.today.minusDays(it) }.forEach { day ->
                    Token(Format.shortDate(day, state.today), { vm.onOverride(p.index) { it.copy(date = day) } }, active = day == current)
                }
            }
        }
    }
}

@Composable
private fun Suggestions(list: List<Transaction>, state: JournalUiState, vm: JournalViewModel) {
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("suggestions")) {
        Hairline()
        Text(Texts.SUGGESTIONS, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        list.forEach { tx ->
            Row(Modifier.fillMaxWidth().tap { vm.onSuggestion(tx) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("suggestion"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tx.description.orEmpty(), style = AppTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val caption = listOfNotNull(state.names.categoryPath(tx.categoryId), state.names.account(tx.accountId)?.name).joinToString(" · ")
                    if (caption.isNotEmpty()) Text(caption, style = AppTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                Text(Format.money(tx.amountMinor, tx.currency), style = AppTheme.type.mono)
            }
        }
    }
}

@Composable
private fun AiPanel(ai: AiUi, busy: Boolean, vm: JournalViewModel) {
    when (ai) {
        AiUi.Idle -> Unit
        AiUi.Reading -> Column(Modifier.background(AppTheme.colors.background).testTag("ai-reading")) {
            Hairline()
            FeedNote(Texts.AI_READING, NoteTone.Info)
        }
        is AiUi.Failed -> Column(Modifier.background(AppTheme.colors.background).testTag("ai-failed")) {
            Hairline()
            FeedNote(ai.message, NoteTone.Error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextAction(Texts.AI_CLOSE, vm::onAiClose, color = AppTheme.colors.secondary)
                Spacer(Modifier.weight(1f))
                if (ai.retryable) TextAction(Texts.AI_RETRY, vm::onAiRetry)
            }
        }
        is AiUi.Result -> Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("ai-result")) {
            Hairline()
            PanelTitle(Texts.AI_RESULT)
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                ai.items.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = AppTheme.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            item.warnings.forEach { Text(it, style = AppTheme.type.caption.copy(color = AppTheme.colors.time)) }
                            item.problems.forEach { Text(it, style = AppTheme.type.caption.copy(color = AppTheme.colors.error)) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(item.amount, style = AppTheme.type.mono)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextAction(Texts.AI_DROP, vm::onAiClose, color = AppTheme.colors.secondary)
                Spacer(Modifier.weight(1f))
                TextAction(if (busy) "…" else Texts.AI_RECORD, vm::onAiRecord, Modifier.testTag("ai-record"), enabled = ai.items.all { it.problems.isEmpty() })
            }
        }
    }
}
