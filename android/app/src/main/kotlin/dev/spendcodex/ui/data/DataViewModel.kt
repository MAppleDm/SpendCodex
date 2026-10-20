package dev.spendcodex.ui.data

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spendcodex.Texts
import dev.spendcodex.data.backup.Backups
import dev.spendcodex.data.backup.ExportKind
import dev.spendcodex.data.backup.ExportPreview
import dev.spendcodex.data.backup.ExportScope
import dev.spendcodex.data.backup.ImportError
import dev.spendcodex.data.backup.ReadResult
import dev.spendcodex.data.backup.RestorePreview
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.settings.Settings
import dev.spendcodex.ui.Notice
import dev.spendcodex.ui.Notices
import dev.spendcodex.ui.search.Period
import dev.spendcodex.ui.shareFile
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RestoreUi {
    data object Idle : RestoreUi
    data object Reading : RestoreUi
    data class Ready(val preview: RestorePreview, val armed: Boolean = false, val busy: Boolean = false) : RestoreUi
    data class Failed(val errors: List<ImportError>) : RestoreUi
}

data class DataUi(
    val key: String? = null,
    val scope: ExportScope = ExportScope(),
    /** Set when the page was opened from a search: the export is that search. */
    val fromSearch: Filter? = null,
    val period: Period = Period.All,
    val busy: Boolean = false,
    val message: String? = null,
    val messageError: Boolean = false,
    val restore: RestoreUi = RestoreUi.Idle,
)

data class DataUiState(
    val ui: DataUi = DataUi(),
    val preview: ExportPreview? = null,
    val lastBackupAt: Long? = null,
    val hasSafetyCopy: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class DataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: Backups,
    private val ledger: Ledger,
    private val settings: Settings,
    private val notices: Notices,
    private val clock: Clock,
) : ViewModel() {
    private val ui = MutableStateFlow(DataUi())
    private val safety = MutableStateFlow(backups.hasSafetyCopy())
    private var readSequence = 0

    /** What the chosen export holds, recomputed a moment after the choice changes. */
    private val preview = combine(ui.map { it.scope }.distinctUntilChanged(), ledger.count, ledger.receiptCount) { s, _, _ -> s }
        .debounce(150)
        .mapLatest { runCatching { backups.preview(it) }.getOrNull() }

    val state: StateFlow<DataUiState> = combine(ui, preview, settings.lastBackupAt, safety) { u, p, last, s ->
        DataUiState(u, p, last, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataUiState())

    fun open(key: String, scope: ExportScope?) {
        if (ui.value.key == key) return
        onRestoreCancel()
        safety.value = backups.hasSafetyCopy()
        ui.value = DataUi(key = key, scope = scope ?: ExportScope(), fromSearch = scope?.filter?.takeIf { !it.isEmpty })
    }

    fun fileName(kind: ExportKind): String = backups.fileName(kind)

    // ---------- the selection for analysis ----------

    fun onPeriod(period: Period) {
        val today = LocalDate.now(clock)
        val (from, to) = when (period) {
            Period.All, Period.Custom -> null to null
            Period.ThisMonth -> today.withDayOfMonth(1) to null
            Period.LastMonth -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
            Period.ThisYear -> today.withDayOfYear(1) to null
            Period.Last12 -> today.minusMonths(12).plusDays(1) to null
        }
        ui.update { it.copy(period = period, fromSearch = null, scope = it.scope.copy(filter = it.scope.filter.copy(from = from, to = to)), message = null) }
    }

    fun onNotes(include: Boolean) = ui.update { it.copy(scope = it.scope.copy(includeNotes = include), message = null) }

    fun onClearSearch() = ui.update { it.copy(fromSearch = null, scope = ExportScope(includeNotes = it.scope.includeNotes), period = Period.All) }

    // ---------- writing files ----------

    private fun scopeFor(kind: ExportKind) = if (kind == ExportKind.Backup) ExportScope() else ui.value.scope

    /** Saves an export where the person chose ([uri] from the system's "save as"). */
    fun onSaveTo(kind: ExportKind, uri: Uri?) {
        if (uri == null) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { backups.write(kind, scopeFor(kind), it) }
                }
            }.isSuccess
            if (ok && kind == ExportKind.Backup) settings.markBackup(clock.millis())
            ui.update { it.copy(busy = false, message = if (ok) Texts.FILE_SAVED else Texts.FILE_FAILED, messageError = !ok) }
        }
    }

    fun onShare(kind: ExportKind) {
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = runCatching {
                shareFile(context, backups.fileName(kind), kind.mime, Texts.SHARE_TITLE) { out ->
                    withContext(Dispatchers.IO) { backups.write(kind, scopeFor(kind), out) }
                }
            }.isSuccess
            if (ok && kind == ExportKind.Backup) settings.markBackup(clock.millis())
            ui.update { it.copy(busy = false, message = if (ok) null else Texts.FILE_FAILED, messageError = !ok) }
        }
    }

    /** The journal as it was before the last restore, saved out of the app. */
    fun onSaveSafety(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> backups.safetyCopy.inputStream().use { it.copyTo(out) } }
                }
            }.isSuccess
            ui.update { it.copy(message = if (ok) Texts.FILE_SAVED else Texts.FILE_FAILED, messageError = !ok) }
        }
    }

    // ---------- restoring ----------

    fun onRestorePicked(uri: Uri?) {
        if (uri == null) return
        if ((ui.value.restore as? RestoreUi.Ready)?.busy == true) return
        onRestoreCancel()
        val sequence = ++readSequence
        ui.update { it.copy(restore = RestoreUi.Reading, message = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { backups.read(it) } }
            }.getOrElse { ReadResult.Failed(listOf(ImportError(dev.spendcodex.data.backup.ImportProblem.NotReadable, detail = it.message))) }
            if (sequence != readSequence) { (result as? ReadResult.Ok)?.preview?.close(); return@launch }
            ui.update {
                it.copy(restore = when (result) {
                    is ReadResult.Ok -> RestoreUi.Ready(result.preview)
                    is ReadResult.Failed -> RestoreUi.Failed(result.errors)
                })
            }
        }
    }

    /** First tap arms, the second replaces: nothing that destroys data happens on one tap. */
    fun onRestoreConfirm(onDone: () -> Unit = {}) {
        val ready = ui.value.restore as? RestoreUi.Ready ?: return
        if (!ready.armed) {
            ui.update { it.copy(restore = ready.copy(armed = true)) }
            return
        }
        if (ready.busy) return
        ui.update { it.copy(restore = ready.copy(busy = true)) }
        viewModelScope.launch {
            val ok = runCatching { backups.restore(ready.preview) }
            ui.update { it.copy(restore = RestoreUi.Idle) }
            safety.value = backups.hasSafetyCopy()
            if (ok.isSuccess) {
                notices.show(Notice(Texts.RESTORED))
                ui.update { it.copy(message = Texts.RESTORED, messageError = false) }
                onDone()
            } else {
                ui.update { it.copy(message = Texts.FILE_FAILED + " " + ok.exceptionOrNull()?.message.orEmpty(), messageError = true) }
            }
        }
    }

    fun onRestoreCancel() {
        val ready = ui.value.restore as? RestoreUi.Ready
        if (ready?.busy == true) return
        readSequence++
        ready?.preview?.close()
        ui.update { it.copy(restore = RestoreUi.Idle) }
    }

    override fun onCleared() { (ui.value.restore as? RestoreUi.Ready)?.preview?.close(); super.onCleared() }
}
