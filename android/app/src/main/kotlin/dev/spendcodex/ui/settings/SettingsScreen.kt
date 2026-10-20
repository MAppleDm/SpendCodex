package dev.spendcodex.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.spendcodex.coreui.Palette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.Hairline
import dev.spendcodex.coreui.Hint
import dev.spendcodex.coreui.OutlineButton
import dev.spendcodex.coreui.PageHeader
import dev.spendcodex.coreui.SectionCaption
import dev.spendcodex.coreui.StatRow
import dev.spendcodex.coreui.TextAction
import dev.spendcodex.coreui.Token
import dev.spendcodex.coreui.UnderlineField
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.ui.lock.LockSupport
import dev.spendcodex.ui.update.UpdateBlock
import dev.spendcodex.ui.update.UpdateState

/**
 * What the "⋮" opens: where the data goes first, then the journal's lists, then the app's own choices. There are no payment
 * methods: the journal counts what was spent, not what is left on a card or in cash.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    vm: SettingsViewModel,
    version: String,
    lockOn: Boolean,
    lockSupport: LockSupport,
    update: UpdateState,
    onBack: () -> Unit,
    onData: () -> Unit,
    onCategories: () -> Unit,
    onAi: () -> Unit,
    onToggleLock: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onErased: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val u = state.ui
    val c = AppTheme.colors
    Column(modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        PageHeader(Texts.SETTINGS, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            StatRow(Texts.DATA, "›", Modifier.testTag("settings-data"), onClick = onData)
            StatRow(Texts.CATEGORIES_AND_TAGS, "›", Modifier.testTag("settings-categories"), onClick = onCategories)
            Hairline()
            StatRow(Texts.LANGUAGE, Texts.LANGUAGE_NAME, Modifier.testTag("settings-language"), onClick = vm::onLanguage)
            val theme by vm.theme.collectAsState()
            StatRow(Texts.THEME, Texts.themeName(Palette.of(theme)), Modifier.testTag("settings-theme"), onClick = vm::onTheme)
            StatRow(Texts.DEFAULT_CURRENCY, state.choices.defaultCurrency ?: "—", Modifier.testTag("settings-currency"), onClick = vm::onToggleCurrency)
            if (u.currencyOpen) {
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (Currencies.common + Currencies.all.map { it.code }).distinct().forEach { code ->
                        Token(code, { vm.onCurrency(code) }, active = code == state.choices.defaultCurrency)
                    }
                }
            }
            StatRow(Texts.AI, if (state.hasKey) Texts.AI_KEY_SET else "›", Modifier.testTag("settings-ai"), onClick = onAi)
            Hairline()
            StatRow(Texts.LOCK, if (lockOn) Texts.ON else Texts.OFF, Modifier.testTag("settings-lock"),
                enabled = lockSupport == LockSupport.Ready || lockOn, onClick = onToggleLock)
            Hint(when (lockSupport) {
                LockSupport.Ready -> Texts.LOCK_HINT
                LockSupport.NotSet -> Texts.LOCK_NOT_SET
                LockSupport.Unavailable -> Texts.LOCK_UNAVAILABLE
            })
            Hairline(Modifier.padding(top = 8.dp))
            UpdateBlock(update, onCheckUpdates, onDownloadUpdate, onCancelUpdate, onInstallUpdate) { onOpenUrl(dev.spendcodex.ui.update.RELEASES_URL) }
            Hairline(Modifier.padding(top = 8.dp))
            SectionCaption(Texts.ABOUT)
            Hint(Texts.ABOUT_TEXT)
            Hint(Texts.version(version))
            StatRow(Texts.SOURCE, "↗", onClick = { onOpenUrl(dev.spendcodex.ui.update.SOURCE_URL) })
            Hairline(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Texts.ERASE_HINT, style = AppTheme.type.caption, modifier = Modifier.weight(1f))
                TextAction(if (u.eraseArmed) Texts.ERASE_CONFIRM else Texts.ERASE, { if (vm.onErase()) onErased() }, Modifier.testTag("settings-erase"), color = c.error)
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

/** DeepSeek: what it is for, what is sent, the key, the switch. */
@Composable
fun AiScreen(state: SettingsUiState, vm: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val u = state.ui
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().imePadding()) {
        PageHeader(Texts.AI_TITLE, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(Texts.AI_EXPLAIN, style = AppTheme.type.secondary, modifier = Modifier.padding(16.dp))
            SectionCaption(Texts.AI_KEY)
            if (state.hasKey) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Texts.AI_KEY_SET, style = AppTheme.type.body, modifier = Modifier.weight(1f))
                    TextAction(Texts.AI_KEY_CLEAR, vm::onClearKey, color = AppTheme.colors.error)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    UnderlineField(u.keyDraft, vm::onKeyDraft, Modifier.weight(1f).testTag("ai-key"), placeholder = "sk-…", secret = true, mono = true,
                        onImeAction = vm::onSaveKey)
                    TextAction(Texts.SAVE, vm::onSaveKey, Modifier.testTag("ai-key-save"), enabled = u.keyDraft.isNotBlank())
                }
            }
            Hint(Texts.AI_KEY_HINT)
            TextAction(Texts.AI_GET_KEY, { runCatching { uri.openUri("https://platform.deepseek.com/api_keys") } }, Modifier.padding(horizontal = 8.dp))
            u.keyMessage?.let { Hint(it, color = if (u.keyError) AppTheme.colors.error else AppTheme.colors.confirm) }
        }
    }
}

/** The first start: one choice, the main currency; or a backup brought from another phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(state: SettingsUiState, vm: SettingsViewModel, onRestore: () -> Unit, restore: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val u = state.ui
    val chosen = u.setupCurrency ?: vm.suggestedCurrency()
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextAction(if (state.choices.language.code == "ru") "English" else "Русский", vm::onLanguage, color = AppTheme.colors.secondary)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(Texts.APP, style = AppTheme.type.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)),
                modifier = Modifier.padding(start = 16.dp, top = 24.dp))
            Text(Texts.SETUP_TAGLINE, style = AppTheme.type.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SectionCaption(Texts.SETUP_CURRENCY)
            val codes = (listOf(vm.suggestedCurrency()) + Currencies.common).distinct()
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                codes.forEach { code -> Token(code + (Currencies.of(code)?.symbol?.let { " $it" } ?: ""), { vm.onSetupCurrency(code) }, active = code == chosen, modifier = Modifier.testTag("setup-$code")) }
                TextAction(Texts.OTHER_CURRENCIES, vm::onSetupAll, color = AppTheme.colors.secondary)
            }
            if (u.setupAll) {
                FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Currencies.all.map { it.code }.filter { it !in codes }.forEach { code -> Token(code, { vm.onSetupCurrency(code) }, active = code == chosen) }
                }
            }
            Hint(Texts.SETUP_CURRENCY_HINT)
            OutlineButton(Texts.START, vm::onStart, Modifier.padding(16.dp).testTag("setup-start"))
            Hairline(Modifier.padding(top = 16.dp))
            TextAction(Texts.SETUP_RESTORE, onRestore, Modifier.padding(horizontal = 8.dp).testTag("setup-restore"), color = AppTheme.colors.secondary)
            restore()
        }
    }
}
