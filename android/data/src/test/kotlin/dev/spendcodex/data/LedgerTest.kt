package dev.spendcodex.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.Problem
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.repo.AccountEdit
import dev.spendcodex.data.repo.CategoryEdit
import dev.spendcodex.data.repo.EditError
import dev.spendcodex.data.repo.EditResult
import dev.spendcodex.data.repo.SaveResult
import dev.spendcodex.data.settings.Language
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerTest {
    private val env = TestEnv()
    private val ledger = env.ledger

    @After fun close() = env.close()

    private suspend fun category(name: String, kind: CategoryKind = CategoryKind.Expense, parent: String? = null): String =
        (ledger.saveCategory(CategoryEdit(name = name, kind = kind, parentId = parent, keywords = emptyList())) as EditResult.Ok).id

    @Test
    fun `an expense with only an amount goes to the default account, now, in the account's currency`() = runTest {
        val card = env.account("Карта")
        env.account("Наличные", kind = AccountKind.Cash)
        val tx = env.saved(Draft(amount = "4,50", description = "кофе", origin = Origin.Line, inputText = "кофе 4,50"))
        assertEquals(card, tx.accountId)
        assertEquals("EUR", tx.currency)
        assertEquals(450L, tx.amountMinor)
        assertEquals(LocalDate.of(2026, 10, 20), tx.date)
        assertEquals(LocalTime.of(12, 15), tx.time) // 10:15 UTC in Berlin
        assertEquals("Europe/Berlin", tx.timeZone)
        assertEquals(Origin.Line, tx.origin)
        assertEquals(tx, ledger.get(tx.id))
    }

    @Test
    fun `a past date has no invented time`() = runTest {
        env.account("Карта")
        val tx = env.saved(Draft(amount = "12", date = LocalDate.of(2026, 10, 19), time = DraftTime.Now))
        assertNull(tx.time)
    }

    @Test
    fun `balances follow the one definition, transfers are neither spent nor earned`() = runTest {
        val card = env.account("Карта", opening = "1000")
        val cash = env.account("Наличные", opening = "50", kind = AccountKind.Cash)
        val expense = env.saved(Draft(amount = "30.25", accountId = card))
        env.saved(Draft(type = TxType.Income, amount = "2000", accountId = card))
        env.saved(Draft(type = TxType.Transfer, amount = "100", accountId = card, toAccountId = cash))
        env.saved(Draft(type = TxType.Refund, amount = "10", accountId = card, refundOf = expense.id))
        env.saved(Draft(type = TxType.Adjustment, amount = "-0.75", accountId = cash))
        val b = ledger.balancesNow()
        assertEquals(100_000L - 3_025 + 200_000 - 10_000 + 1_000, b.getValue(card).minor)
        assertEquals(5_000L + 10_000 - 75, b.getValue(cash).minor)
        assertTrue(b.getValue(card).complete)
        assertEquals(b, ledger.balances.first())
    }

    @Test
    fun `another currency counts with what the account moved, or is marked as not counted`() = runTest {
        val card = env.account("Карта")
        env.saved(Draft(amount = "12", currency = "USD", accountId = card, accountAmount = "11,05"))
        env.saved(Draft(amount = "5", currency = "USD", accountId = card))
        val b = ledger.balancesNow().getValue(card)
        assertEquals(-1_105L, b.minor)
        assertEquals(1, b.unconverted)
        assertFalse(b.complete)
    }

    @Test
    fun `a transfer across currencies needs what arrived`() = runTest {
        val eur = env.account("Евро")
        val usd = env.account("Доллары", "USD")
        val missing = ledger.save(Draft(type = TxType.Transfer, amount = "100", accountId = eur, toAccountId = usd))
        assertTrue((missing as SaveResult.Invalid).problems.contains(Problem.TransferAmountMissing))
        env.saved(Draft(type = TxType.Transfer, amount = "100", accountId = eur, toAccountId = usd, toAmount = "108.40"))
        val b = ledger.balancesNow()
        assertEquals(-10_000L, b.getValue(eur).minor)
        assertEquals(10_840L, b.getValue(usd).minor)
        val same = ledger.save(Draft(type = TxType.Transfer, amount = "1", accountId = eur, toAccountId = eur))
        assertTrue((same as SaveResult.Invalid).problems.contains(Problem.TransferSameAccount))
    }

    @Test
    fun `the same draft sent twice is one record`() = runTest {
        env.account("Карта")
        val draft = Draft(amount = "5")
        val first = ledger.save(draft) as SaveResult.Saved
        val second = ledger.save(draft) as SaveResult.Saved
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(1, ledger.count.first())
    }

    @Test
    fun `an edit changes the record, an edit without change leaves even its updated time`() = runTest {
        env.account("Карта")
        val tx = env.saved(Draft(amount = "5", description = "кофе"))
        env.tick(60)
        val draft = Draft.of(tx, emptyList(), { "EUR" })
        val unchanged = ledger.save(draft) as SaveResult.Saved
        assertEquals(tx.updatedAt, unchanged.tx.updatedAt)
        assertEquals(tx, ledger.get(tx.id))
        val changed = ledger.save(draft.copy(amount = "6", note = "с собой\nвторая строка")) as SaveResult.Saved
        assertEquals(600L, changed.tx.amountMinor)
        assertEquals(tx.createdAt, changed.tx.createdAt)
        assertNotEquals(tx.updatedAt, changed.tx.updatedAt)
        assertEquals("с собой\nвторая строка", ledger.get(tx.id)!!.note)
        assertEquals(1, ledger.count.first())
    }

    @Test
    fun `delete and undo give back the exact record with its tags and refunds`() = runTest {
        env.account("Карта")
        val buy = env.saved(Draft(amount = "60", description = "кроссовки", tags = listOf("спорт", "Обувь")))
        val refund = env.saved(Draft(type = TxType.Refund, amount = "60", refundOf = buy.id))
        val removed = ledger.delete(buy.id)!!
        assertNull(ledger.get(buy.id))
        assertNull(ledger.get(refund.id)!!.refundOf)
        ledger.undo(removed)
        assertEquals(buy, ledger.get(buy.id))
        assertEquals(buy.id, ledger.get(refund.id)!!.refundOf)
        assertEquals(listOf("Обувь", "спорт"), ledger.tagNames(buy.tagIds).sorted())
    }

    @Test
    fun `tags are made on the fly and found again whatever the case`() = runTest {
        env.account("Карта")
        val a = env.saved(Draft(amount = "1", tags = listOf("Работа")))
        val b = env.saved(Draft(amount = "2", tags = listOf("работа", "#отпуск")))
        assertEquals(2, ledger.tagsNow().size)
        assertTrue(a.tagIds.single() in b.tagIds)
        val work = a.tagIds.single()
        assertEquals(EditResult.Error(EditError.InUse), ledger.deleteTag(work))
        assertEquals(EditResult.Ok(work), ledger.renameTag(work, "Работа-2026"))
        assertEquals("Работа-2026", ledger.tagNames(listOf(work)).single())
        val vacation = ledger.tagsNow().first { it.name == "отпуск" }.id
        assertEquals(EditResult.Error(EditError.NameTaken), ledger.renameTag(vacation, "работа-2026"))
    }

    @Test
    fun `archived things stay on old records and cannot be chosen for new ones`() = runTest {
        val old = env.account("Старая карта")
        env.account("Карта")
        val cat = category("Хобби")
        val tx = env.saved(Draft(amount = "3", accountId = old, categoryId = cat))
        ledger.setAccountArchived(old, true)
        ledger.setCategoryArchived(cat, true)
        val fresh = ledger.save(Draft(amount = "4", accountId = old)) as SaveResult.Invalid
        assertTrue(fresh.problems.contains(Problem.AccountArchived))
        val freshCat = ledger.save(Draft(amount = "4", categoryId = cat)) as SaveResult.Invalid
        assertTrue(freshCat.problems.contains(Problem.CategoryArchived))
        val edit = ledger.save(Draft.of(tx, emptyList(), { "EUR" }).copy(description = "кисти")) as SaveResult.Saved
        assertEquals(old, edit.tx.accountId)
        assertEquals(EditResult.Error(EditError.InUse), ledger.deleteAccount(old))
        assertEquals(EditResult.Error(EditError.InUse), ledger.deleteCategory(cat))
    }

    @Test
    fun `the database itself refuses to lose history`() = runTest {
        val card = env.account("Карта")
        env.saved(Draft(amount = "3", accountId = card))
        var refused = false
        try {
            env.db.dao().deleteAccount(card)
        } catch (e: SQLiteConstraintException) {
            refused = true
        }
        assertTrue("foreign keys must be on", refused)
    }

    @Test
    fun `an unused account can be deleted, a used one cannot change currency`() = runTest {
        val spare = env.account("Запасной")
        assertEquals(EditResult.Ok(spare), ledger.deleteAccount(spare))
        val card = env.account("Карта")
        env.saved(Draft(amount = "1", accountId = card))
        assertEquals(EditResult.Error(EditError.CurrencyLocked), ledger.saveAccount(AccountEdit(card, "Карта", AccountKind.Card, "USD", "")))
        assertEquals(EditResult.Error(EditError.NameTaken), ledger.saveAccount(AccountEdit(null, "карта", AccountKind.Card, "EUR", "")))
    }

    @Test
    fun `a category must fit the type`() = runTest {
        env.account("Карта")
        val salary = category("Зарплата", CategoryKind.Income)
        val r = ledger.save(Draft(amount = "1", categoryId = salary)) as SaveResult.Invalid
        assertTrue(r.problems.contains(Problem.CategoryWrongKind))
        env.saved(Draft(type = TxType.Income, amount = "1", categoryId = salary))
        val t = ledger.save(Draft(type = TxType.Transfer, amount = "1", categoryId = salary))
        assertTrue(t is SaveResult.Invalid)
    }

    @Test
    fun `subcategories are one level deep and of the same kind`() = runTest {
        val food = category("Еда")
        val cafe = category("Кафе", parent = food)
        val income = category("Доход", CategoryKind.Income)
        assertEquals(EditResult.Error(EditError.ParentInvalid), ledger.saveCategory(CategoryEdit(null, "Кофе", CategoryKind.Expense, cafe, emptyList())))
        assertEquals(EditResult.Error(EditError.ParentInvalid), ledger.saveCategory(CategoryEdit(null, "Бонус", CategoryKind.Income, food, emptyList())))
        assertEquals(EditResult.Error(EditError.ParentInvalid), ledger.saveCategory(CategoryEdit(food, "Еда", CategoryKind.Expense, income, emptyList())))
        assertEquals(EditResult.Error(EditError.NameTaken), ledger.saveCategory(CategoryEdit(null, "кафе", CategoryKind.Expense, food, emptyList())))
    }

    @Test
    fun `several operations are stored all together or not at all`() = runTest {
        env.account("Карта")
        val r = ledger.saveAll(listOf(Draft(amount = "1"), Draft(amount = "")))
        assertEquals(1, r.size)
        assertEquals(1, (r.single() as SaveResult.Invalid).index)
        assertEquals(0, ledger.count.first())
        assertEquals(2, ledger.saveAll(listOf(Draft(amount = "1"), Draft(amount = "2"))).count { it is SaveResult.Saved })
    }

    @Test
    fun `operations at the same minute keep the order they were made in, unknown times come last in their day`() = runTest {
        env.account("Карта")
        val a = env.saved(Draft(amount = "1", description = "a"))
        env.tick(1)
        val b = env.saved(Draft(amount = "2", description = "b"))
        env.tick(1)
        val c = env.saved(Draft(amount = "3", description = "c", date = LocalDate.of(2026, 10, 20), time = DraftTime.Unknown))
        val d = env.saved(Draft(amount = "4", description = "d", date = LocalDate.of(2026, 10, 19), time = DraftTime.At(LocalTime.of(23, 0))))
        val feed = ledger.feedNow(Filter(), 10).map { it.id }
        assertEquals(listOf(c.id, b.id, a.id, d.id), feed)
        assertEquals(feed, ledger.feedNow(Filter(), 10).map { it.id })
    }

    @Test
    fun `search finds words anywhere, in any case, and filters combine`() = runTest {
        val card = env.account("Карта")
        val cash = env.account("Наличные", "USD", kind = AccountKind.Cash)
        val food = category("Еда")
        val cafe = category("Кафе", parent = food)
        env.saved(Draft(amount = "4.50", description = "Кофе в «Ёлке»", categoryId = cafe, tags = listOf("Работа"), accountId = card))
        env.saved(Draft(amount = "30", description = "продукты", categoryId = food, accountId = card, date = LocalDate.of(2026, 9, 3)))
        env.saved(Draft(amount = "7", description = "такси", accountId = cash, note = "до вокзала"))
        env.saved(Draft(type = TxType.Income, amount = "100", description = "возврат долга", accountId = cash))

        suspend fun ids(f: Filter) = ledger.feedNow(f, 100).map { it.description }
        assertEquals(listOf("Кофе в «Ёлке»"), ids(Filter(text = "кофе ЕЛКЕ")))
        assertEquals(listOf("Кофе в «Ёлке»"), ids(Filter(text = "работа")))
        assertEquals(listOf("такси"), ids(Filter(text = "вокзал")))
        assertEquals(listOf("Кофе в «Ёлке»"), ids(Filter(text = "4,50")))
        assertEquals(2, ids(Filter(categoryIds = setOf(food))).size) // the subcategory counts
        assertEquals(listOf("продукты"), ids(Filter(from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30))))
        assertEquals(listOf("возврат долга"), ids(Filter(types = setOf(TxType.Income), currencies = setOf("USD"))))
        assertEquals(listOf("такси"), ids(Filter(types = setOf(TxType.Expense), accountIds = setOf(cash))))
        assertEquals(emptyList<String>(), ids(Filter(text = "кофе", accountIds = setOf(cash))))
        assertEquals(listOf("такси"), ids(Filter(text = "налич", types = setOf(TxType.Expense))))
        // LIKE wildcards in the query are plain characters
        assertEquals(emptyList<String>(), ids(Filter(text = "%")))
        val totals = ledger.totalsNow(Filter(accountIds = setOf(cash)))
        assertEquals(setOf("USD"), totals.map { it.currency }.toSet())
    }

    @Test
    fun `repeat makes a new record now, refund draft links to the purchase`() = runTest {
        env.account("Карта")
        val tx = env.saved(Draft(amount = "4.50", description = "кофе", date = LocalDate.of(2026, 10, 1), time = DraftTime.Unknown, tags = listOf("утро")))
        env.tick(5)
        val again = (ledger.repeat(tx.id) as SaveResult.Saved).tx
        assertNotEquals(tx.id, again.id)
        assertEquals(LocalDate.of(2026, 10, 20), again.date)
        assertEquals(Origin.Repeat, again.origin)
        assertEquals(450L, again.amountMinor)
        assertEquals(tx.tagIds, again.tagIds)
        val refund = ledger.refundDraft(tx.id)!!
        assertEquals(TxType.Refund, refund.type)
        assertEquals(tx.id, refund.refundOf)
        assertEquals(tx.id, env.saved(refund).refundOf)
    }

    @Test
    fun `reconcile adds the difference as an adjustment`() = runTest {
        val cash = env.account("Наличные", opening = "100")
        env.saved(Draft(amount = "20", accountId = cash))
        val r = ledger.reconcile(cash, "75,50") as SaveResult.Saved
        assertEquals(TxType.Adjustment, r.tx.type)
        assertEquals(-450L, r.tx.amountMinor)
        assertEquals(Origin.Reconcile, r.tx.origin)
        assertEquals(7_550L, ledger.balancesNow().getValue(cash).minor)
        assertNull(ledger.reconcile(cash, "75.50"))
    }

    @Test
    fun `a new journal starts with categories and two accounts in its language`() = runTest {
        ledger.seed(Language.Ru, "RUB")
        val accounts = ledger.accountsNow()
        assertEquals(listOf("Карта", "Наличные"), accounts.map { it.name })
        assertTrue(accounts.all { it.currency == "RUB" })
        assertEquals(accounts.first().id, env.settings.choices.value.defaultAccountId)
        val categories = ledger.categoriesNow()
        assertTrue(categories.any { it.name == "Кафе и рестораны" && "кофе" in it.keywords })
        assertTrue(categories.any { it.kind == CategoryKind.Income && it.name == "Зарплата" })
        ledger.seed(Language.En, "EUR") // a journal that has accounts is not seeded again
        assertEquals(2, ledger.accountsNow().size)
    }

    @Test
    fun `the journal survives closing and opening the database`() = runTest {
        val name = "persist-test.db"
        env.context.deleteDatabase(name)
        val first = dev.spendcodex.data.db.AppDatabase.build(env.context, name, mainThread = true)
        val ledger1 = dev.spendcodex.data.repo.Ledger(first, env.settings, env.clock)
        val card = (ledger1.saveAccount(AccountEdit(null, "Карта", AccountKind.Card, "EUR", "")) as EditResult.Ok).id
        val tx = (ledger1.save(Draft(amount = "9.99", accountId = card, note = "строка 1\nстрока 2")) as SaveResult.Saved).tx
        first.close()
        val second = dev.spendcodex.data.db.AppDatabase.build(env.context, name, mainThread = true)
        val ledger2 = dev.spendcodex.data.repo.Ledger(second, env.settings, env.clock)
        assertEquals(tx, ledger2.get(tx.id))
        second.close()
        env.context.deleteDatabase(name)
    }
}
