package dev.spendcodex.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spendcodex.BuildConfig
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(private val repository: UpdateRepository) : ViewModel() {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private var pending: PendingUpdate? = null
    private val installed = requireNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))

    init {
        repository.pending()?.let {
            if (it.release.version > installed) {
                pending = it
                monitor(it)
            } else repository.cancel() // the update was installed; discard its old APK and download record
        }
    }

    fun onCheck() {
        if (task?.isActive == true || state.value.phase == UpdatePhase.Ready) return
        mutable.value = UpdateState(phase = UpdatePhase.Checking)
        task = viewModelScope.launch {
            try {
                val release = repository.latest()
                mutable.value = when {
                    release == null -> UpdateState(UpdatePhase.NoReleases)
                    release.version <= installed -> UpdateState(UpdatePhase.Current)
                    else -> UpdateState(UpdatePhase.Available, release)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = UpdateState(problem = e.problem(UpdateProblem.Network))
            }
        }
    }

    fun onDownload() {
        if (task?.isActive == true || state.value.phase != UpdatePhase.Available) return
        val release = state.value.release ?: return
        try {
            pending = repository.download(release)
            monitor(pending!!)
        } catch (e: Exception) {
            mutable.value = UpdateState(UpdatePhase.Available, release, problem = e.problem(UpdateProblem.Download))
        }
    }

    private fun monitor(download: PendingUpdate) {
        mutable.value = UpdateState(UpdatePhase.Downloading, download.release, total = download.release.apk?.size ?: 0)
        task = viewModelScope.launch {
            try {
                while (true) {
                    when (val transfer = repository.transfer(download)) {
                        is UpdateTransfer.Progress -> mutable.value = UpdateState(UpdatePhase.Downloading, download.release, transfer.bytes, transfer.total, transfer.paused)
                        UpdateTransfer.Complete -> {
                            mutable.value = UpdateState(UpdatePhase.Verifying, download.release)
                            repository.verifiedFile(download)
                            mutable.value = UpdateState(UpdatePhase.Ready, download.release)
                            break
                        }
                    }
                    delay(750)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedDownload(download, e)
            }
        }
    }

    fun onCancel() {
        task?.cancel()
        repository.cancel()
        pending = null
        mutable.value = UpdateState(UpdatePhase.Available, state.value.release)
    }

    /** Recheck even a restored download, including its package and signing key, immediately before installation. */
    fun onInstall(open: (File) -> Unit) {
        if (task?.isActive == true || state.value.phase != UpdatePhase.Ready) return
        val download = pending ?: return
        mutable.value = state.value.copy(phase = UpdatePhase.Verifying, problem = null)
        task = viewModelScope.launch {
            try {
                val file = repository.verifiedFile(download)
                mutable.value = UpdateState(UpdatePhase.Ready, download.release)
                open(file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedDownload(download, e)
            }
        }
    }

    fun onInstallProblem(problem: UpdateProblem) { mutable.value = state.value.copy(problem = problem) }

    private fun failedDownload(download: PendingUpdate, error: Exception) {
        repository.cancel()
        pending = null
        mutable.value = UpdateState(UpdatePhase.Available, download.release, problem = error.problem(UpdateProblem.Download))
    }
}

private fun Exception.problem(fallback: UpdateProblem) = (this as? UpdateException)?.problem ?: fallback
