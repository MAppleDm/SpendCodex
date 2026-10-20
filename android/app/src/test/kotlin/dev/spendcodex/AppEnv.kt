package dev.spendcodex

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spendcodex.data.ai.AiReader
import dev.spendcodex.data.backup.Backups
import dev.spendcodex.data.backup.DocAssets
import dev.spendcodex.data.db.AppDatabase
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.settings.KeyCipher
import dev.spendcodex.data.settings.Language
import dev.spendcodex.data.settings.SecretStore
import dev.spendcodex.data.settings.Settings
import dev.spendcodex.ui.Notices
import dev.spendcodex.ui.journal.JournalViewModel
import dev.spendcodex.ui.operation.OperationViewModel
import dev.spendcodex.ui.search.SearchViewModel
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import okhttp3.OkHttpClient

/** No Keystore under Robolectric: a reversible stand-in, enough for the screens. */
class PlainCipher : KeyCipher {
    override fun encrypt(plain: String) = "plain:$plain"
    override fun decrypt(token: String) = token.removePrefix("plain:")
}

class FixedClock(private val instant: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone() = zone
    override fun withZone(zone: ZoneId) = FixedClock(instant, zone)
    override fun instant() = instant
}

/** The app's real objects over an in-memory journal, built by hand instead of by Hilt. */
class AppEnv(language: Language = Language.Ru, modelUrl: String = "http://127.0.0.1:1") {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = FixedClock(Instant.parse("2026-10-20T10:15:30Z"), ZoneId.of("Europe/Berlin"))
    val db = AppDatabase.build(context, name = null, mainThread = true)
    val settings = Settings(context).also { it.reset(); it.setLanguage(language) }
    val secrets = SecretStore(context, PlainCipher()).also { it.clear() }
    val ledger = Ledger(db, settings, clock)
    val notices = Notices()
    val docs = DocAssets { File("../../docs/export/$it").inputStream() }
    val backups = Backups(context, db, ledger, settings, clock, docs, "0.1.0")
    val ai = AiReader(OkHttpClient(), modelUrl, key = { secrets.deepseekKey })

    fun journal() = JournalViewModel(ledger, settings, secrets, ai, notices, clock)
    fun operation() = OperationViewModel(ledger, settings, notices, clock, context)
    fun search() = SearchViewModel(ledger, clock)

    fun close() = db.close()
}
