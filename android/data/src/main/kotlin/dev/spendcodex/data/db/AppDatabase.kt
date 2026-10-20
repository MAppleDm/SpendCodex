package dev.spendcodex.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The journal's database. Its schema is exported to `data/schemas` on every build; a new version needs a migration in
 * [Migrations] and a test that opens the old schema, migrates it and checks every row survived.
 */
@Database(
    entities = [AccountEntity::class, CategoryEntity::class, TagEntity::class, TransactionEntity::class, TransactionTagEntity::class, ReceiptEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        const val NAME = "spendcodex.db"

        /** [name] null: in memory (tests). [mainThread]: let tests query from their own thread. */
        fun build(context: Context, name: String? = NAME, mainThread: Boolean = false): AppDatabase {
            val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            else Room.databaseBuilder(context, AppDatabase::class.java, name)
            if (mainThread) builder.allowMainThreadQueries()
            return builder
                .addMigrations(*Migrations.ALL)
                .addCallback(object : Callback() {
                    // the references between records are the journal's integrity: enforced by SQLite, not hoped for
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
        }
    }
}

/** Schema migrations, oldest first. */
object Migrations {
    val ALL: Array<androidx.room.migration.Migration> = arrayOf(object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS receipts (id TEXT NOT NULL PRIMARY KEY, transaction_id TEXT NOT NULL,
                width INTEGER NOT NULL, height INTEGER NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                created_at INTEGER NOT NULL, sort INTEGER NOT NULL, content BLOB NOT NULL,
                FOREIGN KEY(transaction_id) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_receipts_transaction_id ON receipts(transaction_id)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_receipts_transaction_id_sha256 ON receipts(transaction_id, sha256)")
        }
    })
}
