package dev.spendcodex.ui.operation

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftBuilder
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.Problem
import dev.spendcodex.data.domain.Transaction
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.repo.SaveResult
import dev.spendcodex.data.receipts.ReceiptPhoto
import dev.spendcodex.data.receipts.ReceiptImages
import dev.spendcodex.data.receipts.ReceiptLimits
import dev.spendcodex.data.settings.Lang
import dev.spendcodex.data.settings.Settings
import dev.spendcodex.ui.Notice
import dev.spendcodex.ui.Notices
import dev.spendcodex.ui.OperationRequest
import dev.spendcodex.ui.journal.Names
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** The parts of the form that open a list of choices under them. */
enum class FormField { Currency, Account, ToAccount, Category, Date, Time, RefundOf }

data class FormState(
    val key: String? = null,
    val loading: Boolean = true,
    val draft: Draft = Draft(),
    val existing: Transaction? = null,
    val tagsText: String = "",
    val timeText: String = "",
    val dateText: String = "",
    val open: FormField? = null,
    val problems: List<Problem> = emptyList(),
    val busy: Boolean = false,
    val recentExpenses: List<Transaction> = emptyList(),
    val refundTarget: Transaction? = null,
    /** True when the form records what the quick line had started: the line is cleared once it is recorded. */
    val fromLine: Boolean = false,
    val photos: List<ReceiptPhoto> = emptyList(),
    val photoBusy: Boolean = false,
    val photoMessage: String? = null,
    val removedPhoto: Pair<Int, ReceiptPhoto>? = null,
)

data class OperationUiState(
    val form: FormState = FormState(),
    val names: Names = Names(emptyList(), emptyList(), emptyList()),
    val defaultAccountId: String? = null,
    val today: LocalDate = LocalDate.now(),
)

sealed interface OperationEffect {
    /** The page is done; [recordedFromLine] tells the journal to clear the line it was opened from. */
    data class Close(val recordedFromLine: Boolean = false) : OperationEffect
}

