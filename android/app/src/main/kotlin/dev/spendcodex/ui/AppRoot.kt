package dev.spendcodex.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spendcodex.BuildConfig
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.data.backup.ExportScope
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.ui.accounts.AccountEditScreen
import dev.spendcodex.ui.accounts.AccountsScreen
import dev.spendcodex.ui.accounts.AccountsViewModel
import dev.spendcodex.ui.categories.CategoriesScreen
import dev.spendcodex.ui.categories.CategoriesViewModel
import dev.spendcodex.ui.categories.CategoryEditScreen
import dev.spendcodex.ui.categories.TagEditScreen
import dev.spendcodex.ui.data.DataScreen
import dev.spendcodex.ui.data.DataViewModel
import dev.spendcodex.ui.data.FormatDocScreen
import dev.spendcodex.ui.data.Restore
import dev.spendcodex.ui.journal.AiUi
import dev.spendcodex.ui.journal.JournalScreen
import dev.spendcodex.ui.journal.JournalViewModel
import dev.spendcodex.ui.lock.Biometrics
import dev.spendcodex.ui.lock.LockSupport
import dev.spendcodex.ui.lock.LockViewModel
import dev.spendcodex.ui.lock.PromptResult
import dev.spendcodex.ui.lock.fragmentActivity
import dev.spendcodex.ui.operation.OperationEffect
import dev.spendcodex.ui.operation.OperationScreen
import dev.spendcodex.ui.operation.OperationViewModel
import dev.spendcodex.ui.search.SearchScreen
import dev.spendcodex.ui.search.SearchViewModel
import dev.spendcodex.ui.settings.AiScreen
import dev.spendcodex.ui.settings.SettingsScreen
import dev.spendcodex.ui.settings.SettingsViewModel
import dev.spendcodex.ui.settings.SetupScreen
import dev.spendcodex.ui.update.UpdateInstaller
import dev.spendcodex.ui.update.UpdateProblem
import dev.spendcodex.ui.update.UpdateViewModel

/** The screens of the app and how one leads to another. */
class Screens(
    val nav: NavViewModel,
    val journal: JournalViewModel,
    val operation: OperationViewModel,
    val search: SearchViewModel,
    val accounts: AccountsViewModel,
    val categories: CategoriesViewModel,
    val data: DataViewModel,
    val settings: SettingsViewModel,
    val lock: LockViewModel,
    val updates: UpdateViewModel,
)

/** One activity, one root: the first start until a currency is chosen (or a backup restored), then the journal and its pages. */
@Composable
fun AppRoot(s: Screens) {
    val settingsState by s.settings.state.collectAsStateWithLifecycle()
    val dataState by s.data.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        if (!settingsState.setupDone) {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { s.data.onRestorePicked(it) }
            SetupScreen(settingsState, s.settings,
                onRestore = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                restore = { Restore(dataState.ui.restore, s.data) })
        } else {
            Pages(s)
        }
    }
}

