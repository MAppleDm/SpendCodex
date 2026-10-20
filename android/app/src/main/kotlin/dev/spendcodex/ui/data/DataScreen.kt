package dev.spendcodex.ui.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.ChoiceRow
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.Hint
import dev.spendcodex.coreui.PageHeader
import dev.spendcodex.coreui.PanelTitle
import dev.spendcodex.coreui.SectionCaption
import dev.spendcodex.coreui.StatRow
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.data.backup.ExportKind
import dev.spendcodex.ui.search.Period

/** Everything about getting the data out and back in: the backup, the restore, the exports for analysis. */
@Composable
fun DataScreen(state: DataUiState, vm: DataViewModel, onBack: () -> Unit, onFormatDoc: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(vm) { onDispose { vm.onRestoreCancel() } }
    val u = state.ui
    // one "save as" per kind; the file is written once the system says where
    val savers = ExportKind.entries.associateWith { kind ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(kind.mime)) { uri -> vm.onSaveTo(kind, uri) }
    }
    val safetySaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { vm.onSaveSafety(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onRestorePicked(it) }

    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding()) {
        PageHeader(Texts.DATA, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            // the backup
            SectionCaption(Texts.BACKUP)
            Hint(Texts.BACKUP_HINT)
            Hint(Texts.lastBackup(state.lastBackupAt?.let { Format.moment(it) }), Modifier.testTag("last-backup"),
                color = if (state.lastBackupAt == null) AppTheme.colors.time else AppTheme.colors.secondary)
            StatRow(Texts.BACKUP_SAVE, "›", Modifier.testTag("backup-save"), onClick = { savers.getValue(ExportKind.Backup).launch(vm.fileName(ExportKind.Backup)) })
            StatRow(Texts.BACKUP_SHARE, "↗", Modifier.testTag("backup-share"), onClick = { vm.onShare(ExportKind.Backup) })
            StatRow(Texts.RESTORE, "›", Modifier.testTag("restore"), onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) })
            Restore(u.restore, vm)
            if (state.hasSafetyCopy) {
                StatRow(Texts.SAFETY_COPY, "›", Modifier.testTag("safety-copy"), onClick = { safetySaver.launch("spendcodex-before-restore.zip") })
                Hint(Texts.SAFETY_HINT)
            }
            u.message?.let { Hint(it, Modifier.testTag("data-message"), color = if (u.messageError) AppTheme.colors.error else AppTheme.colors.confirm) }
            Hairline(Modifier.padding(top = 12.dp))

            // exports for analysis
            SectionCaption(Texts.EXPORT)
            Hint(Texts.EXPORT_HINT)
            Hint(Texts.RECEIPTS_EXPORT_HINT)
            if (u.fromSearch != null) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Hint(Texts.FROM_SEARCH + (u.fromSearch.text.takeIf { it.isNotBlank() }?.let { ": «$it»" } ?: ""), Modifier.weight(1f))
                    TextAction(Texts.RESET, vm::onClearSearch, color = AppTheme.colors.secondary)
                }
            } else {
                SectionCaption(Texts.PERIOD)
                listOf(Period.All to Texts.ALL_TIME, Period.ThisMonth to Texts.THIS_MONTH, Period.LastMonth to Texts.LAST_MONTH,
                    Period.ThisYear to Texts.THIS_YEAR, Period.Last12 to Texts.LAST_12).forEach { (p, label) ->
                    ChoiceRow(label, u.period == p, { vm.onPeriod(p) }, Modifier.testTag("export-period-${p.name.lowercase()}"))
                }
                val chosen = u.scope.filter.accountIds
                StatRow(Texts.ACCOUNTS, if (chosen.isEmpty()) Texts.ANY else Texts.selected(chosen.size), Modifier.testTag("export-accounts"), onClick = vm::onToggleAccounts)
                if (u.accountsOpen) {
                    ChoiceRow(Texts.ANY, chosen.isEmpty(), vm::onAllAccounts)
                    state.accounts.forEach { a -> ChoiceRow(a.name, a.id in chosen, { vm.onAccount(a.id) }, caption = a.currency) }
                }
            }
            StatRow(Texts.NOTES, if (u.scope.includeNotes) Texts.INCLUDED else Texts.EXCLUDED, Modifier.testTag("export-notes"), onClick = { vm.onNotes(!u.scope.includeNotes) })
            state.preview?.let { p ->
                Text(
                    Texts.exportPreview(p.transactions, Format.range(p.first, p.last), p.accounts.joinToString(", ").ifEmpty { "—" }, p.notes),
                    style = AppTheme.type.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("export-preview"),
                )
            }
            listOf(ExportKind.Ai, ExportKind.Json, ExportKind.CsvPackage, ExportKind.TransactionsCsv).forEach { kind ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Texts.kindTitle(kind), style = AppTheme.type.body)
                        Text(Texts.kindHint(kind), style = AppTheme.type.caption)
                    }
                    TextAction(Texts.SAVE_FILE, { savers.getValue(kind).launch(vm.fileName(kind)) }, Modifier.testTag("export-save-${kind.name.lowercase()}"),
                        color = AppTheme.colors.secondary, enabled = !u.busy)
                    TextAction(Texts.SHARE_FILE, { vm.onShare(kind) }, Modifier.testTag("export-share-${kind.name.lowercase()}"),
                        color = AppTheme.colors.secondary, enabled = !u.busy)
                }
            }
            Hairline(Modifier.padding(top = 12.dp))
            StatRow(Texts.FORMAT_DOC, "›", Modifier.testTag("format-doc"), onClick = onFormatDoc)
            Hint(Texts.FORMAT_DOC_HINT)
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
fun Restore(restore: RestoreUi, vm: DataViewModel, onDone: () -> Unit = {}) {
    when (restore) {
        RestoreUi.Idle -> Unit
        RestoreUi.Reading -> Hint(Texts.RESTORE_READING, Modifier.testTag("restore-reading"))
        is RestoreUi.Failed -> Column(Modifier.testTag("restore-failed")) {
            Hint(Texts.RESTORE_FAILED, color = AppTheme.colors.error)
            restore.errors.take(6).forEach { Hint("• " + Texts.importError(it), color = AppTheme.colors.error) }
            Row(Modifier.padding(horizontal = 8.dp)) { TextAction(Texts.AI_CLOSE, vm::onRestoreCancel, color = AppTheme.colors.secondary) }
        }
        is RestoreUi.Ready -> Column(Modifier.fillMaxWidth().testTag("restore-preview")) {
            val p = restore.preview
            val s = p.snapshot
            PanelTitle(Texts.RESTORE)
            Hint(Texts.restoreSummary(
                date = runCatching { Format.moment(java.time.Instant.parse(p.exportedAt).toEpochMilli()) }.getOrDefault(p.exportedAt),
                version = p.appVersion, accounts = s.accounts.size, categories = s.categories.size, tags = s.tags.size, ops = s.transactions.size,
                range = Format.range(p.first, p.last),
            ))
            Hint(Texts.restoreReplaces(p.currentTransactions), color = AppTheme.colors.time)
            Hint(Texts.restorePhotos(s.receipts.size, p.currentReceipts))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextAction(Texts.CANCEL, vm::onRestoreCancel, color = AppTheme.colors.secondary, enabled = !restore.busy)
                Spacer(Modifier.weight(1f))
                TextAction(
                    when {
                        restore.busy -> "…"
                        restore.armed -> Texts.RESTORE_CONFIRM
                        else -> Texts.RESTORE_DO
                    },
                    { vm.onRestoreConfirm(onDone) }, Modifier.testTag("restore-confirm"), color = AppTheme.colors.error,
                )
            }
        }
    }
}

/** The format description, as it is inside every export. */
@Composable
fun FormatDocScreen(text: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding()) {
        PageHeader(Texts.FORMAT_DOC, onBack)
        val scroll = rememberScrollState()
        Text(
            text,
            style = AppTheme.type.caption.copy(color = AppTheme.colors.foreground, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            modifier = Modifier.weight(1f).verticalScroll(scroll).navigationBarsPadding().padding(16.dp).testTag("format-text"),
        )
    }
}
