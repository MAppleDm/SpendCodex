package dev.spendcodex.ui.journal

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.data.ai.AiContext
import dev.spendcodex.data.ai.AiDraft
import dev.spendcodex.data.ai.AiFailure
import dev.spendcodex.data.ai.AiIssue
import dev.spendcodex.data.ai.AiReader
import dev.spendcodex.data.db.FeedRow
import dev.spendcodex.data.db.TypeTotal
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftBuilder
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.Transaction
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.log.AppLog
import dev.spendcodex.data.receipts.ReceiptImages
import dev.spendcodex.data.receipts.ReceiptLimits
import dev.spendcodex.data.receipts.ReceiptPhoto
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.repo.SaveResult
import dev.spendcodex.data.settings.Lang
import dev.spendcodex.data.settings.SecretStore
import dev.spendcodex.data.settings.Settings
import dev.spendcodex.ui.CameraCapture
import dev.spendcodex.ui.Notice
import dev.spendcodex.ui.Notices
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AiUi {
    data object Idle : AiUi
    data object Reading : AiUi
    data class Failed(val message: String, val retryable: Boolean, val needsKey: Boolean = false) : AiUi
    /**
     * Uncertain answers to a line stop here, and every receipt read from photos; an ordinary, validated line saves in one
     * step. [notes]: what concerns the photos as a whole (one not read, a receipt without a total).
     */
    data class Result(val items: List<AiItem>, val notes: List<String> = emptyList()) : AiUi
}

/** One operation to record; from a receipt (the whole purchase), with its items as [caption] and the [photos] it is to keep. */
data class AiItem(
    val draft: Draft, val title: String, val amount: String, val warnings: List<String>, val problems: List<String>,
    val caption: String? = null, val photos: List<ReceiptPhoto> = emptyList(),
)

/**
 * The line and what DeepSeek is doing with it. [fromPhotos]: DeepSeek is reading receipt photos instead, and the line stays
 * as it is meanwhile; [photos] are those photos once prepared.
 */
data class ComposerState(
    val text: String = "",
    val batchId: String = UUID.randomUUID().toString(),
    val busy: Boolean = false,
    val saving: Boolean = false,
    val ai: AiUi = AiUi.Idle,
    val fromPhotos: Boolean = false,
    val photos: List<ReceiptPhoto> = emptyList(),
)

/** What the manual form starts from: the line, DeepSeek's single answer or the photos being read, attached. */
data class FormStart(val draft: Draft, val photos: List<ReceiptPhoto>, val fromLine: Boolean)

data class JournalUiState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val rows: List<FeedRow> = emptyList(),
    val hasMore: Boolean = false,
    val names: Names = Names(emptyList(), emptyList(), emptyList()),
    val todayTotals: List<TypeTotal> = emptyList(),
    val defaultAccountId: String? = null,
    val hasKey: Boolean = false,
    val composer: ComposerState = ComposerState(),
    val canSend: Boolean = false,
    val notice: Notice? = null,
)

