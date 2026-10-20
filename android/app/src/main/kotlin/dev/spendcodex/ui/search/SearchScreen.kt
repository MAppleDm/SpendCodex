package dev.spendcodex.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.BackIcon
import dev.spendcodex.coreui.ChoiceRow
import dev.spendcodex.coreui.DateRow
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.Hint
import dev.spendcodex.coreui.IconAction
import dev.spendcodex.coreui.SectionCaption
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.Token
import dev.spendcodex.coreui.UnderlineField
import dev.spendcodex.data.db.TypeTotal
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.ui.journal.FeedItem
import dev.spendcodex.ui.journal.OperationRow
import dev.spendcodex.ui.journal.feedItems
import kotlinx.coroutines.flow.distinctUntilChanged

/** Finding any record: a few words and any mix of filters; the found set can go straight to an export. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(state: SearchUiState, vm: SearchViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onExport: (Filter) -> Unit, modifier: Modifier = Modifier) {
    val f = state.filter
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (f.isEmpty) runCatching { focus.requestFocus() } }
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            UnderlineField(f.text, vm::onText, Modifier.weight(1f).padding(start = 4.dp).testTag("search-text"), placeholder = Texts.SEARCH_PLACEHOLDER,
                imeAction = ImeAction.Search, focusRequester = focus)
        }
        // the filters
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fun label(base: String, n: Int) = if (n == 0) base else "$base · $n"
            Token(if (f.from == null && f.to == null) Texts.PERIOD else Format.range(f.from, f.to) ?: Texts.PERIOD, { vm.onOpen(SearchField.Period) },
                weak = f.from == null && f.to == null, active = state.open == SearchField.Period, modifier = Modifier.testTag("filter-period"))
            Token(label(Texts.TYPES, f.types.size), { vm.onOpen(SearchField.Types) }, weak = f.types.isEmpty(), active = state.open == SearchField.Types, modifier = Modifier.testTag("filter-types"))
            Token(label(Texts.CATEGORIES, f.categoryIds.size), { vm.onOpen(SearchField.Categories) }, weak = f.categoryIds.isEmpty(), active = state.open == SearchField.Categories, modifier = Modifier.testTag("filter-categories"))
            if (state.names.tags.isNotEmpty()) {
                Token(label(Texts.TAGS, f.tagIds.size), { vm.onOpen(SearchField.Tags) }, weak = f.tagIds.isEmpty(), active = state.open == SearchField.Tags, modifier = Modifier.testTag("filter-tags"))
            }
            Token(label(Texts.CURRENCIES, f.currencies.size), { vm.onOpen(SearchField.Currencies) }, weak = f.currencies.isEmpty(), active = state.open == SearchField.Currencies, modifier = Modifier.testTag("filter-currencies"))
            if (!f.isEmpty) TextAction(Texts.RESET, vm::onReset, color = AppTheme.colors.secondary)
        }
        state.open?.let { Options(it, state, vm) }
        Hairline()
        // what was found
        if (state.loaded && !f.isEmpty) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(summary(state.totals), style = AppTheme.type.caption.copy(fontFeatureSettings = "tnum"), modifier = Modifier.weight(1f).padding(vertical = 8.dp).testTag("search-summary"))
                if (state.rows.isNotEmpty()) TextAction(Texts.EXPORT_FOUND, { onExport(vm.exportFilter()) }, Modifier.testTag("search-export"))
            }
        }
        val items = remember(state.rows) { feedItems(state.rows, headingFirst = true) }
        val listState = rememberLazyListState()
        LaunchedEffect(listState, items.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged()
                .collect { if (it >= items.size - 8) vm.onNeedMore() }
        }
        when {
            f.isEmpty -> Hint(Texts.SEARCH_HINT, Modifier.padding(top = 8.dp))
            state.loaded && state.rows.isEmpty() -> Hint(Texts.NOTHING_FOUND, Modifier.padding(top = 8.dp).testTag("nothing-found"))
        }
        if (!f.isEmpty) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().testTag("search-results"), state = listState) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is FeedItem.Op -> OperationRow(item.row, onClick = { onOpen(item.row.id) })
                        is FeedItem.Day -> DateRow(Format.dayTitle(item.date, state.today))
                    }
                }
            }
        }
    }
}

/** "42 операции · расходы −1 230,00 € · −15 000 ₽ · доходы +500,00 €": per currency, never added across them. */
private fun summary(totals: List<TypeTotal>): String {
    val count = totals.sumOf { it.count }
    fun part(type: TxType, label: String, sign: Int) = totals.filter { it.type == type.code && it.total != 0L }.sortedBy { it.currency }
        .map { Format.money(sign * it.total, it.currency, withSign = sign > 0) }.takeIf { it.isNotEmpty() }?.let { "$label ${it.joinToString(" · ")}" }
    return listOfNotNull(Texts.found(count), part(TxType.Expense, Texts.SPENT, -1), part(TxType.Refund, Texts.REFUNDED, 1), part(TxType.Income, Texts.EARNED, 1))
        .joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(field: SearchField, state: SearchUiState, vm: SearchViewModel) {
    val f = state.filter
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).testTag("filter-options")) {
        Hairline()
        when (field) {
            SearchField.Period -> {
                listOf(Period.All to Texts.ALL_TIME, Period.ThisMonth to Texts.THIS_MONTH, Period.LastMonth to Texts.LAST_MONTH,
                    Period.ThisYear to Texts.THIS_YEAR, Period.Last12 to Texts.LAST_12, Period.Custom to Texts.CUSTOM_PERIOD).forEach { (p, label) ->
                    ChoiceRow(label, state.period == p, { vm.onPeriod(p) })
                }
                if (state.period == Period.Custom) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Texts.FROM, style = AppTheme.type.secondary)
                        UnderlineField(state.fromText, vm::onFromText, Modifier.width(128.dp).padding(horizontal = 8.dp).testTag("period-from"),
                            placeholder = Texts.DATE_HINT, keyboardType = KeyboardType.Number, mono = true)
                        Text(Texts.TO, style = AppTheme.type.secondary)
                        UnderlineField(state.toText, vm::onToText, Modifier.width(128.dp).padding(horizontal = 8.dp).testTag("period-to"),
                            placeholder = Texts.DATE_HINT, keyboardType = KeyboardType.Number, mono = true)
                    }
                }
            }
            SearchField.Types -> {
                listOf(TxType.Expense, TxType.Refund).forEach { t -> ChoiceRow(Texts.typeTitle(t), t in f.types, { vm.onType(t) }) }
                SectionCaption(Texts.LEGACY_TYPES)
                listOf(TxType.Income, TxType.Transfer, TxType.Adjustment).forEach { t -> ChoiceRow(Texts.typeTitle(t), t in f.types, { vm.onType(t) }) }
            }
            SearchField.Categories -> listOf(CategoryKind.Expense, CategoryKind.Income).forEach { kind ->
                val all = state.names.categories.filter { it.kind == kind }
                all.filter { it.parentId == null }.forEach { parent ->
                    ChoiceRow(parent.name, parent.id in f.categoryIds, { vm.onCategory(parent.id) }, caption = if (kind == CategoryKind.Income) Texts.INCOMES.lowercase() else null)
                    all.filter { it.parentId == parent.id }.forEach { child -> ChoiceRow("    " + child.name, child.id in f.categoryIds, { vm.onCategory(child.id) }) }
                }
            }
            SearchField.Tags -> state.names.tags.forEach { t -> ChoiceRow("#" + t.name, t.id in f.tagIds, { vm.onTag(t.id) }) }
            SearchField.Currencies -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
                val codes = (state.names.accounts.map { it.currency } + state.totals.map { it.currency } + f.currencies).distinct().sorted()
                codes.forEach { code -> Token(code, { vm.onCurrency(code) }, active = code in f.currencies) }
            }
        }
        Spacer(Modifier.padding(bottom = 4.dp))
    }
}
