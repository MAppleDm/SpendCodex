package dev.spendcodex.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spendcodex.data.backup.Backups
import dev.spendcodex.data.backup.DocAssets
import dev.spendcodex.data.db.AppDatabase
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.repo.AccountEdit
import dev.spendcodex.data.repo.EditResult
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.repo.SaveResult
import dev.spendcodex.data.settings.Language
import dev.spendcodex.data.settings.Settings
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand. */
class TestClock(var now: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

/** A journal in memory with the app's real database, settings and backups. */
class TestEnv(zone: ZoneId = ZoneId.of("Europe/Berlin")) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = TestClock(Instant.parse("2026-10-20T10:15:30.250Z"), zone)
    val db: AppDatabase = AppDatabase.build(context, name = null, mainThread = true)
    val settings = Settings(context).also { it.reset(); it.setLanguage(Language.Ru) }
    val ledger = Ledger(db, settings, clock)
    val docs = DocAssets { name -> File("../../docs/export/$name").inputStream() }
    val backups = Backups(context, db, ledger, settings, clock, docs, "0.1.0-test")

    fun close() = db.close()

    suspend fun account(name: String, currency: String = "EUR", opening: String = "", kind: AccountKind = AccountKind.Card): String =
        (ledger.saveAccount(AccountEdit(name = name, kind = kind, currency = currency, opening = opening)) as EditResult.Ok).id

    suspend fun saved(draft: Draft): dev.spendcodex.data.domain.Transaction {
        val r = ledger.save(draft)
        check(r is SaveResult.Saved) { "not saved: $r" }
        return r.tx
    }

    fun tick(seconds: Long = 1) = clock.advance(Duration.ofSeconds(seconds))

    companion object {
        val UTC: ZoneId = ZoneOffset.UTC
    }
}