sealed interface JournalEffect { data object Recorded : JournalEffect }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val ledger: Ledger,
    private val settings: Settings,
    private val secrets: SecretStore,
    private val ai: AiReader,
    private val notices: Notices,
    private val clock: Clock,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val limit = MutableStateFlow(PAGE)
    private val today = MutableStateFlow(LocalDate.now(clock))
    private val composer = MutableStateFlow(ComposerState())
    private val _effects = Channel<JournalEffect>(Channel.BUFFERED)
    val effects: Flow<JournalEffect> = _effects.receiveAsFlow()
    private var request: Job? = null
    private val camera = CameraCapture(context)

    private val names = combine(ledger.accounts, ledger.categories, ledger.tags) { a, c, t -> Names(a, c, t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Names(emptyList(), emptyList(), emptyList()))
    private val rows = limit.flatMapLatest { ledger.feed(Filter(types = EXPENSE_TYPES), it) }
    private val totals = today.flatMapLatest { ledger.totals(Filter(from = it, to = it, types = EXPENSE_TYPES)) }
    private data class Data(val rows: List<FeedRow>, val names: Names, val totals: List<TypeTotal>, val today: LocalDate)
    private val data = combine(rows, names, totals, today) { r, n, t, d -> Data(r, n, t, d) }

    val state: StateFlow<JournalUiState> = combine(data, composer, settings.choices, secrets.hasKey, notices.current) { d, c, choices, key, notice ->
        val default = choices.defaultAccountId?.takeIf { id -> d.names.active.any { it.id == id } } ?: d.names.active.firstOrNull()?.id
        JournalUiState(
            loaded = true, today = d.today, rows = d.rows, hasMore = d.rows.size >= limit.value,
            names = d.names, todayTotals = d.totals, defaultAccountId = default, hasKey = key,
            composer = c, canSend = !c.busy && c.text.isNotBlank() && c.ai !is AiUi.Result, notice = notice,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, JournalUiState())

    fun onText(text: String) {
        val old = composer.value
        if (old.text == text || old.saving) return
        // photos are read whatever the line says
        if (old.fromPhotos) { composer.update { it.copy(text = text) }; return }
        request?.cancel()
        composer.value = ComposerState(text = text)
    }

    fun onSharedText(text: String) = onText(text.trim())

    /** No local interpretation: preserve the note, or start from a blocked AI draft; photos being read go along. */
    fun formStart(): FormStart {
        val c = composer.value
        val item = (c.ai as? AiUi.Result)?.items?.singleOrNull()
        onAiClose()
        val id = UUID.randomUUID().toString()
        val line = if (c.fromPhotos) null else c.text.trim().ifEmpty { null }
        val draft = (item?.draft ?: Draft(description = line.orEmpty(), currency = settings.choices.value.defaultCurrency))
            .copy(id = id, type = TxType.Expense, origin = Origin.Form, inputText = line)
        val photos = (item?.photos ?: c.photos).take(ReceiptLimits.PER_OPERATION).map { it.copyFor(id) }
        return FormStart(draft, photos, fromLine = line != null)
    }

    fun onFormRecorded() { composer.value = ComposerState() }

    fun onSend() {
        val c = composer.value
        // Check the source state: two quick taps must never start two requests.
        if (c.busy || c.text.isBlank() || c.ai is AiUi.Result) return
        if (!secrets.hasKey.value) {
            composer.update { it.copy(ai = AiUi.Failed(Texts.AI_NO_KEY, false, needsKey = true)) }
            return
        }
        if (c.text.length > 20_000) {
            composer.update { it.copy(ai = AiUi.Failed(Texts.AI_INPUT_LONG, false)) }
            return
        }
        request?.cancel()
        composer.update { it.copy(busy = true, ai = AiUi.Reading) }
        request = viewModelScope.launch {
            try {
                val n = names.value
                val choices = settings.choices.value
                val default = ledger.defaultAccountId()
                val drafts = withContext(Dispatchers.IO) { ai.read(c.text, aiContext(n)) }
                if (composer.value.batchId != c.batchId) return@launch
                val items = drafts.mapIndexed { index, d ->
                    val id = UUID.nameUUIDFromBytes("${c.batchId}:$index".toByteArray(Charsets.UTF_8)).toString()
                    aiItem(d.copy(draft = d.draft.copy(id = id, accountId = d.draft.accountId ?: default,
                        currency = d.draft.currency ?: choices.defaultCurrency)), n, default)
                }
                logItems("line", items)
                if (items.any { it.problems.isNotEmpty() || it.warnings.isNotEmpty() }) {
                    composer.update { it.copy(busy = false, ai = AiUi.Result(items)) }
                } else save(items, c.batchId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(c.batchId, e) }
        }
    }

    /** Photos of receipts, from the camera, the gallery or another app: DeepSeek reads them, the person checks and records. */
    fun onPhotos(uris: List<Uri>, afterRead: () -> Unit = {}) {
        val c = composer.value
        if (uris.isEmpty() || c.busy || c.saving || c.ai is AiUi.Result) { afterRead(); return }
        if (!secrets.hasKey.value) {
            afterRead()
            composer.update { it.copy(ai = AiUi.Failed(Texts.AI_NO_KEY, false, needsKey = true)) }
            return
        }
        request?.cancel()
        val batch = UUID.randomUUID().toString()
        AppLog.i(TAG, "${uris.size} photo(s) to read")
        composer.update { it.copy(batchId = batch, busy = true, ai = AiUi.Reading, fromPhotos = true, photos = emptyList()) }
        request = viewModelScope.launch {
            val chosen = uris.take(AiReader.MAX_PHOTOS)
            val photos = try {
                withContext(Dispatchers.IO) {
                    // bound to no record yet: each operation recorded from them gets copies of its own
                    chosen.mapNotNull { uri ->
                        try { ReceiptImages.read(context, uri, "", clock.millis()) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { AppLog.w(TAG, "could not read a photo: $uri", e); null }
                        catch (e: OutOfMemoryError) { AppLog.w(TAG, "photo too large: $uri", e); null }
                    }.distinctBy { it.receipt.sha256 }
                }
            } finally { afterRead() }
            if (composer.value.batchId != batch) return@launch
            if (photos.isEmpty()) {
                composer.update { it.copy(busy = false, ai = AiUi.Failed(Texts.RECEIPT_FAILED, false), fromPhotos = false) }
                return@launch
            }
            composer.update { it.copy(photos = photos) }
            AppLog.i(TAG, "photos prepared: ${photos.joinToString { "${it.receipt.width}×${it.receipt.height} ${it.receipt.size / 1024} KB" }}")
            val notes = listOfNotNull(
                Texts.PHOTOS_LIMIT.takeIf { uris.size > chosen.size },
                Texts.photosSkipped(chosen.size - photos.size).takeIf { photos.size < chosen.size },
            )
            readPhotos(batch, photos, notes)
        }
    }

    private suspend fun readPhotos(batch: String, photos: List<ReceiptPhoto>, notes: List<String>) {
        try {
            val n = names.value
            val choices = settings.choices.value
            val default = ledger.defaultAccountId()
            val drafts = withContext(Dispatchers.IO) { ai.readPhotos(photos.map { ReceiptImages.forReading(it.bytes!!) }, aiContext(n)) }
            if (composer.value.batchId != batch) return
            val items = drafts.mapIndexed { index, d ->
                val id = UUID.nameUUIDFromBytes("$batch:$index".toByteArray(Charsets.UTF_8)).toString()
                val draft = d.draft.copy(id = id, accountId = d.draft.accountId ?: default, currency = d.draft.currency ?: choices.defaultCurrency)
                aiItem(d.copy(draft = draft), n, default)
                    .copy(caption = draft.note.ifBlank { null }, photos = d.receipt?.photos.orEmpty().map { photos[it].copyFor(id) })
            }
            val receipts = drafts.mapNotNull { it.receipt?.let { r -> r to it.draft } }
            val checks = receipts.filter { it.first.total == null }.map { (r, d) -> Texts.receiptNoTotal(Texts.receiptLabel(d.payee.ifBlank { null }, r.index)) }
            val read = receipts.flatMap { it.first.photos }.toSet()
            val unread = photos.indices.filter { it !in read }.map { Texts.photoUnread(it + 1) }
            logItems("photos", items, notes + checks + unread)
            composer.update { it.copy(busy = false, ai = AiUi.Result(items, notes + checks + unread)) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed(batch, e, Texts.RECEIPT_FAILED) }
        catch (_: OutOfMemoryError) { failed(batch, IllegalStateException("out of memory"), Texts.RECEIPT_FAILED) }
    }

    fun cameraTarget(): Uri? = camera.target() ?: run {
        composer.update { if (it.busy) it else it.copy(ai = AiUi.Failed(Texts.RECEIPT_CAMERA_FAILED, false)) }
        null
    }

    fun onCameraResult(success: Boolean) {
        val file = camera.taken(success) ?: return
        onPhotos(listOf(Uri.fromFile(file))) { file.delete() }
    }

    private fun aiContext(n: Names) = AiContext(LocalDate.now(clock), clock.zone.id, Lang.en, n.categories,
        settings.choices.value.defaultCurrency, n.tags.filter { !it.archived }.map { it.name })

    /** What went wrong, for the panel above the line; [fallback] for a failure that is not DeepSeek's. */
    private fun failed(batch: String, e: Exception, fallback: String = Texts.OPERATION_SAVE_FAILED) {
        if (e is AiFailure) AppLog.w(TAG, "DeepSeek: ${e.javaClass.simpleName} ${e.detail}") else AppLog.e(TAG, "reading failed", e)
        val ai = if (e is AiFailure) {
            val message = when (e) {
                is AiFailure.NoKey -> Texts.AI_NO_KEY
                is AiFailure.Auth -> Texts.AI_AUTH
                is AiFailure.Input -> when (e.reason) {
                    "unsupported" -> Texts.AI_EXPENSES_ONLY
                    "needs_amount" -> Texts.AI_NEEDS_AMOUNT
                    "unreadable" -> Texts.PHOTO_UNREADABLE
                    "not_a_purchase" -> Texts.PHOTO_NOT_PURCHASE
                    else -> Texts.AI_UNCLEAR
                }
                else -> Texts.aiFailed(e.detail)
            }
            AiUi.Failed(message, e.retryable, e is AiFailure.NoKey || e is AiFailure.Auth)
        } else AiUi.Failed(fallback, true)
        if (composer.value.batchId == batch) composer.update { it.copy(busy = false, saving = false, ai = ai) }
    }

    private fun aiItem(a: AiDraft, n: Names, default: String?): AiItem {
        val built = DraftBuilder.build(a.draft, n.refs(), default, ZonedDateTime.now(clock))
        val currency = a.draft.currency ?: n.account(a.draft.accountId ?: default)?.currency
        val minor = currency?.let(Currencies::of)?.let { Decimal.parseHuman(a.draft.amount, it.exponent) }
        val amount = if (minor != null) Format.money(minor, currency) else a.draft.amount.ifBlank { "—" }
        val problems = buildList {
            addAll(built.problems.map(Texts::problem))
            if (!a.amountInText) add(Texts.AI_AMOUNT_NOT_IN_TEXT)
            a.issues.forEach { issue -> add(when (issue) {
                AiIssue.Currency -> Texts.AI_BAD_CURRENCY
                AiIssue.Date -> Texts.AI_BAD_DATE
                AiIssue.Time -> Texts.AI_BAD_TIME
                AiIssue.Tags -> Texts.AI_BAD_TAGS
            }) }
        }
        val warnings = buildList {
            a.unknownCategory?.let { add(Texts.aiUnknownCategory(it)) }
        }
        val description = a.draft.description.ifBlank { null } ?: Texts.typeTitle(a.draft.type)
        val category = n.categoryPath(a.draft.categoryId)?.takeIf { !it.equals(description, ignoreCase = true) }
        val title = listOfNotNull(description, category, a.draft.payee.ifBlank { null }?.takeIf { a.receipt != null },
            a.draft.date?.let { Format.shortDate(it, today.value) }).joinToString(" · ")
        return AiItem(a.draft, title, amount, warnings, problems.distinct())
    }

    private suspend fun save(items: List<AiItem>, batchId: String) {
        composer.update { it.copy(busy = true, saving = true) }
        val results = if (items.any { it.photos.isNotEmpty() }) ledger.saveWithPhotos(items.map { it.draft to it.photos })
            else ledger.saveAll(items.map { it.draft })
        AppLog.i(TAG, "recorded ${items.size}: $results")
        val invalid = results.singleOrNull() as? SaveResult.Invalid
        if (invalid != null) {
            val updated = items.mapIndexed { i, item -> if (i == invalid.index) item.copy(problems = invalid.problems.map(Texts::problem)) else item }
            composer.update { it.copy(busy = false, saving = false, ai = AiUi.Result(updated)) }
            return
        }
        // after photos the line is kept: it was not part of what was recorded
        composer.update { if (it.batchId != batchId) it else if (it.fromPhotos) ComposerState(text = it.text) else ComposerState() }
        // the new rows are the confirmation; a mistaken one is swiped away
        if (results.any { it is SaveResult.Saved && it.created }) _effects.trySend(JournalEffect.Recorded)
    }

    fun onAiRetry() {
        val c = composer.value
        if (!c.fromPhotos) { onSend(); return }
        if (c.busy || c.photos.isEmpty()) return
        request?.cancel()
        composer.update { it.copy(busy = true, ai = AiUi.Reading) }
        request = viewModelScope.launch { readPhotos(c.batchId, c.photos, emptyList()) }
    }

    fun onAiClose() {
        if (composer.value.saving) return
        request?.cancel()
        composer.update { if (it.fromPhotos) ComposerState(text = it.text) else it.copy(busy = false, ai = AiUi.Idle) }
    }

    fun onAiRecord() {
        val c = composer.value
        val result = c.ai as? AiUi.Result ?: return
        if (c.busy || result.items.any { it.problems.isNotEmpty() }) return
        composer.update { it.copy(busy = true, saving = true) }
        request = viewModelScope.launch {
            try { save(result.items, c.batchId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.e(TAG, "recording failed", e)
                composer.update { it.copy(busy = false, saving = false, ai = AiUi.Result(result.items)) }
                notices.show(Notice(Texts.OPERATION_SAVE_FAILED))
            }
        }
    }

    /** A record or the manual form was just recorded: the journal goes to the newest rows. */
    fun onRecorded() { _effects.trySend(JournalEffect.Recorded) }

    /** A swipe deleted a record; the line above the input brings it back, with its tags, receipts and refunds' links. */
    fun onDelete(id: String) {
        viewModelScope.launch {
            val removed = ledger.delete(id) ?: return@launch
            AppLog.i(TAG, "deleted ${removed.tx.id} by a swipe")
            notices.show(Notice(Texts.deleted(describe(removed.tx)), Texts.RESTORE_ONE, undo = { ledger.undo(removed) }))
        }
    }

    fun describe(tx: Transaction): String {
        val title = tx.description ?: tx.payee ?: names.value.categoryPath(tx.categoryId) ?: Texts.type(tx.type)
        return "$title ${Format.money(tx.amountMinor, tx.currency, withSign = tx.type == TxType.Income || tx.type == TxType.Refund)}"
    }

    /** What the person is shown to check or what is recorded at once, as the log keeps it. */
    private fun logItems(source: String, items: List<AiItem>, notes: List<String> = emptyList()) {
        AppLog.i(TAG, "read from $source: ${items.size} item(s)" + notes.joinToString("") { "\n  note: $it" } + items.joinToString("") { item ->
            "\n  ${item.amount} · ${item.title} · ${item.draft.tags.joinToString(" ") { "#$it" }} · photos ${item.photos.size}" +
                (item.caption?.let { "\n    $it" } ?: "") + (item.warnings + item.problems).joinToString("") { "\n    ! $it" }
        })
    }

    fun onNeedMore() { if (state.value.hasMore) limit.update { it + PAGE } }
    fun onNotice(notice: Notice) {
        notices.dismiss(notice.id)
        val undo = notice.undo ?: return
        viewModelScope.launch { undo() }
    }
    fun dismissNotice(id: Long) = notices.dismiss(id)
    fun onForeground() { today.value = LocalDate.now(clock) }

    override fun onCleared() { camera.clear(); super.onCleared() }

    companion object {
        const val PAGE = 200
        private const val TAG = "Journal"
        val EXPENSE_TYPES = setOf(TxType.Expense, TxType.Refund)
    }
}
