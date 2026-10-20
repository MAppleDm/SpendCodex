package dev.spendcodex.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** How a prompt ended. */
enum class PromptResult {
    Passed,

    /** Dismissed, cancelled, too many wrong tries: the person can ask again. */
    Closed,

    /** The phone cannot check anyone any more (its screen lock was removed, no sensor): the lock cannot work. */
    CannotCheck;

    companion object {
        private val CANNOT = setOf(
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
        )

        fun ofError(code: Int): PromptResult = if (code in CANNOT) CannotCheck else Closed
    }
}

/** What the phone can check a person with. */
enum class LockSupport {
    /** A fingerprint, a face, or a PIN, pattern or password of the phone. */
    Ready,

    /** The phone has no screen lock set, so there is nothing to check against. */
    NotSet,

    /** No such check on this phone at all. */
    Unavailable,
}

/**
 * The system's own prompt (the under-screen fingerprint, the face, or the phone's PIN as a fallback). The app only learns
 * whether it was passed.
 */
object Biometrics {
    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun support(context: Context): LockSupport = when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
        BiometricManager.BIOMETRIC_SUCCESS -> LockSupport.Ready
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LockSupport.NotSet
        else -> LockSupport.Unavailable
    }

    /** When the prompt that is up was asked for; 0 when none is. A prompt older than [STALE_MS] is not trusted to still be up. */
    private var shownAt = 0L
    private const val STALE_MS = 30_000L

    /**
     * Shows the prompt and tells [onResult] how it ended. One prompt at a time: a second ask while one is up is ignored, unless
     * the first one has been up so long that its answer was lost on the way (an activity that went away under it).
     */
    fun prompt(activity: FragmentActivity, title: String, onResult: (PromptResult) -> Unit) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (shownAt != 0L && now - shownAt < STALE_MS) return
        shownAt = now
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                shownAt = 0L
                onResult(PromptResult.Passed)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                shownAt = 0L
                onResult(PromptResult.ofError(errorCode))
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(ALLOWED).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}

/** The activity a composable is shown in, if it is one that can show the prompt. */
fun Context.fragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
