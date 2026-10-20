package dev.spendcodex.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.repo.AccountEdit
import dev.spendcodex.data.repo.EditResult
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.settings.Settings
import dev.spendcodex.ui.Notice
import dev.spendcodex.ui.Notices
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

/** An account being edited, as typed. */
data class AccountForm(
    val key: String? = null,
    val id: String? = null,
    val name: String = "",
    val kind: AccountKind = AccountKind.Card,
    val currency: String = "",
    val opening: String = "",
    val used: Boolean = false,
    val archived: Boolean = false,
    val currencyOpen: Boolean = false,
    val reconcile: String = "",
    val error: String? = null,
    val message: String? = null,
)

data class AccountsUiState(
    val accounts: List<Account> = emptyList(),
    val defaultAccountId: String? = null,
    val form: AccountForm = AccountForm(),
    val archivedOpen: Boolean = false,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val ledger: Ledger,
    private val settings: Settings,
    private val notices: Notices,
) : ViewModel() {
    private val form = MutableStateFlow(AccountForm())
    private val archivedOpen = MutableStateFlow(false)
    private val _closed = Channel<Unit>(Channel.BUFFERED)
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    val state: StateFlow<AccountsUiState> = combine(ledger.accounts, settings.choices, form, archivedOpen) { a, c, f, open ->
        val default = c.defaultAccountId?.takeIf { id -> a.any { it.id == id && !it.archived } } ?: a.firstOrNull { !it.archived }?.id
        AccountsUiState(a, default, f, open)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountsUiState())

    fun onToggleArchived() = archivedOpen.update { !it }

    fun open(key: String, id: String?) {
        if (form.value.key == key) return
        form.value = AccountForm(key = key, currency = settings.choices.value.defaultCurrency ?: "EUR")
        if (id == null) return
        viewModelScope.launch {
            val a = ledger.accountsNow().firstOrNull { it.id == id } ?: return@launch
            val used = ledger.feedNow(dev.spendcodex.data.domain.Filter(accountIds = setOf(id)), 1).isNotEmpty()
            form.update {
                if (it.key != key) it else it.copy(
                    id = a.id, name = a.name, kind = a.kind, currency = a.currency,
                    opening = a.openingMinor?.let { m -> Format.plain(m, a.currency) }.orEmpty(), used = used, archived = a.archived,
                )
            }
        }
    }

    fun edit(change: (AccountForm) -> AccountForm) = form.update { change(it).copy(error = null, message = null) }

    fun onSave() {
        val f = form.value
        viewModelScope.launch {
            when (val r = ledger.saveAccount(AccountEdit(f.id, f.name, f.kind, f.currency, f.opening))) {
                is EditResult.Error -> form.update { it.copy(error = Texts.editError(r.error)) }
                is EditResult.Ok -> {
                    // the first account of a journal becomes its default
                    if (settings.choices.value.defaultAccountId == null) settings.setDefaultAccount(r.id)
                    notices.show(Notice(Texts.SAVED))
                    form.value = AccountForm()
                    _closed.send(Unit)
                }
            }
        }
    }

    fun onMakeDefault() {
        form.value.id?.let { settings.setDefaultAccount(it) }
    }

    fun onArchive() {
        val f = form.value
        val id = f.id ?: return
        viewModelScope.launch {
            ledger.setAccountArchived(id, !f.archived)
            form.update { it.copy(archived = !f.archived) }
        }
    }

    fun onDelete() {
        val id = form.value.id ?: return
        viewModelScope.launch {
            when (val r = ledger.deleteAccount(id)) {
                is EditResult.Error -> form.update { it.copy(error = Texts.editError(r.error)) }
                is EditResult.Ok -> {
                    form.value = AccountForm()
                    _closed.send(Unit)
                }
            }
        }
    }

}
