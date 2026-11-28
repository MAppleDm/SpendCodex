package dev.spendcodex.ui.journal

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.CameraIcon
import dev.spendcodex.coreui.FeedNote
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.IconAction
import dev.spendcodex.coreui.InputBar
import dev.spendcodex.coreui.MoreIcon
import dev.spendcodex.coreui.NoteTone
import dev.spendcodex.coreui.PanelTitle
import dev.spendcodex.coreui.SearchIcon
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.rememberGlow
import dev.spendcodex.data.ai.AiReader
import dev.spendcodex.data.db.TypeTotal
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.receipts.ReceiptImages
import dev.spendcodex.data.receipts.ReceiptPhoto
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Expenses, a daily total, and one text input with the receipt camera before it. A swipe right deletes a record.
 */
@Composable
fun JournalScreen(
    state: JournalUiState,
    vm: JournalViewModel,
    onOpen: (String) -> Unit,
    onEditDraft: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    var choosing by remember { mutableStateOf(false) }
    var calendarAnchor by remember { mutableStateOf<LocalDate?>(null) }
    var jumpDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var handledScrollRequest by rememberSaveable { mutableIntStateOf(0) }
    val feedItems = remember(state.rows, selectedDate) { feedItems(state.rows, selectedDate = selectedDate) }
    val latestItems by rememberUpdatedState(feedItems)
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AiReader.MAX_PHOTOS)) { vm.onPhotos(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), vm::onCameraResult)
    val canPhoto = !state.composer.busy && !state.composer.saving && state.composer.ai !is AiUi.Result
    fun openCalendar(date: LocalDate) {
        focus.clearFocus()
        calendarAnchor = date
    }

    calendarAnchor?.let { anchor ->
        val oldest = state.days.lastOrNull()?.let { LocalDate.parse(it.date) } ?: state.today
        val newest = state.days.firstOrNull()?.let { LocalDate.parse(it.date) } ?: state.today
        JournalCalendar(anchor, minOf(oldest, state.today, anchor).year, maxOf(newest, state.today, anchor),
            onDismiss = { calendarAnchor = null },
            onSelect = { date ->
                calendarAnchor = null
                jumpDate = date
            },
        )
    }
    LaunchedEffect(jumpDate) {
        val date = jumpDate ?: return@LaunchedEffect
        vm.loadThrough(date)
        selectedDate = date
        val index = snapshotFlow { latestItems.indexOfFirst { it is FeedItem.Day && it.date == date } }.first { it >= 0 }
        listState.scrollToItem(index)
        val layout = listState.layoutInfo
        val heading = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (heading != null) {
            // In a reversed list the heading would land at the bottom, with its entries below the viewport.
            listState.scrollToItem(index, -(layout.viewportSize.height - heading.size).coerceAtLeast(0))
        }
        jumpDate = null
    }
    LaunchedEffect(state.loaded, state.scrollRequest) {
        if (!state.loaded || handledScrollRequest == state.scrollRequest) return@LaunchedEffect
        handledScrollRequest = state.scrollRequest
        if (state.scrollRequest == 0) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        jumpDate = null
        selectedDate = null
        // Ask for the absolute bottom after the refreshed items reach the list, overriding its saved item anchor.
        listState.requestScrollToItem(0)
    }
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().imePadding()) {
        Header(state, { openCalendar(state.today) }, onSearch, onSettings)
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded && feedItems.isEmpty()) {
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(24.dp).testTag("empty")) {
                    Text(Texts.EMPTY_TITLE, style = AppTheme.type.body)
                    Text(Texts.EMPTY_HINT, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                LaunchedEffect(listState, feedItems.size, state.hasMore) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged().collect { if (it >= feedItems.size - 8) vm.onNeedMore() }
                }
                LazyColumn(Modifier.fillMaxSize().testTag("feed"), state = listState, reverseLayout = true) {
                    items(feedItems, key = { it.key }) { item ->
                        val animate = Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(120), fadeOutSpec = null)
                        when (item) {
                            is FeedItem.Op -> OperationRow(item.row, onClick = { onOpen(item.row.id) }, modifier = animate, onDelete = { vm.onDelete(item.row.id) })
                            is FeedItem.Day -> DayDivider(item, state.today, { openCalendar(item.date) }, animate)
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
        AiPanel(state.composer, vm, onEditDraft, onAi)
        if (choosing && canPhoto) {
            Column(Modifier.fillMaxWidth().testTag("photo-choice")) {
                Hairline()
                Text(Texts.PHOTO_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextAction(Texts.RECEIPT_CAMERA, {
                        choosing = false
                        vm.cameraTarget()?.let { uri ->
                            try { camera.launch(uri) }
                            catch (_: ActivityNotFoundException) { vm.onCameraResult(false) }
                            catch (_: SecurityException) { vm.onCameraResult(false) }
                        }
                    }, Modifier.testTag("photo-camera"))
                    TextAction(Texts.RECEIPT_GALLERY, {
                        choosing = false
                        try { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        catch (_: ActivityNotFoundException) { }
                    }, Modifier.testTag("photo-gallery"))
                    Spacer(Modifier.weight(1f))
                    TextAction(Texts.CANCEL, { choosing = false }, color = AppTheme.colors.secondary)
                }
            }
        }
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
            leading = {
                IconAction({ choosing = !choosing }, Texts.PHOTO_READ, Modifier.testTag("open-photo"), active = choosing && canPhoto, enabled = canPhoto) { CameraIcon(it) }
            },
        )
    }
}

@Composable
private fun Header(state: JournalUiState, onCalendar: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val c = AppTheme.colors
    val line = todayLine(state.todayTotals)
    val glow = rememberGlow(line)
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).clickable(onClickLabel = Texts.JUMP_TO_DATE, role = Role.Button, onClick = onCalendar)
            .testTag("summary").padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Text(Format.dayTitle(state.today, state.today), style = AppTheme.type.body.copy(fontFeatureSettings = "tnum"))
            Text(line, style = AppTheme.type.secondary.copy(fontFeatureSettings = "tnum", color = androidx.compose.ui.graphics.lerp(c.secondary, c.time, glow)),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        IconAction(onSearch, Texts.CD_SEARCH, Modifier.padding(top = 4.dp).testTag("open-search")) { SearchIcon(it) }
        IconAction(onSettings, Texts.CD_SETTINGS, Modifier.padding(top = 4.dp, end = 4.dp).testTag("open-settings")) { MoreIcon(it) }
    }
}

