package dev.spendcodex.ui.journal

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.spendcodex.coreui.DateRow
import dev.spendcodex.coreui.FeedNote
import dev.spendcodex.coreui.FormIcon
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.IconAction
import dev.spendcodex.coreui.InputBar
import dev.spendcodex.coreui.MoreIcon
import dev.spendcodex.coreui.NoteTone
import dev.spendcodex.coreui.PanelTitle
import dev.spendcodex.coreui.SearchIcon
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.rememberGlow
import dev.spendcodex.data.db.TypeTotal
import dev.spendcodex.data.domain.TxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Expenses, a daily total, and one text input. Manual entry lives beside search in the header. */
@Composable
fun JournalScreen(
    state: JournalUiState,
    vm: JournalViewModel,
    effects: Flow<JournalEffect>,
    onOpen: (String) -> Unit,
    onForm: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(effects) {
        effects.collect {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            listState.animateScrollToItem(0)
        }
    }
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().imePadding()) {
        Header(state, onForm, onSearch, onSettings)
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded && state.rows.isEmpty()) {
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(24.dp).testTag("empty")) {
                    Text(Texts.EMPTY_TITLE, style = AppTheme.type.body)
                    Text(Texts.EMPTY_HINT, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                val items = remember(state.rows) { feedItems(state.rows) }
                LaunchedEffect(listState, items.size) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged().collect { if (it >= items.size - 8) vm.onNeedMore() }
                }
                LazyColumn(Modifier.fillMaxSize().testTag("feed"), state = listState, reverseLayout = true) {
                    items(items, key = { it.key }) { item ->
                        val animate = Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(120), fadeOutSpec = null)
                        when (item) {
                            is FeedItem.Op -> OperationRow(item.row, onClick = { onOpen(item.row.id) }, modifier = animate)
                            is FeedItem.Day -> DateRow(Format.dayTitle(item.date, state.today), animate)
                        }
                    }
                }
            }
        }
        state.notice?.let { notice ->
            LaunchedEffect(notice.id) { delay(6_000); vm.dismissNotice(notice.id) }
            Hairline()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).testTag("notice"), verticalAlignment = Alignment.CenterVertically) {
                Text(notice.text, style = AppTheme.type.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                if (notice.action != null) TextAction(notice.action, { vm.onNotice(notice) }, Modifier.testTag("notice-action"))
            }
        }
        AiPanel(state.composer, vm, onForm, onAi)
        if (!state.hasKey && state.composer.ai == AiUi.Idle) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Texts.AI_CONNECT_HINT, style = AppTheme.type.caption, modifier = Modifier.weight(1f))
                TextAction(Texts.AI_CONNECT, onAi, Modifier.testTag("connect-ai"))
            }
        }
        InputBar(
            value = state.composer.text, onValueChange = vm::onText, onSend = vm::onSend,
            canSend = state.canSend, placeholder = Texts.INPUT_PLACEHOLDER,
            readOnly = state.composer.saving, sendDescription = Texts.AI_SEND,
            modifier = Modifier.navigationBarsPadding().testTag("input"),
        )
    }
}

@Composable
private fun Header(state: JournalUiState, onForm: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val c = AppTheme.colors
    val line = todayLine(state.todayTotals)
    val glow = rememberGlow(line)
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp).testTag("summary")) {
            Text(Format.dayTitle(state.today, state.today), style = AppTheme.type.body.copy(fontFeatureSettings = "tnum"))
            Text(line, style = AppTheme.type.secondary.copy(fontFeatureSettings = "tnum", color = androidx.compose.ui.graphics.lerp(c.secondary, c.time, glow)),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        IconAction(onForm, Texts.MANUAL_ENTRY, Modifier.padding(top = 4.dp).testTag("open-form"), enabled = !state.composer.saving) { FormIcon(it) }
        IconAction(onSearch, Texts.CD_SEARCH, Modifier.padding(top = 4.dp).testTag("open-search")) { SearchIcon(it) }
        IconAction(onSettings, Texts.CD_SETTINGS, Modifier.padding(top = 4.dp, end = 4.dp).testTag("open-settings")) { MoreIcon(it) }
    }
}

private fun todayLine(totals: List<TypeTotal>): String {
    val spent = totals.filter { it.type == TxType.Expense.code && it.total != 0L }.sortedBy { it.currency }.map { Format.money(-it.total, it.currency) }
    val refunded = totals.filter { it.type == TxType.Refund.code && it.total != 0L }.sortedBy { it.currency }.map { "${Texts.REFUNDED} ${Format.money(it.total, it.currency, withSign = true)}" }
    return (spent + refunded).takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: Texts.TODAY_NOTHING
}

@Composable
private fun AiPanel(c: ComposerState, vm: JournalViewModel, onForm: () -> Unit, onAi: () -> Unit) {
    when (val ai = c.ai) {
        AiUi.Idle -> Unit
        AiUi.Reading -> Column(Modifier.fillMaxWidth().testTag("ai-reading")) {
            Hairline()
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FeedNote(if (c.saving) Texts.AI_SAVING else Texts.AI_READING, NoteTone.Info, Modifier.weight(1f))
                if (!c.saving) TextAction(Texts.CANCEL, vm::onAiClose)
            }
        }
        is AiUi.Failed -> Column(Modifier.fillMaxWidth().testTag("ai-failed")) {
            Hairline()
            FeedNote(ai.message, NoteTone.Error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextAction(Texts.MANUAL_ENTRY, onForm, Modifier.testTag("ai-manual"), color = AppTheme.colors.secondary)
                Spacer(Modifier.weight(1f))
                if (ai.needsKey) TextAction(Texts.AI_CONNECT, onAi)
                else if (ai.retryable) TextAction(Texts.AI_RETRY, vm::onAiRetry, Modifier.testTag("ai-retry"))
                else TextAction(Texts.AI_CLOSE, vm::onAiClose)
            }
        }
        is AiUi.Result -> Column(Modifier.fillMaxWidth().testTag("ai-result")) {
            Hairline()
            PanelTitle(Texts.AI_RESULT)
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                ai.items.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = AppTheme.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (item.draft.tags.isNotEmpty()) Text(item.draft.tags.joinToString(" ") { "#$it" }, style = AppTheme.type.caption)
                            item.warnings.forEach { Text(it, style = AppTheme.type.caption.copy(color = AppTheme.colors.time)) }
                            item.problems.forEach { Text(it, style = AppTheme.type.caption.copy(color = AppTheme.colors.error)) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(item.amount, style = AppTheme.type.mono)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextAction(Texts.AI_DROP, vm::onAiClose, color = AppTheme.colors.secondary, enabled = !c.busy)
                if (ai.items.size == 1) TextAction(Texts.EDIT, onForm, Modifier.testTag("ai-edit"), enabled = !c.busy)
                Spacer(Modifier.weight(1f))
                TextAction(if (c.busy) "…" else Texts.AI_RECORD, vm::onAiRecord, Modifier.testTag("ai-record"),
                    enabled = !c.busy && ai.items.all { it.problems.isEmpty() })
            }
        }
    }
}
