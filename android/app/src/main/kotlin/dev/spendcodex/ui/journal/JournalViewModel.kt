package dev.spendcodex.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.repo.SaveResult
import dev.spendcodex.data.settings.Lang
import dev.spendcodex.data.settings.SecretStore
import dev.spendcodex.data.settings.Settings
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
    /** Only uncertain answers stop here; an ordinary, validated expense saves in one step. */
    data class Result(val items: List<AiItem>) : AiUi
}

data class AiItem(val draft: Draft, val title: String, val amount: String, val warnings: List<String>, val problems: List<String>)

data class ComposerState(
    val text: String = "",
    val batchId: String = UUID.randomUUID().toString(),
    val busy: Boolean = false,
    val saving: Boolean = false,
    val ai: AiUi = AiUi.Idle,
)

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
) : ViewModel() {
    private val limit = MutableStateFlow(PAGE)
    private val today = MutableStateFlow(LocalDate.now(clock))
    private val composer = MutableStateFlow(ComposerState())
    private val _effects = Channel<JournalEffect>(Channel.BUFFERED)
    val effects: Flow<JournalEffect> = _effects.receiveAsFlow()
    private var request: Job? = null

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
        request?.cancel()
        composer.value = ComposerState(text = text)
    }

    fun onSharedText(text: String) = onText(text.trim())

    /** No local interpretation: preserve the note, or start from a blocked AI draft. */
    fun formDraft(): Draft {
        val c = composer.value
        val first = (c.ai as? AiUi.Result)?.items?.singleOrNull()?.draft
        onAiClose()
        return (first ?: Draft(description = c.text.trim(), currency = settings.choices.value.defaultCurrency))
            .copy(id = UUID.randomUUID().toString(), type = TxType.Expense, origin = Origin.Form, inputText = c.text.trim().ifEmpty { null })
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
                val ctx = AiContext(LocalDate.now(clock), clock.zone.id, Lang.en, n.categories,
                    choices.defaultCurrency, n.tags.filter { !it.archived }.map { it.name })
                val drafts = withContext(Dispatchers.IO) { ai.read(c.text, ctx) }
                if (composer.value.batchId != c.batchId) return@launch
                val items = drafts.mapIndexed { index, d ->
                    val id = UUID.nameUUIDFromBytes("${c.batchId}:$index".toByteArray(Charsets.UTF_8)).toString()
                    aiItem(d.copy(draft = d.draft.copy(id = id, accountId = d.draft.accountId ?: default,
                        currency = d.draft.currency ?: choices.defaultCurrency)), n, default)
                }
                if (items.any { it.problems.isNotEmpty() || it.warnings.isNotEmpty() }) {
                    composer.update { it.copy(busy = false, ai = AiUi.Result(items)) }
                } else save(items, c.batchId)
            } catch (e: CancellationException) { throw e }
            catch (e: AiFailure) {
                val message = when (e) {
                    is AiFailure.NoKey -> Texts.AI_NO_KEY
                    is AiFailure.Auth -> Texts.AI_AUTH
                    is AiFailure.Input -> when (e.reason) {
                        "unsupported" -> Texts.AI_EXPENSES_ONLY
                        "needs_amount" -> Texts.AI_NEEDS_AMOUNT
                        else -> Texts.AI_UNCLEAR
                    }
                    else -> Texts.aiFailed(e.detail)
                }
                if (composer.value.batchId == c.batchId) composer.update {
                    it.copy(busy = false, saving = false, ai = AiUi.Failed(message, e.retryable, e is AiFailure.NoKey || e is AiFailure.Auth))
                }
            } catch (_: Exception) {
                if (composer.value.batchId == c.batchId) composer.update {
                    it.copy(busy = false, saving = false, ai = AiUi.Failed(Texts.OPERATION_SAVE_FAILED, true))
                }
            }
        }
    }

    private fun aiItem(a: AiDraft, n: Names, default: String?): AiItem {
        val built = DraftBuilder.build(a.draft, n.refs(), default, ZonedDateTime.now(clock))
        val currency = a.draft.currency ?: n.account(a.draft.accountId ?: default)?.currency
        val minor = currency?.let(Currencies::of)?.let { Decimal.parseHuman(a.draft.amount, it.exponent) }
        val amount = if (minor != null) Format.money(minor, currency) else a.draft.amount
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
        val title = listOfNotNull(a.draft.description.ifBlank { null } ?: Texts.typeTitle(a.draft.type),
            n.categoryPath(a.draft.categoryId), a.draft.date?.let { Format.shortDate(it, today.value) }).joinToString(" · ")
        return AiItem(a.draft, title, amount, warnings, problems.distinct())
    }

    private suspend fun save(items: List<AiItem>, batchId: String) {
        composer.update { it.copy(busy = true, saving = true) }
        val results = ledger.saveAll(items.map { it.draft })
        val invalid = results.singleOrNull() as? SaveResult.Invalid
        if (invalid != null) {
            val updated = items.mapIndexed { i, item -> if (i == invalid.index) item.copy(problems = invalid.problems.map(Texts::problem)) else item }
            composer.update { it.copy(busy = false, saving = false, ai = AiUi.Result(updated)) }
            return
        }
        if (composer.value.batchId == batchId) composer.value = ComposerState()
        announce(results.filterIsInstance<SaveResult.Saved>())
    }

    fun onAiRetry() { onSend() }

    fun onAiClose() {
        if (composer.value.saving) return
        request?.cancel()
        composer.update { it.copy(busy = false, ai = AiUi.Idle) }
    }

    fun onAiRecord() {
        val c = composer.value
        val result = c.ai as? AiUi.Result ?: return
        if (c.busy || result.items.any { it.problems.isNotEmpty() }) return
        composer.update { it.copy(busy = true, saving = true) }
        request = viewModelScope.launch {
            try { save(result.items, c.batchId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                composer.update { it.copy(busy = false, saving = false, ai = AiUi.Result(result.items)) }
                notices.show(Notice(Texts.OPERATION_SAVE_FAILED))
            }
        }
    }

    private fun announce(saved: List<SaveResult.Saved>) {
        val created = saved.filter { it.created }.map { it.tx }
        if (created.isEmpty()) return
        val what = if (created.size == 1) describe(created.single()) else ""
        notices.show(Notice(Texts.recorded(created.size, what), Texts.UNDO, undo = { created.forEach { ledger.delete(it.id) } }))
        _effects.trySend(JournalEffect.Recorded)
    }

    fun describe(tx: Transaction): String {
        val title = tx.description ?: tx.payee ?: names.value.categoryPath(tx.categoryId) ?: Texts.type(tx.type)
        return "$title ${Format.money(if (tx.type == TxType.Expense) -tx.amountMinor else tx.amountMinor, tx.currency)}"
    }

    fun onNeedMore() { if (state.value.hasMore) limit.update { it + PAGE } }
    fun onNotice(notice: Notice) {
        notices.dismiss(notice.id)
        val undo = notice.undo ?: return
        viewModelScope.launch { undo(); notices.show(Notice(Texts.UNDONE)) }
    }
    fun dismissNotice(id: Long) = notices.dismiss(id)
    fun onForeground() { today.value = LocalDate.now(clock) }

    companion object {
        const val PAGE = 200
        val EXPENSE_TYPES = setOf(TxType.Expense, TxType.Refund)
    }
}
