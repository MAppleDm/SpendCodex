package dev.spendcodex.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendcodex.data.backup.Archive
import dev.spendcodex.data.db.toEntity
import dev.spendcodex.data.backup.Codec
import dev.spendcodex.data.backup.ExportKind
import dev.spendcodex.data.backup.ExportScope
import dev.spendcodex.data.backup.ImportProblem
import dev.spendcodex.data.backup.ReadResult
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.repo.CategoryEdit
import dev.spendcodex.data.repo.EditResult
import dev.spendcodex.data.settings.Language
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val env = TestEnv()
    private val others = ArrayList<TestEnv>()

    @After fun close() {
        env.close()
        others.forEach { it.close() }
    }

    private fun fresh(zone: ZoneId = ZoneId.of("Asia/Tokyo")) = TestEnv(zone).also { others += it }

    /** A journal with every kind of thing that could get lost on the way. */
    private suspend fun rich(): Map<String, String> = with(env) {
        val card = account("Карта «Основная», €", opening = "1 234,56")
        val usd = account("Доллары", "USD", opening = "")
        val yen = account("円 cash", "JPY", opening = "5000", kind = AccountKind.Cash)
        val kwd = account("Dinar", "KWD", opening = "1.250", kind = AccountKind.Savings)
        val food = (ledger.saveCategory(CategoryEdit(null, "Еда", CategoryKind.Expense, null, listOf("продукты", "groceries"))) as EditResult.Ok).id
        val cafe = (ledger.saveCategory(CategoryEdit(null, "Кафе, бары", CategoryKind.Expense, food, listOf("кофе"))) as EditResult.Ok).id
        val salary = (ledger.saveCategory(CategoryEdit(null, "=Зарплата", CategoryKind.Income, null, emptyList())) as EditResult.Ok).id
        val buy = saved(Draft(amount = "4,50", accountId = card, categoryId = cafe, description = "Кофе \"с собой\", 2 шт.",
            payee = "@coffee", note = "строка 1\nстрока 2, с запятой\n\t«ёлка» 🎄", tags = listOf("Работа", "утро"),
            origin = Origin.Line, inputText = "кофе 4,50 #работа"))
        tick(1)
        saved(Draft(type = TxType.Refund, amount = "1.50", accountId = card, categoryId = cafe, refundOf = buy.id, description = "-сдача"))
        saved(Draft(type = TxType.Income, amount = "3000", accountId = card, categoryId = salary, date = LocalDate.of(2026, 10, 1), time = DraftTime.Unknown))
        saved(Draft(type = TxType.Transfer, amount = "100", accountId = card, toAccountId = usd, toAmount = "108.40", date = LocalDate.of(2026, 10, 2), time = DraftTime.At(LocalTime.of(9, 5))))
        saved(Draft(amount = "1200", currency = "JPY", accountId = card, accountAmount = "7.33", description = "ramen"))
        saved(Draft(amount = "999", accountId = yen, description = "onigiri"))
        saved(Draft(type = TxType.Adjustment, amount = "-0.125", accountId = kwd))
        saved(Draft(amount = "12", currency = "USD", accountId = card, description = "без суммы списания"))
        ledger.setCategoryArchived(salary, true)
        ledger.setAccountArchived(kwd, true)
        val spare = (ledger.saveCategory(CategoryEdit(null, "Пустая", CategoryKind.Expense, null, emptyList())) as EditResult.Ok).id
        ledger.setCategoryArchived(spare, true)
        ledger.setTagArchived(ledger.tagsNow().first { it.name == "утро" }.id, true)
        settings.setDefaultAccount(card)
        settings.setDefaultCurrency("EUR")
        settings.setAiEnabled(true)
        mapOf("card" to card, "usd" to usd, "food" to food)
    }

    private suspend fun bytes(kind: ExportKind, scope: ExportScope = ExportScope(), e: TestEnv = env): ByteArray =
        ByteArrayOutputStream().also { e.backups.write(kind, scope, it) }.toByteArray()

    private fun withoutExportedAt(json: String) = json.replace(Regex("\"exported_at\": \"[^\"]+\""), "\"exported_at\": \"-\"")

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> files.forEach { (name, b) -> z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun `export, restore, export gives the same data`() = runTest {
        rich()
        val backup = bytes(ExportKind.Backup)
        val before = Codec.encode(env.backups.file())

        val other = fresh()
        other.ledger.seed(Language.En, "USD") // something to be replaced
        other.saved(Draft(amount = "1"))
        val read = other.backups.read(ByteArrayInputStream(backup))
        assertTrue(read.toString(), read is ReadResult.Ok)
        val preview = (read as ReadResult.Ok).preview
        assertEquals(1, preview.currentTransactions)
        assertEquals(8, preview.snapshot.transactions.size)
        other.backups.restore(preview)
        val after = Codec.encode(other.backups.file())
        assertEquals(withoutExportedAt(before), withoutExportedAt(after))

        // and the restored journal works: balances, settings, the safety copy of what was replaced
        assertEquals(env.ledger.balancesNow(), other.ledger.balancesNow())
        assertEquals(env.settings.choices.value, other.settings.choices.value)
        assertTrue(other.settings.setupDone.value)
        assertTrue(other.backups.hasSafetyCopy())
        val safety = other.backups.read(other.backups.safetyCopy.inputStream()) as ReadResult.Ok
        assertEquals(1, safety.preview.snapshot.transactions.size)

        // a second round changes nothing either
        val again = fresh(ZoneId.of("America/New_York"))
        again.backups.restore((again.backups.read(ByteArrayInputStream(bytes(ExportKind.Backup, e = other))) as ReadResult.Ok).preview)
        assertEquals(withoutExportedAt(before), withoutExportedAt(Codec.encode(again.backups.file())))
    }

    @Test
    fun `a bare data file restores as well`() = runTest {
        rich()
        val json = bytes(ExportKind.Json)
        val other = fresh()
        val read = other.backups.read(ByteArrayInputStream(json)) as ReadResult.Ok
        other.backups.restore(read.preview)
        assertEquals(withoutExportedAt(String(json)), withoutExportedAt(Codec.encode(other.backups.file())))
    }

    @Test
    fun `the backup archive holds every file with checksums`() = runTest {
        rich()
        val files = unzip(bytes(ExportKind.Backup))
        val root = "spendcodex-backup-2026-10-20/"
        assertEquals(
            setOf("manifest.json", "data/spendcodex.json", "csv/transactions.csv", "csv/accounts.csv", "csv/categories.csv", "csv/tags.csv",
                "csv/transaction_tags.csv", "csv/receipts.csv", "docs/format.md", "docs/schema.json").map { root + it }.toSet(),
            files.keys,
        )
        val manifest = String(files.getValue(root + "manifest.json"))
        assertTrue(manifest.contains("\"kind\": \"backup\""))
        assertTrue(manifest.contains("\"transactions\": 8"))
        assertTrue(manifest.contains(Archive.sha256(files.getValue(root + "data/spendcodex.json"))))
        assertEquals("spendcodex-backup-2026-10-20.zip", env.backups.fileName(ExportKind.Backup))
    }

    @Test
    fun `amounts are exact strings in their currency's digits`() = runTest {
        rich()
        val json = Codec.encode(env.backups.file())
        assertTrue(json.contains("\"amount\": \"4.50\""))
        assertTrue(json.contains("\"amount\": \"1200\""))
        assertTrue(json.contains("\"amount\": \"-0.125\""))
        assertTrue(json.contains("\"opening_balance\": \"1234.56\""))
        assertTrue(json.contains("\"account_amount\": \"7.33\""))
        assertTrue(json.contains("\"to_amount\": \"108.40\""))
        assertTrue(json.contains("\"time\": null"))
        assertTrue(json.contains("\"time_zone\": \"Europe/Berlin\""))
        assertTrue(json.contains("\"created_at\": \"2026-10-20T10:15:30.250Z\""))
        assertFalse(Regex("\"amount\": [0-9-]").containsMatchIn(json))
    }

    @Test
    fun `csv escapes what needs it and guards against formulas`() = runTest {
        rich()
        val files = unzip(bytes(ExportKind.CsvPackage))
        val tx = String(files.entries.first { it.key.endsWith("transactions.csv") }.value)
        val lines = tx.split("\r\n")
        assertEquals(
            "id,date,time,time_zone,type,amount,currency,account_id,account,account_currency,account_amount,to_account_id,to_account," +
                "to_currency,to_amount,category_id,category,parent_category,description,payee,tags,note,refund_of,origin,input_text,created_at,updated_at",
            lines.first(),
        )
        assertTrue(tx.contains("\"Кофе \"\"с собой\"\", 2 шт.\""))
        assertTrue(tx.contains("\"строка 1\nстрока 2, с запятой\n\t«ёлка» 🎄\""))
        assertTrue(tx.contains(",'@coffee,"))
        assertTrue(tx.contains(",'-сдача,"))
        assertTrue(tx.contains("'=Зарплата"))
        assertTrue(tx.contains("\"Карта «Основная», €\""))
        assertTrue(tx.contains(",Работа; утро,"))
        // a negative adjustment is a number, not text, and is not prefixed
        assertTrue(tx.contains(",adjustment,-0.125,KWD,"))
        val accounts = String(files.entries.first { it.key.endsWith("accounts.csv") }.value)
        assertTrue(accounts.contains("computed_balance_complete"))
        // the JSON keeps the exact text
        val json = Codec.encode(env.backups.file())
        assertTrue(json.contains("\"payee\": \"@coffee\""))
    }

    @Test
    fun `a selection for AI is described, partial, and leaves notes out when asked`() = runTest {
        val ids = rich()
        val scope = ExportScope(Filter(from = LocalDate.of(2026, 10, 2), accountIds = setOf(ids.getValue("usd"))), includeNotes = false)
        val preview = env.backups.preview(scope)
        assertEquals(1, preview.transactions)
        val files = unzip(bytes(ExportKind.Ai, scope))
        val root = "spendcodex-ai-2026-10-20/"
        assertTrue(files.keys.containsAll(listOf("README_FOR_AI.md", "PROMPT.txt", "transactions.csv", "data.json", "manifest.json").map { root + it }))
        val readme = String(files.getValue(root + "README_FOR_AI.md"))
        assertFalse(readme.contains("{{"))
        assertTrue(readme.contains("2026-10-02"))
        assertTrue(readme.contains("Доллары (USD)"))
        assertTrue(readme.contains("не включены"))
        val data = String(files.getValue(root + "data.json"))
        assertTrue(data.contains("\"kind\": \"partial\""))
        assertTrue(data.contains("\"notes_included\": false"))
        assertFalse(data.contains("строка 1"))
        // the transfer's source account comes along; accounts nobody asked for do not
        assertTrue(data.contains("Карта «Основная», €"))
        assertFalse(data.contains("円 cash"))
        // a partial file is never a backup
        val refused = fresh().backups.read(ByteArrayInputStream(data.toByteArray())) as ReadResult.Failed
        assertEquals(ImportProblem.Partial, refused.errors.single().problem)
    }

    @Test
    fun `a selected refund keeps no link to a purchase outside the selection`() = runTest {
        rich()
        val scope = ExportScope(Filter(types = setOf(TxType.Refund)))
        val file = env.backups.file(scope)
        assertEquals(1, file.transactions.size)
        assertEquals(null, file.transactions.single().refundOf)
    }

    @Test
    fun `damaged, foreign and broken files are refused before anything changes`() = runTest {
        rich()
        val good = bytes(ExportKind.Backup)
        val other = fresh()
        other.ledger.seed(Language.Ru, "RUB")
        val before = Codec.encode(other.backups.file())

        suspend fun problems(b: ByteArray) =
            (other.backups.read(ByteArrayInputStream(b)) as ReadResult.Failed).errors.map { it.problem }

        // one changed byte in the data file: the checksum notices
        val files = unzip(good).toMutableMap()
        val dataKey = files.keys.first { it.endsWith("data/spendcodex.json") }
        files[dataKey] = String(files.getValue(dataKey)).replace("\"4.50\"", "\"4.51\"").toByteArray()
        assertEquals(listOf(ImportProblem.Damaged), problems(zip(files)))

        val json = String(bytes(ExportKind.Json))
        assertEquals(listOf(ImportProblem.NotReadable), problems("not json at all".toByteArray()))
        assertEquals(listOf(ImportProblem.NotReadable), problems("{}".toByteArray()))
        assertEquals(listOf(ImportProblem.NotSpendCodex), problems(json.replace("\"format\": \"spendcodex\"", "\"format\": \"other\"").toByteArray()))
        assertEquals(listOf(ImportProblem.TooNew), problems(json.replace("\"format_version\": 2", "\"format_version\": 3").toByteArray()))
        assertEquals(listOf(ImportProblem.NotReadable), problems(json.replace("\"payee\": null", "\"payee\": null, \"surprise\": 1").toByteArray()))
        assertTrue(problems(json.replace("\"amount\": \"4.50\"", "\"amount\": \"4.505\"").toByteArray()).contains(ImportProblem.BadField))
        assertTrue(problems(json.replace("\"amount\": \"4.50\"", "\"amount\": \"4,50\"").toByteArray()).contains(ImportProblem.BadField))
        val firstAccount = Regex("\"account_id\": \"([^\"]+)\"").find(json)!!.groupValues[1]
        assertTrue(problems(json.replace("\"account_id\": \"$firstAccount\"", "\"account_id\": \"nope\"").toByteArray()).contains(ImportProblem.MissingReference))
        // a transfer that became an income with a target account breaks a rule of the journal
        assertTrue(problems(json.replace("\"type\": \"transfer\"", "\"type\": \"income\"").toByteArray()).contains(ImportProblem.RuleBroken))
        val txId = Regex("\"transactions\": \\[\\s*\\{\\s*\"id\": \"([^\"]+)\"").find(json)!!.groupValues[1]
        val secondTx = Regex("\"id\": \"([^\"]+)\",\\s*\"type\"").findAll(json).map { it.groupValues[1] }.toList()[1]
        assertTrue(problems(json.replace("\"id\": \"$secondTx\"", "\"id\": \"$txId\"").toByteArray()).contains(ImportProblem.DuplicateId))

        // nothing was touched by any of it
        assertEquals(withoutExportedAt(before), withoutExportedAt(Codec.encode(other.backups.file())))
    }

    @Test
    fun `an empty journal exports and restores as empty`() = runTest {
        val backup = bytes(ExportKind.Backup)
        val other = fresh()
        other.ledger.seed(Language.Ru, "EUR")
        other.backups.restore((other.backups.read(ByteArrayInputStream(backup)) as ReadResult.Ok).preview)
        assertEquals(0, other.ledger.accountsNow().size)
        assertEquals(0, other.ledger.categoriesNow().size)
    }

    @Test
    fun `fifty thousand operations keep the journal quick and the backup round-trips`() = runTest {
        val card = env.account("Карта")
        val cash = env.account("Наличные", kind = AccountKind.Cash)
        val cat = (env.ledger.saveCategory(CategoryEdit(null, "Еда", CategoryKind.Expense, null, emptyList())) as EditResult.Ok).id
        val dao = env.db.dao()
        val start = LocalDate.of(2020, 1, 1)
        val rows = (0 until 50_000).map { i ->
            dev.spendcodex.data.domain.Transaction(
                id = "tx-%06d".format(i), type = if (i % 10 == 0) TxType.Income else TxType.Expense,
                date = start.plusDays((i / 25).toLong()), time = LocalTime.of((i % 24), (i * 7) % 60), timeZone = "Europe/Berlin",
                amountMinor = 100L + i % 9_000, currency = "EUR", accountId = if (i % 3 == 0) cash else card,
                categoryId = if (i % 10 == 0) null else cat, description = "покупка $i", note = if (i % 100 == 0) "заметка $i" else null,
                origin = Origin.Line, createdAt = 1_600_000_000_000L + i, updatedAt = 1_600_000_000_000L + i,
            )
        }
        rows.chunked(1000).forEach { chunk -> dao.insertTransactions(chunk.map { it.toEntity() }) }
        assertEquals(50_000, dao.countNow())

        var t0 = System.nanoTime()
        val page = env.ledger.feedNow(Filter(), 200)
        val feedMs = (System.nanoTime() - t0) / 1_000_000
        val newest = rows.sortedWith(compareByDescending<dev.spendcodex.data.domain.Transaction> { it.date }.thenByDescending { it.time }.thenByDescending { it.createdAt })
        assertEquals(newest.take(200).map { it.id }, page.map { it.id })

        t0 = System.nanoTime()
        val found = env.ledger.feedNow(Filter(text = "заметка 4990"), 200)
        val searchMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(found.any { it.id == "tx-049900" })

        t0 = System.nanoTime()
        val balances = env.ledger.balancesNow()
        val balanceMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(rows.filter { it.accountId == card }.sumOf { if (it.type == TxType.Income) it.amountMinor else -it.amountMinor }, balances.getValue(card).minor)

        t0 = System.nanoTime()
        val backup = bytes(ExportKind.Backup)
        val exportMs = (System.nanoTime() - t0) / 1_000_000
        val other = fresh()
        t0 = System.nanoTime()
        other.backups.restore((other.backups.read(ByteArrayInputStream(backup)) as ReadResult.Ok).preview)
        val restoreMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(50_000, other.db.dao().countNow())
        assertEquals(balances, other.ledger.balancesNow())
        println("50k: feed ${feedMs}ms, search ${searchMs}ms, balances ${balanceMs}ms, backup ${exportMs}ms (${backup.size / 1024} KB), restore ${restoreMs}ms")
        assertTrue("feed took $feedMs ms", feedMs < 2_000)
        assertTrue("search took $searchMs ms", searchMs < 3_000)
    }
}