private fun todayLine(totals: List<TypeTotal>): String {
    val spent = totals.filter { it.type == TxType.Expense.code && it.total != 0L }.sortedBy { it.currency }.map { Format.money(it.total, it.currency) }
    val refunded = totals.filter { it.type == TxType.Refund.code && it.total != 0L }.sortedBy { it.currency }.map { "${Texts.REFUNDED} ${Format.money(it.total, it.currency, withSign = true)}" }
    return (spent + refunded).takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: Texts.TODAY_NOTHING
}

@Composable
private fun AiPanel(c: ComposerState, vm: JournalViewModel, onEditDraft: () -> Unit, onAi: () -> Unit) {
    when (val ai = c.ai) {
        AiUi.Idle -> Unit
        AiUi.Reading -> Column(Modifier.fillMaxWidth().testTag("ai-reading")) {
            Hairline()
            val reading = when {
                c.saving -> Texts.AI_SAVING
                !c.fromPhotos -> Texts.AI_READING
                c.photos.isEmpty() -> Texts.RECEIPT_PROCESSING
                else -> Texts.PHOTO_READING
            }
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FeedNote(reading, NoteTone.Info, Modifier.weight(1f))
                if (!c.saving) TextAction(Texts.CANCEL, vm::onAiClose)
            }
            PhotoStrip(c.photos)
        }
        is AiUi.Failed -> Column(Modifier.fillMaxWidth().testTag("ai-failed")) {
            Hairline()
            FeedNote(ai.message, NoteTone.Error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Spacer(Modifier.weight(1f))
                if (ai.needsKey) TextAction(Texts.AI_CONNECT, onAi)
                else if (ai.retryable) TextAction(Texts.AI_RETRY, vm::onAiRetry, Modifier.testTag("ai-retry"))
                else TextAction(Texts.AI_CLOSE, vm::onAiClose)
            }
        }
        is AiUi.Result -> Column(Modifier.fillMaxWidth().testTag("ai-result")) {
            Hairline()
            PanelTitle(if (c.fromPhotos) Texts.PHOTO_RESULT else Texts.AI_RESULT)
            PhotoStrip(c.photos)
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                ai.notes.forEach { note ->
                    Text(note, style = AppTheme.type.caption.copy(color = AppTheme.colors.time),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("ai-note"))
                }
                ai.items.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("ai-item"), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = AppTheme.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            // a receipt's whole list is what is being checked
                            item.caption?.let { Text(it, style = AppTheme.type.caption, maxLines = if (c.fromPhotos) 12 else 2, overflow = TextOverflow.Ellipsis) }
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
                if (ai.items.size == 1) TextAction(Texts.EDIT, onEditDraft, Modifier.testTag("ai-edit"), enabled = !c.busy)
                Spacer(Modifier.weight(1f))
                TextAction(if (c.busy) "…" else Texts.AI_RECORD, vm::onAiRecord, Modifier.testTag("ai-record"),
                    enabled = !c.busy && ai.items.all { it.problems.isEmpty() })
            }
        }
    }
}

/** The receipt photos DeepSeek is reading, small, in the order they were chosen. */
@Composable
private fun PhotoStrip(photos: List<ReceiptPhoto>) {
    if (photos.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp).testTag("ai-photos"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        photos.forEach { photo ->
            val image by produceState<ImageBitmap?>(null, photo.receipt.id) {
                value = withContext(Dispatchers.IO) { runCatching { photo.bytes?.let { ReceiptImages.thumbnail(it, 160)?.asImageBitmap() } }.getOrNull() }
            }
            Box(Modifier.size(width = 40.dp, height = 52.dp).border(1.dp, AppTheme.colors.border)) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    }
}
