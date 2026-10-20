package dev.spendcodex.ui.lock

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the app asks for the phone's fingerprint, face or PIN before it shows the journal, and whether it is closed right now.
 *
 * Only the choice is kept (one flag); the check itself is the system's, the app never sees a fingerprint or a PIN. With the
 * lock on, the app is closed on every new start and when it comes back after [GRACE_MS] in the background, so that a quick look
 * at a message or the camera does not ask twice.
 */
@Singleton
class AppLock(context: Context, private val now: () -> Long) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, { SystemClock.elapsedRealtime() })

    private val prefs = context.getSharedPreferences("lock", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled

    // a start of the app is closed whenever the lock is on
    private val _locked = MutableStateFlow(_enabled.value)
    val locked: StateFlow<Boolean> = _locked

    private var leftAt: Long? = null

    /** The person turned the lock on or off (having just proved who they are), so the app stays open now. */
    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(ENABLED, on) }
        _enabled.value = on
        _locked.value = false
    }

    fun unlock() {
        _locked.value = false
    }

    /** The app went out of sight. */
    fun onBackground() {
        leftAt = now()
    }

    /** The app is back: closed if it was away for a while. */
    fun onForeground() {
        val left = leftAt ?: return
        leftAt = null
        if (_enabled.value && now() - left >= GRACE_MS) _locked.value = true
    }

    companion object {
        const val GRACE_MS = 60_000L
        private const val ENABLED = "enabled"
    }
}
