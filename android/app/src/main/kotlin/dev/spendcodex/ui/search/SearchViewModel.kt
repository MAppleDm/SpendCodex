package dev.spendcodex.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spendcodex.data.db.FeedRow
import dev.spendcodex.data.db.TypeTotal
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.ui.journal.Names
import dev.spendcodex.ui.journal.JournalViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class SearchField { Period, Types, Accounts, Categories, Tags, Currencies }

enum class Period { All, ThisMonth, LastMonth, ThisYear, Last12, Custom }

data class SearchUiState(
    val filter: Filter = Filter(),
    val period: Period = Period.All,
    val fromText: String = "",
    val toText: String = "",
    val open: SearchField? = null,
    val rows: List<FeedRow> = emptyList(),
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val totals: List<TypeTotal> = emptyList(),
    val names: Names = Names(emptyList(), emptyList(), emptyList()),
    val today: LocalDate = LocalDate.now(),
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val ledger: Ledger, private val clock: Clock) : ViewModel() {
    private val ui = MutableStateFlow(SearchUiState())
    private val limit = MutableStateFlow(PAGE)
    private var openedFor: String? = null

    // typing waits a moment before the query runs; every other change runs at once
    private val filter = ui.map { it.filter }.distinctUntilChanged().debounce { if (it.text.isNotEmpty()) 150L else 0L }
    // Default search follows the expense journal. Explicit legacy type filters can still recover older records.
    private fun expenseFilter(f: Filter) = if (f.types.isEmpty()) f.copy(types = JournalViewModel.EXPENSE_TYPES) else f
    private data class Matches(val filter: Filter, val rows: List<FeedRow>, val totals: List<TypeTotal>)
    private val matches = combine(filter, limit) { f, l -> f to l }.flatMapLatest { (f, l) ->
        combine(ledger.feed(expenseFilter(f), l), ledger.totals(expenseFilter(f))) { r, t -> Matches(f, r, t) }
    }
    fun exportFilter(): Filter = expenseFilter(ui.value.filter)
    private val names = combine(ledger.accounts, ledger.categories, ledger.tags) { a, c, t -> Names(a, c, t) }

    val state: StateFlow<SearchUiState> = combine(ui, matches, names) { u, m, n ->
        val ready = m.filter == u.filter
        u.copy(rows = if (ready) m.rows else emptyList(), loaded = ready, hasMore = ready && m.rows.size >= limit.value,
            totals = if (ready) m.totals else emptyList(), names = n, today = LocalDate.now(clock))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    /** Opens the page with [start] (empty, or an account from the journal's balances); a rotation keeps what was set. */
    fun open(key: String, start: Filter) {
        if (openedFor == key) return
        openedFor = key
        limit.value = PAGE
        ui.value = SearchUiState(filter = start)
    }

    fun onText(text: String) = setFilter { it.copy(text = text) }

    fun onOpen(field: SearchField?) = ui.update { it.copy(open = if (it.open == field) null else field) }

    fun onPeriod(period: Period) {
        val today = LocalDate.now(clock)
        val (from, to) = when (period) {
            Period.All -> null to null
            Period.ThisMonth -> today.withDayOfMonth(1) to null
            Period.LastMonth -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
            Period.ThisYear -> today.withDayOfYear(1) to null
            Period.Last12 -> today.minusMonths(12).plusDays(1) to null
            Period.Custom -> ui.value.filter.from to ui.value.filter.to
        }
        ui.update {
            it.copy(period = period, filter = it.filter.copy(from = from, to = to),
                fromText = from?.let(dev.spendcodex.Format::numericDate).orEmpty(), toText = to?.let(dev.spendcodex.Format::numericDate).orEmpty())
        }
        limit.value = PAGE
    }

    fun onFromText(text: String) = ui.update {
        it.copy(fromText = text, period = Period.Custom, filter = it.filter.copy(from = if (text.isBlank()) null else dev.spendcodex.Format.parseNumericDate(text) ?: it.filter.from))
    }

    fun onToText(text: String) = ui.update {
        it.copy(toText = text, period = Period.Custom, filter = it.filter.copy(to = if (text.isBlank()) null else dev.spendcodex.Format.parseNumericDate(text) ?: it.filter.to))
    }

    fun onType(type: TxType) = setFilter { it.copy(types = it.types.toggle(type)) }
    fun onAccount(id: String) = setFilter { it.copy(accountIds = it.accountIds.toggle(id)) }
    fun onCategory(id: String) = setFilter { it.copy(categoryIds = it.categoryIds.toggle(id)) }
    fun onTag(id: String) = setFilter { it.copy(tagIds = it.tagIds.toggle(id)) }
    fun onCurrency(code: String) = setFilter { it.copy(currencies = it.currencies.toggle(code)) }

    fun onReset() {
        ui.update { SearchUiState() }
        limit.value = PAGE
    }

    fun onNeedMore() {
        if (state.value.hasMore) limit.update { it + PAGE }
    }

    private fun setFilter(change: (Filter) -> Filter) {
        ui.update { it.copy(filter = change(it.filter)) }
        limit.value = PAGE
    }

    private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

    companion object {
        const val PAGE = 200
    }
}
