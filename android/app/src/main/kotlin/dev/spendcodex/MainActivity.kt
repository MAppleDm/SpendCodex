package dev.spendcodex

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.CoreTexts
import dev.spendcodex.data.settings.Language
import dev.spendcodex.data.settings.Settings
import dev.spendcodex.ui.AppRoot
import dev.spendcodex.ui.NavViewModel
import dev.spendcodex.ui.Screens
import dev.spendcodex.ui.accounts.AccountsViewModel
import dev.spendcodex.ui.categories.CategoriesViewModel
import dev.spendcodex.ui.data.DataViewModel
import dev.spendcodex.ui.journal.JournalViewModel
import dev.spendcodex.ui.lock.AppLock
import dev.spendcodex.ui.lock.Biometrics
import dev.spendcodex.ui.lock.LockScreen
import dev.spendcodex.ui.lock.LockSupport
import dev.spendcodex.ui.lock.LockViewModel
import dev.spendcodex.ui.lock.PromptResult
import dev.spendcodex.ui.operation.OperationViewModel
import dev.spendcodex.ui.search.SearchViewModel
import dev.spendcodex.ui.settings.SettingsViewModel
import dev.spendcodex.ui.update.UpdateViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val nav: NavViewModel by viewModels()
    private val journal: JournalViewModel by viewModels()
    private val operation: OperationViewModel by viewModels()
    private val search: SearchViewModel by viewModels()
    private val accounts: AccountsViewModel by viewModels()
    private val categories: CategoriesViewModel by viewModels()
    private val data: DataViewModel by viewModels()
    private val settingsModel: SettingsViewModel by viewModels()
    private val lockModel: LockViewModel by viewModels()
    private val updates: UpdateViewModel by viewModels()

    @Inject lateinit var settings: Settings
    @Inject lateinit var lock: AppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screens = Screens(nav, journal, operation, search, accounts, categories, data, settingsModel, lockModel, updates)
        // Follows the system light/dark setting; there is no dynamic (wallpaper) colour. A new language rebuilds the
        // screens (the view models and their state stay).
        setContent {
            val choices by settings.choices.collectAsState()
            CoreTexts.english = choices.language == Language.En
            val locked by lock.locked.collectAsState()
            LaunchedEffect(locked) { if (locked) askToUnlock() }
            key(choices.language) {
                AppTheme {
                    if (locked) LockScreen(onOpen = ::askToUnlock) else AppRoot(screens)
                }
            }
        }
        // with the lock on the app's picture is not kept in the list of recent apps
        lifecycleScope.launch { lock.enabled.collect { hideInRecents(it) } }
        if (savedInstanceState == null) takeSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedText(intent)
    }

    /** Text shared from another app ("Поделиться" → SpendCodex) lands in the quick line, ready to check and record. */
    private fun takeSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            nav.home()
            journal.onSharedText(it.take(2_000))
        }
    }

    /**
     * The system's prompt; it opens the app when passed. A phone that cannot check anyone any more (its screen lock was
     * removed after the lock was turned on) opens it at once: a lock that cannot be opened would keep the person out of
     * their own journal, and whoever could remove the phone's lock could see everything on it anyway.
     */
    private fun askToUnlock() {
        if (Biometrics.support(this) != LockSupport.Ready) {
            lock.unlock()
            return
        }
        Biometrics.prompt(this, Texts.LOCK_TITLE_OPEN) { result -> if (result != PromptResult.Closed) lock.unlock() }
    }

    /** Android 13 can hide just the picture in the list of recent apps; before that only a secure window does. */
    private fun hideInRecents(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        super.onStop()
        lock.onBackground()
    }

    override fun onStart() {
        super.onStart()
        lock.onForeground()
        journal.onForeground()
    }
}
