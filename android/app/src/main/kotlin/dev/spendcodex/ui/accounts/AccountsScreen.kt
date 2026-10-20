package dev.spendcodex.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.ChevronIcon
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.Hint
import dev.spendcodex.coreui.PageHeader
import dev.spendcodex.coreui.SectionCaption
import dev.spendcodex.coreui.StatRow
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.Token
import dev.spendcodex.coreui.UnderlineField
import dev.spendcodex.coreui.tap
import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.Currencies

@Composable
fun AccountsScreen(state: AccountsUiState, vm: AccountsViewModel, onBack: () -> Unit, onOpen: (String?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        PageHeader(Texts.ACCOUNTS, onBack) { TextAction(Texts.ADD_ACCOUNT, { onOpen(null) }, Modifier.testTag("add-account")) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val (archived, active) = state.accounts.partition { it.archived }
            active.forEach { AccountRow(it, state, onOpen) }
            if (archived.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().tap(onClick = vm::onToggleArchived).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${Texts.ARCHIVED} (${archived.size})", style = AppTheme.type.secondary, modifier = Modifier.weight(1f))
                    ChevronIcon(AppTheme.colors.secondary, up = state.archivedOpen)
                }
                if (state.archivedOpen) archived.forEach { AccountRow(it, state, onOpen) }
            }
        }
    }
}

@Composable
private fun AccountRow(a: Account, state: AccountsUiState, onOpen: (String?) -> Unit) {
    val b = state.balances[a.id]
    val t = AppTheme.type
    Row(Modifier.fillMaxWidth().tap { onOpen(a.id) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("account-row"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.name, style = t.body.copy(color = if (a.archived) AppTheme.colors.tertiary else AppTheme.colors.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val caption = listOfNotNull(
                Texts.accountKind(a.kind), a.currency,
                Texts.IS_DEFAULT.takeIf { a.id == state.defaultAccountId },
                b?.takeIf { !it.openingKnown }?.let { Texts.BALANCE_NO_OPENING },
                b?.takeIf { !it.complete }?.let { Texts.balanceIncomplete(it.unconverted) },
            ).joinToString(" · ")
            Text(caption, style = t.caption, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        Text((if (b?.complete == false) "≈ " else "") + Format.money(b?.minor ?: 0, a.currency), style = t.mono)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountEditScreen(state: AccountsUiState, vm: AccountsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val f = state.form
    val t = AppTheme.type
    val balance = f.id?.let { state.balances[it] }
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().imePadding()) {
        PageHeader(if (f.id == null) Texts.NEW_ACCOUNT else f.name.ifBlank { Texts.ACCOUNT }, onBack) {
            TextAction(Texts.SAVE, vm::onSave, Modifier.testTag("account-save"))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                UnderlineField(f.name, { v -> vm.edit { it.copy(name = v) } }, Modifier.testTag("account-name"), placeholder = Texts.NAME,
                    imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences)
            }
            SectionCaption(Texts.KIND)
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AccountKind.entries.forEach { k -> Token(Texts.accountKind(k), { vm.edit { it.copy(kind = k) } }, active = f.kind == k) }
            }
            SectionCaption(Texts.CURRENCY)
            if (f.used) {
                StatRow(Texts.CURRENCY, f.currency, enabled = false)
                Hint(Texts.CURRENCY_LOCKED)
            } else {
                StatRow(Texts.CURRENCY, f.currency, Modifier.testTag("account-currency"), onClick = { vm.edit { it.copy(currencyOpen = !it.currencyOpen) } })
                if (f.currencyOpen) {
                    FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (Currencies.common + Currencies.all.map { it.code }).distinct().forEach { code ->
                            Token(code, { vm.edit { it.copy(currency = code, currencyOpen = false) } }, active = code == f.currency)
                        }
                    }
                }
            }
            SectionCaption(Texts.OPENING)
            Column(Modifier.padding(horizontal = 16.dp)) {
                UnderlineField(f.opening, { v -> vm.edit { it.copy(opening = v) } }, Modifier.testTag("account-opening"), placeholder = "—",
                    keyboardType = KeyboardType.Decimal, mono = true)
            }
            Hint(Texts.OPENING_HINT)
            f.error?.let { Hint(it, Modifier.testTag("account-error"), color = AppTheme.colors.error) }
            if (f.id != null) {
                Hairline(Modifier.padding(top = 12.dp))
                if (!f.archived) {
                    StatRow(Texts.DEFAULT_ACCOUNT, if (state.defaultAccountId == f.id) Texts.YES else Texts.MAKE_DEFAULT,
                        enabled = state.defaultAccountId != f.id, onClick = vm::onMakeDefault)
                    SectionCaption(Texts.RECONCILE)
                    balance?.let { Hint(Texts.nowBalance(Format.money(it.minor, f.currency))) }
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        UnderlineField(f.reconcile, { v -> vm.edit { it.copy(reconcile = v) } }, Modifier.weight(1f).testTag("account-reconcile"),
                            placeholder = "0", keyboardType = KeyboardType.Decimal, mono = true)
                        TextAction(Texts.RECONCILE_DO, vm::onReconcile, Modifier.testTag("account-reconcile-do"), enabled = f.reconcile.isNotBlank())
                    }
                    Hint(Texts.RECONCILE_HINT)
                }
                f.message?.let { Hint(it, Modifier.testTag("account-message")) }
                Hairline(Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextAction(if (f.archived) Texts.UNARCHIVE else Texts.ARCHIVE, vm::onArchive, Modifier.testTag("account-archive"))
                    Spacer(Modifier.weight(1f))
                    TextAction(Texts.DELETE, vm::onDelete, Modifier.testTag("account-delete"), color = if (f.used) AppTheme.colors.tertiary else AppTheme.colors.error)
                }
                if (f.used) Hint(Texts.DELETE_LOCKED)
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}