@HiltViewModel
class OperationViewModel @Inject constructor(
    private val ledger: Ledger,
    private val settings: Settings,
    private val notices: Notices,
    private val clock: Clock,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val form = MutableStateFlow(FormState())
    private val _effects = Channel<OperationEffect>(Channel.BUFFERED)
    val effects: Flow<OperationEffect> = _effects.receiveAsFlow()
    private var cameraFile: File? = null
    private var cameraKey: String? = null

    private val names = combine(ledger.accounts, ledger.categories, ledger.tags) { a, c, t -> Names(a, c, t) }

    val state: StateFlow<OperationUiState> = combine(form, names, settings.choices) { f, n, choices ->
        val default = choices.defaultAccountId?.takeIf { id -> n.active.any { it.id == id } } ?: n.active.firstOrNull()?.id
        OperationUiState(f, n, default, LocalDate.now(clock))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OperationUiState())

    /** Opens the form for [request]; the same opening again (a rotation) keeps what was typed. */
    fun open(key: String, request: OperationRequest, fromLine: Boolean = false) {
        if (form.value.key == key) return
        form.value = FormState(key = key, loading = true, fromLine = fromLine)
        viewModelScope.launch {
            when (request) {
                is OperationRequest.New -> load(key, request.draft, null)
                is OperationRequest.Edit -> {
                    val tx = ledger.get(request.id)
                    if (tx == null) {
                        _effects.send(OperationEffect.Close())
                        return@launch
                    }
                    val accounts = ledger.accountsNow().associate { it.id to it.currency }
                    load(key, Draft.of(tx, ledger.tagNames(tx.tagIds).sorted(), { accounts[it] }, Lang.current.decimal), tx)
                }
            }
        }
    }

    private suspend fun load(key: String, draft: Draft, existing: Transaction?) {
        val recent = ledger.recentExpenses(40)
        val target = draft.refundOf?.let { ledger.get(it) }
        val photos = existing?.let { tx -> ledger.receiptsOf(tx.id).map { ReceiptPhoto(it) } }.orEmpty()
        form.update {
            if (it.key != key) it else it.copy(
                loading = false,
                draft = draft,
                existing = existing,
                tagsText = draft.tags.joinToString(" "),
                timeText = (draft.time as? DraftTime.At)?.let { t -> Format.time(t.time) }.orEmpty(),
                dateText = draft.date?.let(Format::numericDate).orEmpty(),
                recentExpenses = recent.filter { e -> e.id != draft.id },
                refundTarget = target,
                photos = photos,
            )
        }
    }

    fun update(change: (Draft) -> Draft) = form.update { it.copy(draft = change(it.draft), problems = emptyList()) }

    fun onOpen(field: FormField?) = form.update { it.copy(open = if (it.open == field) null else field) }

    fun onTags(text: String) = form.update {
        it.copy(tagsText = text, draft = it.draft.copy(tags = text.split(' ', ',', ';').map { s -> s.trim().removePrefix("#") }.filter { s -> s.isNotEmpty() }))
    }

    fun onDate(date: LocalDate) = form.update {
        val time = it.draft.time.let { t -> if (t is DraftTime.At) t else if (date == LocalDate.now(clock)) DraftTime.Now else DraftTime.Unknown }
        it.copy(draft = it.draft.copy(date = date, time = time), dateText = Format.numericDate(date), open = null, problems = emptyList())
    }

    fun onDateText(text: String) = form.update { f ->
        val parsed = Format.parseNumericDate(text)
        if (parsed != null) {
            val time = f.draft.time.let { t -> if (t is DraftTime.At) t else if (parsed == LocalDate.now(clock)) DraftTime.Now else DraftTime.Unknown }
            f.copy(dateText = text, draft = f.draft.copy(date = parsed, time = time))
        } else f.copy(dateText = text)
    }

    fun onTimeText(text: String) = form.update { f ->
        val parsed = Format.parseTime(text)
        f.copy(timeText = text, draft = if (parsed != null) f.draft.copy(time = DraftTime.At(parsed)) else f.draft)
    }

    fun onTime(time: DraftTime) = form.update { it.copy(draft = it.draft.copy(time = time), timeText = "", open = null) }

    fun onRefundOf(tx: Transaction?) = form.update {
        it.copy(draft = it.draft.copy(refundOf = tx?.id, categoryId = it.draft.categoryId ?: tx?.categoryId), refundTarget = tx, open = null)
    }

    /** The problems the form has now, without saving: shown as it is filled in. */
    fun livePreview(s: OperationUiState): List<Problem> {
        if (s.form.loading) return emptyList()
        val built = DraftBuilder.build(s.form.draft, s.names.refs().let { r ->
            dev.spendcodex.data.domain.Refs(r.accounts, r.categories, r.tagIds) { id -> if (id == s.form.refundTarget?.id) s.form.refundTarget?.type else null }
        }, s.defaultAccountId, ZonedDateTime.now(clock), s.form.existing)
        return built.problems
    }

    fun onSave() {
        val f = form.value
        if (f.busy || f.loading || f.photoBusy) return
        form.update { it.copy(busy = true) }
        viewModelScope.launch {
          try {
            when (val r = ledger.save(f.draft, f.photos)) {
                is SaveResult.Invalid -> form.update { it.copy(busy = false, problems = r.problems) }
                is SaveResult.Saved -> {
                    notices.show(
                        if (f.existing != null) Notice(Texts.SAVED)
                        else Notice(Texts.recorded(1, describe(r.tx)), Texts.UNDO, undo = { ledger.delete(r.tx.id) }),
                    )
                    form.update { FormState() }
                    _effects.send(OperationEffect.Close(recordedFromLine = f.fromLine && f.existing == null))
                }
            }
          } catch (e: CancellationException) { throw e }
          catch (_: Exception) { form.update { it.copy(busy = false, photoMessage = Texts.OPERATION_SAVE_FAILED) } }
        }
    }

    fun onDelete() {
        if (form.value.busy || form.value.photoBusy) return
        val tx = form.value.existing ?: return
        viewModelScope.launch {
            val removed = ledger.delete(tx.id) ?: return@launch
            notices.show(Notice(Texts.deleted(describe(tx)), Texts.RESTORE_ONE, undo = { ledger.undo(removed) }))
            form.update { FormState() }
            _effects.send(OperationEffect.Close())
        }
    }

    fun onRepeat() {
        if (form.value.busy || form.value.photoBusy) return
        val tx = form.value.existing ?: return
        if (tx.type != TxType.Expense) return
        viewModelScope.launch {
            val r = ledger.repeat(tx.id) as? SaveResult.Saved
            if (r == null) {
                form.update { it.copy(problems = listOf(Problem.AccountArchived)) }
                return@launch
            }
            notices.show(Notice(Texts.repeated(describe(r.tx)), Texts.UNDO, undo = { ledger.delete(r.tx.id) }))
            form.update { FormState() }
            _effects.send(OperationEffect.Close())
        }
    }

    /** Turns the page into a refund of this purchase, to be checked and recorded. */
    fun onMakeRefund() {
        if (form.value.busy || form.value.photoBusy) return
        val tx = form.value.existing ?: return
        viewModelScope.launch {
            val draft = ledger.refundDraft(tx.id) ?: return@launch
            val key = form.value.key ?: return@launch
            form.value = FormState(key = key, loading = true)
            load(key, draft.copy(id = UUID.randomUUID().toString(), amount = draft.amount.let { if (Lang.current.decimal == '.') it else it.replace('.', Lang.current.decimal) }), null)
        }
    }

    private fun describe(tx: Transaction): String {
        val n = state.value.names
        val title = tx.description ?: tx.payee ?: n.categoryPath(tx.categoryId) ?: Texts.type(tx.type)
        val signed = if (tx.type == TxType.Expense) -tx.amountMinor else tx.amountMinor
        return "$title ${Format.money(signed, tx.currency, withSign = tx.type == TxType.Income || tx.type == TxType.Refund)}"
    }

    suspend fun photoBytes(photo: ReceiptPhoto): ByteArray? = photo.bytes ?: ledger.receiptBytes(photo.receipt.id)

    fun onPhotosPicked(uris: List<Uri>) {
        val f = form.value
        if (uris.isEmpty() || f.photoBusy || f.busy || f.loading) return
        val capacity = ReceiptLimits.PER_OPERATION - f.photos.size
        if (capacity <= 0) { photoError(Texts.RECEIPT_LIMIT); return }
        form.update { it.copy(photoBusy = true, photoMessage = null) }
        viewModelScope.launch {
            var message: String? = if (uris.size > capacity) Texts.RECEIPT_LIMIT else null
            for (uri in uris.take(capacity)) {
                try {
                    val photo = withContext(Dispatchers.IO) { ReceiptImages.read(context, uri, f.draft.id, clock.millis()) }
                    form.update { current ->
                        if (current.key != f.key) current
                        else if (current.photos.any { it.receipt.sha256 == photo.receipt.sha256 }) {
                            message = Texts.RECEIPT_DUPLICATE
                            current
                        } else current.copy(photos = current.photos + photo)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = Texts.RECEIPT_FAILED }
                catch (_: OutOfMemoryError) { message = Texts.RECEIPT_FAILED }
            }
            form.update { if (it.key == f.key) it.copy(photoBusy = false, photoMessage = message) else it }
        }
    }

    fun onRemovePhoto(id: String) = form.update { f ->
        if (f.photoBusy || f.busy) f else {
            val i = f.photos.indexOfFirst { it.receipt.id == id }
            if (i < 0) f else f.copy(photos = f.photos.filterIndexed { index, _ -> index != i }, removedPhoto = i to f.photos[i], photoMessage = null)
        }
    }

    fun onUndoRemovePhoto() = form.update { f ->
        val removed = f.removedPhoto
        if (removed == null || f.busy || f.photoBusy || f.photos.size >= ReceiptLimits.PER_OPERATION) f
        else f.copy(photos = f.photos.toMutableList().also { it.add(removed.first.coerceAtMost(it.size), removed.second) }, removedPhoto = null)
    }

    fun photoError(message: String) = form.update { it.copy(photoMessage = message) }

    fun cameraTarget(): Uri? = try {
        cameraFile?.delete()
        val dir = File(context.cacheDir, "camera").also { check(it.isDirectory || it.mkdirs()) }
        val file = File.createTempFile("receipt-", ".jpg", dir)
        cameraFile = file
        cameraKey = form.value.key
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (e: Exception) {
        cameraFile?.delete(); cameraFile = null
        android.util.Log.w("Receipts", "Could not prepare camera output", e)
        photoError(Texts.RECEIPT_CAMERA_FAILED); null
    }

    fun onCameraResult(success: Boolean) {
        val file = cameraFile ?: return
        cameraFile = null
        if (!success || cameraKey != form.value.key) { file.delete(); return }
        // Keep the camera file until compression has finished opening it.
        val f = form.value
        if (f.photoBusy || f.busy || f.photos.size >= ReceiptLimits.PER_OPERATION) { file.delete(); return }
        form.update { it.copy(photoBusy = true, photoMessage = null) }
        viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { ReceiptImages.read(context, Uri.fromFile(file), f.draft.id, clock.millis()) }
                form.update { if (it.key != f.key) it else if (it.photos.any { p -> p.receipt.sha256 == photo.receipt.sha256 })
                    it.copy(photoBusy = false, photoMessage = Texts.RECEIPT_DUPLICATE)
                    else it.copy(photos = it.photos + photo, photoBusy = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { form.update { if (it.key == f.key) it.copy(photoBusy = false, photoMessage = Texts.RECEIPT_FAILED) else it } }
            catch (_: OutOfMemoryError) { form.update { if (it.key == f.key) it.copy(photoBusy = false, photoMessage = Texts.RECEIPT_FAILED) else it } }
            finally { file.delete() }
        }
    }

    override fun onCleared() { cameraFile?.delete(); super.onCleared() }
}
