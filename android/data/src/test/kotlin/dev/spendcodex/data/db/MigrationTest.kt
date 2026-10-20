package dev.spendcodex.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.repo.Ledger
import dev.spendcodex.data.settings.Settings
import java.io.File
import java.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database's schema history. Every version is exported to data/schemas. This test builds a database exactly as an
 * old version wrote it (from its exported schema), fills it, and opens it with today's app, which runs [Migrations.ALL] and
 * refuses to open a schema that does not match. A new version adds its migration and a case here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A database file as version [version] of the app created it, from the exported schema. */
    private fun createFromSchema(name: String, version: Int): File {
        val schema = Json.parseToJsonElement(File("schemas/${AppDatabase::class.java.name}/$version.json").readText()).jsonObject
        val database = schema["database"]!!.jsonObject
        assertEquals(version, database["version"]!!.jsonPrimitive.int)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            database["entities"]!!.jsonArray.forEach { e ->
                val entity = e.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            database["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
        }
        return file
    }

    @Test
    fun `a version 1 database opens with today's app and keeps its rows`() = runTest {
        val name = "schema-v1.db"
        val file = createFromSchema(name, 1)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "INSERT INTO accounts (id, name, name_norm, kind, currency, opening_minor, created_at, updated_at, archived, sort) " +
                    "VALUES ('a', 'Карта', 'карта', 'card', 'EUR', NULL, 1, 1, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO transactions (id, type, date, time_min, time_zone, amount_minor, currency, account_id, account_amount_minor, " +
                    "to_account_id, to_amount_minor, category_id, payee, description, note, refund_of, origin, input_text, created_at, updated_at, " +
                    "search_text, description_norm) VALUES ('t', 'expense', '2026-10-20', 615, 'Europe/Berlin', 450, 'EUR', 'a', NULL, NULL, NULL, " +
                    "NULL, NULL, 'кофе', NULL, NULL, 'line', 'кофе 4.50', 1, 1, 'кофе 4.50', 'кофе')",
            )
        }
        val db = AppDatabase.build(context, name, mainThread = true)
        val ledger = Ledger(db, Settings(context), Clock.systemUTC())
        val row = ledger.feedNow(Filter(), 10).single()
        assertEquals(450L, row.amountMinor)
        assertEquals("кофе", row.description)
        assertEquals("Карта", row.accountName)
        db.close()
        context.deleteDatabase(name)
    }
}
