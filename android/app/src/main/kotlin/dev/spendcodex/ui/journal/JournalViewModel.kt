package dev.spendcodex.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.data.ai.AiContext
import dev.spendcodex.data.ai.AiDraft
import dev.spendcodex.data.ai.AiFailure
import dev.spendcodex.data.ai.AiReader
import dev.spendcodex.data.db.FeedRow
import dev.spendcodex.data.db.TypeTotal
import dev.spendcodex.data.domain.Balance
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.domain.Decimal
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftBuilder
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.Transaction
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.parse.LineContext
import dev.spendcodex.data.parse.LineResult
import dev.spendcodex.data.parse.QuickLine
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

/** DeepSeek's part of the input: reading, failed, or what it read waiting for "записать". */
sealed interface AiUi {
    data object Idle : AiUi
    data object Reading : AiUi
    data class Failed(val message: String, val retryable: Boolean) : AiUi
    data class Result(val items: List<AiItem>) : AiUi
}

data class AiItem(val draft: Draft, val title: String, val amount: String, val warnings: List<String>, val problems: List<String>)

/** The input as it is being written. */
data class ComposerState(
    val text: String = "",
    val parsed: List<LineResult> = emptyList(),
    val parsedFor: String = "",
    val ids: List<String> = emptyList(),
    val overrides: Map<Int, LineOverride> = emptyMap(),
    val picker: Picker? = null,
    val suggestions: List<Transaction> = emptyList(),
    val problem: String? = null,
    val busy: Boolean = false,
    val ai: AiUi = AiUi.Idle,
    val summaryOpen: Boolean = false,
)

data class JournalUiState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val rows: List<FeedRow> = emptyList(),
    val hasMore: Boolean = false,
    val names: Names = Names(emptyList(), emptyList(), emptyList()),
    val balances: Map<String, Balance> = emptyMap(),
    val todayTotals: List<TypeTotal> = emptyList(),
    val defaultAccountId: String? = null,
    val composer: ComposerState = ComposerState(),
    val preview: List<PreviewOp> = emptyList(),
    val canSend: Boolean = false,
    /** The line cannot be read here but DeepSeek is on: sending hands it over. */
    val willAskAi: Boolean = false,
    val notice: Notice? = null,
)