@Composable
private fun Pages(s: Screens) {
    val stack by s.nav.stack.collectAsStateWithLifecycle()
    val top = stack.last()
    val journal by s.journal.state.collectAsStateWithLifecycle()

    // pages that finish by themselves go back to where they were opened from
    LaunchedEffect(Unit) {
        s.operation.effects.collect { e ->
            if (e is OperationEffect.Close) {
                if (e.recordedFromLine) s.journal.onFormRecorded()
                s.nav.back()
            }
        }
    }
    LaunchedEffect(Unit) { s.accounts.closed.collect { s.nav.back() } }
    LaunchedEffect(Unit) { s.categories.closed.collect { s.nav.back() } }

    fun newOperation() {
        val fromLine = journal.composer.text.isNotBlank()
        s.nav.push(Screen.Operation(OperationRequest.New(s.journal.formDraft()), fromLine))
    }

    // back closes the innermost thing first: a list of choices, DeepSeek's answer, then the page
    BackHandler(enabled = stack.size > 1 || journal.composer.ai !is AiUi.Idle) {
        when {
            stack.size > 1 -> s.nav.back()
            else -> s.journal.onAiClose()
        }
    }

    val shortcuts = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            e.isCtrlPressed && e.key == Key.F -> { if (top !is Screen.Search) s.nav.push(Screen.Search()); true }
            e.isCtrlPressed && e.key == Key.N -> { if (top == Screen.Journal) newOperation(); true }
            e.isCtrlPressed && e.key == Key.S && top is Screen.Operation -> { s.operation.onSave(); true }
            e.key == Key.Escape -> s.nav.back()
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().then(shortcuts)) {
        when (top) {
            Screen.Journal -> JournalScreen(
                state = journal,
                vm = s.journal,
                effects = s.journal.effects,
                onOpen = { id -> s.nav.push(Screen.Operation(OperationRequest.Edit(id))) },
                onForm = ::newOperation,
                onSearch = { s.nav.push(Screen.Search()) },
                onSettings = { s.nav.push(Screen.Settings) },
                onAi = { s.nav.push(Screen.Ai) },
            )
            is Screen.Operation -> {
                LaunchedEffect(top.key) { s.operation.open(top.key, top.request, top.fromLine) }
                val state by s.operation.state.collectAsStateWithLifecycle()
                if (state.form.key == top.key) OperationScreen(state, s.operation, onBack = { s.nav.back() })
            }
            is Screen.Search -> {
                LaunchedEffect(top.key) { s.search.open(top.key, top.filter) }
                val state by s.search.state.collectAsStateWithLifecycle()
                SearchScreen(state, s.search, onBack = { s.nav.back() },
                    onOpen = { id -> s.nav.push(Screen.Operation(OperationRequest.Edit(id))) },
                    onExport = { f -> s.nav.push(Screen.Data(ExportScope(f))) })
            }
            Screen.Accounts -> {
                val state by s.accounts.state.collectAsStateWithLifecycle()
                AccountsScreen(state, s.accounts, onBack = { s.nav.back() }, onOpen = { id -> s.nav.push(Screen.AccountEdit(id)) })
            }
            is Screen.AccountEdit -> {
                LaunchedEffect(top.key) { s.accounts.open(top.key, top.id) }
                val state by s.accounts.state.collectAsStateWithLifecycle()
                if (state.form.key == top.key) AccountEditScreen(state, s.accounts, onBack = { s.nav.back() })
            }
            Screen.Categories -> {
                val state by s.categories.state.collectAsStateWithLifecycle()
                CategoriesScreen(state, s.categories, onBack = { s.nav.back() },
                    onOpen = { id, kind, parent -> s.nav.push(Screen.CategoryEdit(id, kind, parent)) },
                    onOpenTag = { id -> s.nav.push(Screen.TagEdit(id)) })
            }
            is Screen.CategoryEdit -> {
                LaunchedEffect(top.key) { s.categories.open(top.key, top.id, top.kind, top.parentId) }
                val state by s.categories.state.collectAsStateWithLifecycle()
                if (state.form.key == top.key) CategoryEditScreen(state, s.categories, onBack = { s.nav.back() },
                    onAddSub = { kind, parent -> s.nav.push(Screen.CategoryEdit(null, kind, parent)) })
            }
            is Screen.TagEdit -> {
                LaunchedEffect(top.key) { s.categories.openTag(top.key, top.id) }
                val state by s.categories.state.collectAsStateWithLifecycle()
                if (state.tagForm.key == top.key) TagEditScreen(state, s.categories, onBack = { s.nav.back() })
            }
            is Screen.Data -> {
                LaunchedEffect(top.key) { s.data.open(top.key, top.scope) }
                val state by s.data.state.collectAsStateWithLifecycle()
                DataScreen(state, s.data, onBack = { s.nav.back() }, onFormatDoc = { s.nav.push(Screen.FormatDoc) })
            }
            Screen.FormatDoc -> FormatDocScreen(s.settings.formatText, onBack = { s.nav.back() })
            Screen.Settings -> SettingsPage(s)
            Screen.Ai -> {
                val state by s.settings.state.collectAsStateWithLifecycle()
                AiScreen(state, s.settings, onBack = { s.nav.back() })
            }
        }
    }
}

@Composable
private fun SettingsPage(s: Screens) {
    val state by s.settings.state.collectAsStateWithLifecycle()
    val lockOn by s.lock.enabled.collectAsStateWithLifecycle()
    val update by s.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val lockSupport = Biometrics.support(context)
    val installUpdate = {
        s.updates.onInstall { file ->
            runCatching { context.startActivity(UpdateInstaller.intent(context, file)) }
                .onFailure { s.updates.onInstallProblem(UpdateProblem.Installer) }
        }
    }
    val allowInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) installUpdate() else s.updates.onInstallProblem(UpdateProblem.Permission)
    }
    SettingsScreen(
        state = state,
        vm = s.settings,
        version = BuildConfig.VERSION_NAME,
        lockOn = lockOn,
        lockSupport = lockSupport,
        update = update,
        onBack = { s.settings.onDisarm(); s.nav.back() },
        onData = { s.nav.push(Screen.Data()) },
        onAccounts = { s.nav.push(Screen.Accounts) },
        onCategories = { s.nav.push(Screen.Categories) },
        onAi = { s.nav.push(Screen.Ai) },
        // turning the lock on or off is asked of the person with the same prompt that opens it
        onToggleLock = {
            val activity = context.fragmentActivity()
            when {
                activity == null -> Unit
                lockSupport != LockSupport.Ready -> if (lockOn) s.lock.setEnabled(false)
                else -> Biometrics.prompt(activity, if (lockOn) Texts.LOCK_TITLE_OFF else Texts.LOCK_TITLE_ON) { result ->
                    if (result == PromptResult.Passed) s.lock.setEnabled(!lockOn)
                }
            }
        },
        onCheckUpdates = s.updates::onCheck,
        onDownloadUpdate = s.updates::onDownload,
        onCancelUpdate = s.updates::onCancel,
        onInstallUpdate = {
            if (context.packageManager.canRequestPackageInstalls()) installUpdate()
            else runCatching { allowInstall.launch(UpdateInstaller.permissionIntent(context)) }.onFailure { s.updates.onInstallProblem(UpdateProblem.Installer) }
        },
        onOpenUrl = { runCatching { links.openUri(it) } },
        onErased = { s.nav.home() },
    )
}
