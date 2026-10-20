package dev.spendcodex.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spendcodex.Texts
import dev.spendcodex.data.backup.DocAssets
import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.settings.Choices
import dev.spendcodex.data.settings.Language
import dev.spendcodex.data.settings.SecretStore
import dev.spendcodex.data.settings.Settings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUi(
    val currencyOpen: Boolean = false,
    val accountOpen: Boolean = false,
    val eraseArmed: Boolean = false,
    val keyDraft: String = "",
    val keyMessage: String? = null,
    val keyError: Boolean = false,
    /** The first start: the currency being chosen. */
    val setupCurrency: String? = null,
    val setupAll: Boolean = false,
)

data class SettingsUiState(
    val ui: SettingsUi = SettingsUi(),
    val choices: Choices = Choices(Language.Ru, null, null, false),
    val hasKey: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val setupDone: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: Settings,
    private val secrets: SecretStore,
    private val ledger: Ledger,
    private val docs: DocAssets,
) : ViewModel() {
    private val ui = MutableStateFlow(SettingsUi())

    val state: StateFlow<SettingsUiState> = combine(ui, settings.choices, secrets.hasKey, ledger.accounts, settings.setupDone) { u, c, k, a, s ->
        SettingsUiState(u, c, k, a, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(setupDone = settings.setupDone.value))

    val formatText: String by lazy { runCatching { docs.format }.getOrDefault("") }

    fun onLanguage() = settings.setLanguage(if (settings.choices.value.language == Language.Ru) Language.En else Language.Ru)

    fun onToggleCurrency() = ui.update { it.copy(currencyOpen = !it.currencyOpen, accountOpen = false) }
    fun onCurrency(code: String) {
        settings.setDefaultCurrency(code)
        ui.update { it.copy(currencyOpen = false) }
    }

    fun onToggleAccount() = ui.update { it.copy(accountOpen = !it.accountOpen, currencyOpen = false) }
    fun onAccount(id: String) {
        settings.setDefaultAccount(id)
        ui.update { it.copy(accountOpen = false) }
    }

    // ---------- DeepSeek ----------

    fun onKeyDraft(text: String) = ui.update { it.copy(keyDraft = text, keyMessage = null) }

    fun onSaveKey() {
        val key = ui.value.keyDraft.trim()
        if (key.length < 10 || key.any { it.isWhitespace() }) {
            ui.update { it.copy(keyMessage = Texts.AI_KEY_BAD, keyError = true) }
            return
        }
        val ok = runCatching { secrets.saveKey(key) }.isSuccess
        ui.update { it.copy(keyDraft = "", keyMessage = if (ok) Texts.AI_KEY_SET else Texts.AI_KEY_STORE_FAILED, keyError = !ok) }
    }

    fun onClearKey() {
        secrets.clear()
        settings.setAiEnabled(false)
        ui.update { it.copy(keyMessage = null) }
    }

    fun onToggleAi() {
        val on = settings.choices.value.aiEnabled
        if (!on && !secrets.hasKey.value) {
            ui.update { it.copy(keyMessage = Texts.AI_NEEDS_KEY, keyError = true) }
            return
        }
        settings.setAiEnabled(!on)
    }

    // ---------- erase ----------

    /** The first tap arms the button; the second erases. True when erased. */
    fun onErase(): Boolean {
        if (!ui.value.eraseArmed) {
            ui.update { it.copy(eraseArmed = true) }
            return false
        }
        viewModelScope.launch {
            ledger.eraseAll()
            secrets.clear()
            settings.reset()
            ui.value = SettingsUi()
        }
        return true
    }

    fun onDisarm() = ui.update { it.copy(eraseArmed = false) }

    // ---------- first start ----------

    fun onSetupCurrency(code: String) = ui.update { it.copy(setupCurrency = code) }
    fun onSetupAll() = ui.update { it.copy(setupAll = !it.setupAll) }

    /** The proposed main currency: what a phone in this country most likely uses. */
    fun suggestedCurrency(): String = runCatching {
        java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode
    }.getOrNull()?.takeIf { Currencies.of(it) != null } ?: if (settings.choices.value.language == Language.Ru) "RUB" else "EUR"

    fun onStart() {
        val code = ui.value.setupCurrency ?: suggestedCurrency()
        viewModelScope.launch {
            ledger.seed(settings.choices.value.language, code)
            settings.setSetupDone(true)
        }
    }
}
