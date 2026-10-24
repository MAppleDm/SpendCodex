package dev.spendcodex.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spendcodex.coreui.Palette
import dev.spendcodex.Texts
import dev.spendcodex.data.backup.DocAssets
import dev.spendcodex.data.domain.Currencies
import dev.spendcodex.data.log.AppLog
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.settings.Choices
import dev.spendcodex.data.settings.Language
import dev.spendcodex.data.settings.SecretStore
import dev.spendcodex.data.settings.Settings
import dev.spendcodex.ui.shareFile
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUi(
    val currencyOpen: Boolean = false,
    val eraseArmed: Boolean = false,
    val keyDraft: String = "",
    val keyMessage: String? = null,
    val keyError: Boolean = false,
    /** The first start: the currency being chosen. */
    val setupCurrency: String? = null,
    val setupAll: Boolean = false,
    /** The logs are being packed into a .zip; [logsFailed]: the last try did not work. */
    val logsBusy: Boolean = false,
    val logsFailed: Boolean = false,
)

data class SettingsUiState(
    val ui: SettingsUi = SettingsUi(),
    val choices: Choices = Choices(Language.Ru, null, null, false),
    val hasKey: Boolean = false,
    val setupDone: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: Settings,
    private val secrets: SecretStore,
    private val ledger: Ledger,
    private val docs: DocAssets,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val ui = MutableStateFlow(SettingsUi())

    val state: StateFlow<SettingsUiState> = combine(ui, settings.choices, secrets.hasKey, settings.setupDone) { u, c, k, s ->
        SettingsUiState(u, c, k, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(setupDone = settings.setupDone.value))

    val formatText: String by lazy { runCatching { docs.format }.getOrDefault("") }

    fun onLanguage() = settings.setLanguage(if (settings.choices.value.language == Language.Ru) Language.En else Language.Ru)

    /** The look of the app on this phone. */
    val theme: StateFlow<String> = settings.theme

    fun onTheme() = settings.setTheme(if (Palette.of(settings.theme.value) == Palette.Avocado) Palette.Mono.code else Palette.Avocado.code)

    fun onToggleCurrency() = ui.update { it.copy(currencyOpen = !it.currencyOpen) }
    fun onCurrency(code: String) {
        settings.setDefaultCurrency(code)
        ui.update { it.copy(currencyOpen = false) }
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
        if (ok) settings.setAiEnabled(true)
        ui.update { it.copy(keyDraft = "", keyMessage = if (ok) Texts.AI_KEY_SET else Texts.AI_KEY_STORE_FAILED, keyError = !ok) }
    }

    fun onClearKey() {
        secrets.clear()
        settings.setAiEnabled(false)
        ui.update { it.copy(keyMessage = null) }
    }

    // ---------- logs ----------

    suspend fun logsSize(): Long = withContext(Dispatchers.IO) { AppLog.size() }

    /** Every log file, with what the phone is and the app's choices (never the key), as one .zip to send. */
    fun onSendLogs() {
        if (ui.value.logsBusy) return
        ui.update { it.copy(logsBusy = true, logsFailed = false) }
        viewModelScope.launch {
            AppLog.i(TAG, "sending the logs")
            val ok = try {
                shareFile(context, LogReport.fileName(), "application/zip", Texts.SHARE_TITLE) { out ->
                    withContext(Dispatchers.IO) { AppLog.zip(out, LogReport.extras(context, settings.choices.value, settings.theme.value, secrets.hasKey.value)) }
                }
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.e(TAG, "packing the logs failed", e); false }
            ui.update { it.copy(logsBusy = false, logsFailed = !ok) }
        }
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
            // what was typed and read is in the logs too
            withContext(Dispatchers.IO) { AppLog.clear() }
            AppLog.i(TAG, "erased everything")
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

    private companion object { const val TAG = "Settings" }

    fun onStart() {
        val code = ui.value.setupCurrency ?: suggestedCurrency()
        viewModelScope.launch {
            ledger.seed(settings.choices.value.language, code)
            settings.setSetupDone(true)
        }
    }
}
