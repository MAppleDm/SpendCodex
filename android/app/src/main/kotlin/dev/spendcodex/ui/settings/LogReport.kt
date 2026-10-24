package dev.spendcodex.ui.settings

import android.content.Context
import android.os.Build
import dev.spendcodex.BuildConfig
import dev.spendcodex.data.log.AppLog
import dev.spendcodex.data.settings.Choices
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What goes into the logs' .zip besides the log files: the phone and the app's choices, and logcat as Android kept it. */
object LogReport {
    fun fileName(): String = "spendcodex-logs-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".zip"

    fun extras(context: Context, choices: Choices, theme: String, hasKey: Boolean): Map<String, String> =
        mapOf("device.txt" to device(context, choices, theme, hasKey), "logcat.txt" to logcat())

    /** Never the key itself: only whether there is one. */
    private fun device(context: Context, choices: Choices, theme: String, hasKey: Boolean): String = buildString {
        appendLine("SpendCodex ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}), ${context.packageName}")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Locale ${Locale.getDefault()}, zone ${ZoneId.systemDefault()}, now ${OffsetDateTime.now()}")
        appendLine("Language ${choices.language.code}, theme $theme, default currency ${choices.defaultCurrency ?: "none"}, DeepSeek key ${if (hasKey) "saved" else "none"}")
        val files = AppLog.files()
        appendLine("Logs: ${files.size} file(s), ${files.sumOf { it.length() }} bytes; free space ${context.filesDir.usableSpace / 1_048_576} MB")
        val runtime = Runtime.getRuntime()
        appendLine("Memory: ${runtime.totalMemory() / 1_048_576} of ${runtime.maxMemory() / 1_048_576} MB")
    }

    /** The app's own lines in logcat (an app sees only its own): system messages about it, crashes Android noticed. */
    private fun logcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "20000").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(5, TimeUnit.SECONDS)
        text
    }.getOrElse { "logcat unavailable: $it" }
}
