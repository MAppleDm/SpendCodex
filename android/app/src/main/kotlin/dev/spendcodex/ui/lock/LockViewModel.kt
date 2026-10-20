package dev.spendcodex.ui.lock

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The lock's choice, for the about page. The check itself is made in the activity (it needs one to show the prompt). */
@HiltViewModel
class LockViewModel @Inject constructor(private val lock: AppLock) : ViewModel() {
    val enabled: StateFlow<Boolean> = lock.enabled

    fun setEnabled(on: Boolean) = lock.setEnabled(on)
}
