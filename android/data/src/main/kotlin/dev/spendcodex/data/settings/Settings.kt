package dev.spendcodex.data.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Language(val code: String) {
    Ru("ru"), En("en");

    val locale: Locale get() = Locale.forLanguageTag(code)

    /** The decimal separator amounts are shown and typed with in this language. */
    val decimal: Char get() = if (this == Ru) ',' else '.'

    companion object {
        fun of(code: String?): Language? = entries.firstOrNull { it.code == code }

        /** A phone set to Russian (or a neighbouring language most of its users also read) starts in Russian. */
        fun forDevice(locale: Locale = Locale.getDefault()): Language =
            if (locale.language in setOf("ru", "uk", "be", "kk")) Ru else En
    }
}

/**
 * The language of everything the app writes. Set by [Settings] from the user's choice; read wherever a text is made.
 */
object Lang {
    @Volatile var current: Language = Language.Ru

    val en: Boolean get() = current == Language.En

    fun t(ru: String, en: String): String = if (this.en) en else ru
}

/** What is stored about the user's choices; a full backup carries all of it except the DeepSeek key. */
data class Choices(
    val language: Language,
    val defaultCurrency: String?,
    val defaultAccountId: String?,
    val aiEnabled: Boolean,
)

/**
 * The user's choices, in the `app` preferences file (which Android's own backup carries together with the database).
 * The DeepSeek key is not here: it lives encrypted in [SecretStore] and never travels.
 */
@Singleton
class Settings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _choices = MutableStateFlow(read())
    val choices: StateFlow<Choices> = _choices.asStateFlow()

    private val _setupDone = MutableStateFlow(prefs.getBoolean(SETUP, false))
    /** False until the first start chose a currency (or a backup was restored). */
    val setupDone: StateFlow<Boolean> = _setupDone.asStateFlow()

    private val _lastBackupAt = MutableStateFlow(prefs.getLong(LAST_BACKUP, 0L).takeIf { it > 0 })
    /** When a full backup was last saved out of the app (epoch ms), for the reminder on the data page. */
    val lastBackupAt: StateFlow<Long?> = _lastBackupAt.asStateFlow()

    init {
        Lang.current = _choices.value.language
    }

    private fun read() = Choices(
        language = Language.of(prefs.getString(LANGUAGE, null)) ?: Language.forDevice(),
        defaultCurrency = prefs.getString(CURRENCY, null),
        defaultAccountId = prefs.getString(ACCOUNT, null),
        aiEnabled = prefs.getBoolean(AI, false),
    )

    fun setLanguage(language: Language) = update { it.copy(language = language) }
    fun setDefaultCurrency(code: String) = update { it.copy(defaultCurrency = code) }
    fun setDefaultAccount(id: String?) = update { it.copy(defaultAccountId = id) }
    fun setAiEnabled(on: Boolean) = update { it.copy(aiEnabled = on) }

    fun update(change: (Choices) -> Choices) {
        val next = change(_choices.value)
        prefs.edit {
            putString(LANGUAGE, next.language.code)
            if (next.defaultCurrency == null) remove(CURRENCY) else putString(CURRENCY, next.defaultCurrency)
            if (next.defaultAccountId == null) remove(ACCOUNT) else putString(ACCOUNT, next.defaultAccountId)
            putBoolean(AI, next.aiEnabled)
        }
        Lang.current = next.language
        _choices.value = next
    }

    fun setSetupDone(done: Boolean) {
        prefs.edit { putBoolean(SETUP, done) }
        _setupDone.value = done
    }

    fun markBackup(at: Long) {
        prefs.edit { putLong(LAST_BACKUP, at) }
        _lastBackupAt.value = at
    }

    /** "Стереть всё": back to a first start, in the same language. */
    fun reset() {
        val language = _choices.value.language
        prefs.edit { clear(); putString(LANGUAGE, language.code) }
        _choices.value = Choices(language, null, null, false)
        _setupDone.value = false
        _lastBackupAt.value = null
    }

    private companion object {
        const val LANGUAGE = "language"
        const val CURRENCY = "default_currency"
        const val ACCOUNT = "default_account"
        const val AI = "ai_enabled"
        const val SETUP = "setup_done"
        const val LAST_BACKUP = "last_backup_at"
    }
}