sealed interface JournalEffect {
    /** Something was recorded: scroll to it, a light tap of the phone. */
    data object Recorded : JournalEffect
}

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

    private val names: StateFlow<Names> = combine(ledger.accounts, ledger.categories, ledger.tags) { a, c, t -> Names(a, c, t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Names(emptyList(), emptyList(), emptyList()))

    private val rows = limit.flatMapLatest { ledger.feed(Filter(), it) }
    private val todayTotals = today.flatMapLatest { ledger.dayTotals(it) }

    private data class Data(val rows: List<FeedRow>?, val names: Names, val balances: Map<String, Balance>, val totals: List<TypeTotal>, val today: LocalDate)

    private val data = combine(rows, names, ledger.balances, todayTotals, today) { r, n, b, t, d -> Data(r, n, b, t, d) }

    val state: StateFlow<JournalUiState> = combine(data, composer, settings.choices, secrets.hasKey, notices.current) { d, c, choices, hasKey, notice ->
        val default = choices.defaultAccountId?.takeIf { id -> d.names.active.any { it.id == id } } ?: d.names.active.firstOrNull()?.id
        val now = ZonedDateTime.now(clock)
        val parsedNow = c.parsedFor == c.text
        val preview = if (parsedNow) c.parsed.mapIndexed { i, r ->
            val draft = Composer.apply(r, c.overrides[i], c.ids.getOrElse(i) { "" }, d.names, d.today)
            Composer.preview(i, r, draft, c.overrides[i], d.names, default, now)
        } else emptyList()
        val blocking = parsedNow && c.parsed.any { it.blocking }
        val aiReady = choices.aiEnabled && hasKey
        JournalUiState(
            loaded = d.rows != null,
            today = d.today,
            rows = d.rows.orEmpty(),
            hasMore = (d.rows?.size ?: 0) >= limit.value,
            names = d.names,
            balances = d.balances,
            todayTotals = d.totals,
            defaultAccountId = default,
            composer = c,
            preview = preview,
            canSend = !c.busy && c.text.isNotBlank() && parsedNow && (preview.isNotEmpty() && preview.all { it.ok } || blocking && aiReady),
            willAskAi = blocking && aiReady,
            notice = notice,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, JournalUiState())

    private var parseJob: Job? = null

    init {
        // a renamed account or a new category changes how the line reads
        viewModelScope.launch { names.collect { reparse() } }
    }

    // ---------- the line ----------

    fun onText(text: String) {
        composer.update {
            val cleared = text.isBlank()
            it.copy(
                text = text,
                problem = null,
                overrides = if (cleared) emptyMap() else it.overrides,
                picker = if (cleared) null else it.picker,
                ai = if (it.ai is AiUi.Result || it.ai is AiUi.Failed) AiUi.Idle else it.ai,
            )
        }
        reparse()
    }

    private fun reparse() {
        val text = composer.value.text
        parseJob?.cancel()
        parseJob = viewModelScope.launch(Dispatchers.Default) {
            val n = names.value
            val parsed = if (text.isBlank()) emptyList() else QuickLine.parse(
                text,
                LineContext(n.active, n.categories.filter { !it.archived }, today.value, hint = ledger::hint),
            )
            val suggestions = parsed.singleOrNull()?.takeIf { it.draft.amount.isBlank() && it.draft.description.length >= 2 }
                ?.let { ledger.suggestions(it.draft.description) }.orEmpty()
            composer.update { c ->
                if (c.text != text) c else c.copy(
                    parsed = parsed,
                    parsedFor = text,
                    // a draft keeps its id while the line is edited, so sending twice records once
                    ids = List(parsed.size) { i -> c.ids.getOrNull(i) ?: UUID.randomUUID().toString() },
                    overrides = c.overrides.filterKeys { it < parsed.size },
                    picker = c.picker?.takeIf { it.index < parsed.size },
                    suggestions = suggestions,
                )
            }
        }
    }

    fun onSharedText(text: String) = onText(text.trim())

    fun onPick(picker: Picker?) = composer.update { it.copy(picker = if (it.picker == picker) null else picker) }

    fun onOverride(index: Int, change: (LineOverride) -> LineOverride) = composer.update {
        it.copy(overrides = it.overrides + (index to change(it.overrides[index] ?: LineOverride())), picker = null, problem = null)
    }

    fun onSuggestion(tx: Transaction) {
        val c = Currencies.of(tx.currency) ?: return
        val amount = Decimal.format(tx.amountMinor, c.exponent).let { if (Lang.current.decimal == '.') it else it.replace('.', Lang.current.decimal) }
        val code = if (names.value.account(tx.accountId)?.currency != tx.currency) " ${tx.currency}" else ""
        onText("${tx.description.orEmpty()} $amount$code".trim())
        onOverride(0) { it.copy(accountId = tx.accountId, category = tx.categoryId?.let { id -> CategoryChoice.Of(id) } ?: CategoryChoice.None) }
    }

    /** The drafts of the line as they will be recorded, overrides applied. */
    private fun drafts(): List<Draft> {
        val c = composer.value
        val n = names.value
        return c.parsed.mapIndexed { i, r -> Composer.apply(r, c.overrides[i], c.ids.getOrElse(i) { UUID.randomUUID().toString() }, n, today.value) }
    }

    /** What the full form starts with: the line's first operation, or nothing. */
    fun formDraft(): Draft {
        val c = composer.value
        val first = if (c.parsedFor == c.text) drafts().firstOrNull() else null
        return (first ?: Draft()).copy(origin = Origin.Form, id = UUID.randomUUID().toString())
    }

    /** The form recorded what the line had started: the line is done. */
    fun onFormRecorded() = clearLine()

    fun onSend() {
        val s = state.value
        if (!s.canSend) return
        if (s.willAskAi) {
            readWithAi(s.composer.text)
            return
        }
        val drafts = drafts()
        if (drafts.isEmpty()) return
        composer.update { it.copy(busy = true) }
        viewModelScope.launch {
            val results = ledger.saveAll(drafts)
            val invalid = results.singleOrNull() as? SaveResult.Invalid
            if (invalid != null) {
                composer.update { it.copy(busy = false, problem = invalid.problems.joinToString(" ") { p -> Texts.problem(p) }) }
                return@launch
            }
            val saved = results.filterIsInstance<SaveResult.Saved>()
            clearLine()
            announce(saved)
        }
    }

    private fun clearLine() = composer.update {
        ComposerState(summaryOpen = it.summaryOpen)
    }

    private fun announce(saved: List<SaveResult.Saved>) {
        val created = saved.filter { it.created }.map { it.tx }
        if (created.isEmpty()) return
        val what = if (created.size == 1) describe(created.single()) else ""
        notices.show(Notice(Texts.recorded(created.size, what), Texts.UNDO, undo = {
            created.forEach { ledger.delete(it.id) }
        }))
        _effects.trySend(JournalEffect.Recorded)
    }

    /** "кофе −4,50 €" for a notice. */
    fun describe(tx: Transaction): String {
        val n = names.value
        val title = tx.description ?: tx.payee ?: n.categoryPath(tx.categoryId) ?: Texts.type(tx.type)
        val sign = when (tx.type) {
            TxType.Expense -> -tx.amountMinor
            TxType.Adjustment -> tx.amountMinor
            else -> tx.amountMinor
        }
        return "$title ${Format.money(sign, tx.currency, withSign = tx.type == TxType.Income || tx.type == TxType.Refund)}"
    }

    // ---------- DeepSeek ----------

    private fun readWithAi(text: String) {
        composer.update { it.copy(ai = AiUi.Reading, picker = null) }
        viewModelScope.launch {
            val n = names.value
            val ctx = AiContext(today.value, clock.zone.id, Lang.en, n.accounts, n.categories, settings.choices.value.defaultCurrency)
            val next = try {
                val drafts = withContext(Dispatchers.IO) { ai.read(text, ctx) }
                AiUi.Result(drafts.map { aiItem(it) })
            } catch (e: AiFailure) {
                when (e) {
                    is AiFailure.NoKey -> AiUi.Failed(Texts.AI_NO_KEY, false)
                    is AiFailure.Auth -> AiUi.Failed(Texts.AI_AUTH + " (" + e.detail + ")", false)
                    else -> AiUi.Failed(Texts.aiFailed(e.detail), e.retryable)
                }
            }
            composer.update { if (it.text == text) it.copy(ai = next) else it }
        }
    }

    private fun aiItem(a: AiDraft): AiItem {
        val n = names.value
        val default = state.value.defaultAccountId
        val built = DraftBuilder.build(a.draft, n.refs(), default, ZonedDateTime.now(clock))
        val account = n.account(a.draft.accountId ?: default)
        val currency = a.draft.currency ?: account?.currency
        val minor = currency?.let { Currencies.of(it) }?.let { Decimal.parseHuman(a.draft.amount, it.exponent) }
        val amount = if (minor != null && currency != null) Format.money(minor, currency) else a.draft.amount
        val warnings = buildList {
            if (!a.amountInText) add(Texts.AI_AMOUNT_NOT_IN_TEXT)
            a.unknownAccount?.let { add(Texts.aiUnknownAccount(it)) }
            a.unknownCategory?.let { add(Texts.aiUnknownCategory(it)) }
        }
        val title = listOfNotNull(
            a.draft.description.ifBlank { null } ?: Texts.typeTitle(a.draft.type),
            n.categoryPath(a.draft.categoryId),
            account?.name,
            a.draft.date?.let { Format.shortDate(it, today.value) },
        ).joinToString(" · ")
        return AiItem(a.draft, title, amount, warnings, built.problems.map(Texts::problem))
    }

    fun onAiRetry() = readWithAi(composer.value.text)

    fun onAiClose() = composer.update { it.copy(ai = AiUi.Idle) }

    fun onAiRecord() {
        val result = composer.value.ai as? AiUi.Result ?: return
        composer.update { it.copy(busy = true) }
        viewModelScope.launch {
            val results = ledger.saveAll(result.items.map { it.draft })
            val invalid = results.singleOrNull() as? SaveResult.Invalid
            if (invalid != null) {
                composer.update { it.copy(busy = false, problem = invalid.problems.joinToString(" ") { p -> Texts.problem(p) }) }
                return@launch
            }
            clearLine()
            announce(results.filterIsInstance<SaveResult.Saved>())
        }
    }

    // ---------- the rest of the page ----------

    fun onToggleSummary() = composer.update { it.copy(summaryOpen = !it.summaryOpen) }

    fun onNeedMore() {
        if (state.value.hasMore) limit.update { it + PAGE }
    }

    fun onNotice(notice: Notice) {
        notices.dismiss(notice.id)
        val undo = notice.undo ?: return
        viewModelScope.launch {
            undo()
            notices.show(Notice(Texts.UNDONE))
        }
    }

    fun dismissNotice(id: Long) = notices.dismiss(id)

    /** Back in sight: the day may have changed while the app was away. */
    fun onForeground() {
        val now = LocalDate.now(clock)
        if (now != today.value) {
            today.value = now
            reparse()
        }
    }

    companion object {
        const val PAGE = 200
    }
}
