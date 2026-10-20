package dev.spendcodex.data.parse

import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.TxType
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLineTest {
    private val today = LocalDate.of(2026, 10, 20) // a Tuesday

    private fun account(id: String, name: String, currency: String = "EUR", kind: AccountKind = AccountKind.Card) =
        Account(id, name, kind, currency, null, 0, 0)

    private fun category(id: String, name: String, kind: CategoryKind = CategoryKind.Expense, parent: String? = null, vararg words: String) =
        Category(id, name, kind, parent, words.toList(), 0, 0)

    private val accounts = listOf(
        account("card", "Карта"),
        account("cash", "Наличные", kind = AccountKind.Cash),
        account("usd", "Доллары", "USD"),
    )
    private val categories = listOf(
        category("food", "Продукты", CategoryKind.Expense, null, "продукты", "магазин", "супермаркет", "groceries"),
        category("cafe", "Кафе и рестораны", CategoryKind.Expense, null, "кофе", "кафе", "ресторан", "обед", "ужин", "coffee", "lunch"),
        category("transport", "Транспорт", CategoryKind.Expense, null, "такси", "метро", "автобус", "taxi"),
        category("shopping", "Покупки"),
        category("salary", "Зарплата", CategoryKind.Income, null, "зарплата", "аванс", "salary"),
    )

    private fun parse(text: String, hint: (String) -> Hint? = { null }) =
        QuickLine.parse(text, LineContext(accounts, categories, today, hint))

    private fun one(text: String, hint: (String) -> Hint? = { null }): LineResult = parse(text, hint).single()

    @Test
    fun `a coffee is an expense in the cafe category`() {
        val r = one("кофе 4.50")
        assertEquals(TxType.Expense, r.draft.type)
        assertEquals("4.50", r.draft.amount)
        assertNull(r.draft.currency)
        assertNull(r.draft.accountId)
        assertEquals("cafe", r.draft.categoryId)
        assertEquals(CategorySource.Written, r.categorySource)
        assertEquals("кофе", r.draft.description)
        assertEquals(DraftTime.Now, r.draft.time)
        assertNull(r.draft.date)
        assertEquals(Origin.Line, r.draft.origin)
        assertEquals("кофе 4.50", r.draft.inputText)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `a description that is only the category name is not repeated`() {
        val r = one("продукты 32.90")
        assertEquals("food", r.draft.categoryId)
        assertEquals("", r.draft.description)
    }

    @Test
    fun `yesterday has a date and no made-up time`() {
        val r = one("такси 12 вчера")
        assertEquals(LocalDate.of(2026, 10, 19), r.draft.date)
        assertEquals(DraftTime.Unknown, r.draft.time)
        assertEquals("12", r.draft.amount)
        assertEquals("transport", r.draft.categoryId)
        assertEquals("такси", r.draft.description)
        assertTrue(r.dateWritten)
    }

    @Test
    fun `plus or an income category makes an income`() {
        val a = one("+зарплата 3000")
        assertEquals(TxType.Income, a.draft.type)
        assertEquals("salary", a.draft.categoryId)
        val b = one("зарплата 3000")
        assertEquals(TxType.Income, b.draft.type)
        val c = one("аванс +1500")
        assertEquals(TxType.Income, c.draft.type)
        assertEquals("1500", c.draft.amount)
    }

    @Test
    fun `an arrow between two accounts is a transfer`() {
        val r = one("карта → наличные 100")
        assertEquals(TxType.Transfer, r.draft.type)
        assertEquals("card", r.draft.accountId)
        assertEquals("cash", r.draft.toAccountId)
        assertEquals("100", r.draft.amount)
        assertNull(r.draft.categoryId)
        assertFalse(r.blocking)

        val ascii = one("наличные -> карта 50")
        assertEquals("cash", ascii.draft.accountId)
        assertEquals("card", ascii.draft.toAccountId)
    }

    @Test
    fun `from and to words make a transfer in any order`() {
        val r = one("100 с карты на наличные")
        assertEquals(TxType.Transfer, r.draft.type)
        assertEquals("card", r.draft.accountId)
        assertEquals("cash", r.draft.toAccountId)
        assertEquals("", r.draft.description)
        val back = one("на карту с наличных 20")
        assertEquals("cash", back.draft.accountId)
        assertEquals("card", back.draft.toAccountId)
    }

    @Test
    fun `sending money to a person is an expense, not a transfer between own accounts`() {
        val r = one("перевод маме 100")
        assertEquals(TxType.Expense, r.draft.type)
        assertEquals("перевод маме", r.draft.description)
        assertFalse(r.blocking)
        val own = one("перевод карта наличные 30")
        assertEquals(TxType.Transfer, own.draft.type)
    }

    @Test
    fun `a transfer across currencies takes the second amount as what arrived`() {
        val r = one("наличные → доллары 100 108")
        assertEquals("cash", r.draft.accountId)
        assertEquals("usd", r.draft.toAccountId)
        assertEquals("100", r.draft.amount)
        assertEquals("108", r.draft.toAmount)
    }

    @Test
    fun `a transfer with one account waits for the other`() {
        val r = one("карта → 100")
        assertTrue(r.issues.contains(LineIssue.TransferTarget))
        assertTrue(r.blocking)
    }

    @Test
    fun `refund keeps the expense category kind`() {
        val r = one("возврат кроссовки 59.99")
        assertEquals(TxType.Refund, r.draft.type)
        assertEquals("кроссовки", r.draft.description)
        assertEquals("59.99", r.draft.amount)
    }

    @Test
    fun `currency, tags and a note`() {
        val r = one("кофе 4,50 € #работа #Кофейни // с коллегой")
        assertEquals("EUR", r.draft.currency)
        assertEquals("4,50", r.draft.amount)
        assertEquals(listOf("работа", "Кофейни"), r.draft.tags)
        assertEquals("с коллегой", r.draft.note)
        assertEquals("кофе", r.draft.description)
        assertTrue(r.currencyWritten)
    }

    @Test
    fun `currency signs before or after and currency words`() {
        assertEquals("USD", one("$12.5 такси").draft.currency)
        assertEquals("12.5", one("$12.5 такси").draft.amount)
        assertEquals("RUB", one("обед 450 руб").draft.currency)
        assertEquals("RUB", one("обед 450₽").draft.currency)
        assertEquals("USD", one("обед 20 usd").draft.currency)
        assertEquals("KZT", one("обед 2000 тенге").draft.currency)
        assertNull(one("try 20").draft.currency) // an English word, not Turkish lira
        assertEquals("TRY", one("кебаб 200 TRY").draft.currency)
    }

    @Test
    fun `weekday and time`() {
        val r = one("обед 12.30 в пятницу в 13:15")
        assertEquals(LocalDate.of(2026, 10, 16), r.draft.date)
        assertEquals(DraftTime.At(LocalTime.of(13, 15)), r.draft.time)
        assertEquals("12.30", r.draft.amount)
        assertEquals("обед", r.draft.description)
    }

    @Test
    fun `dates in several forms`() {
        assertEquals(LocalDate.of(2026, 10, 12), one("12 окт обед 15").draft.date)
        assertEquals("15", one("12 окт обед 15").draft.amount)
        assertEquals(LocalDate.of(2026, 10, 17), one("3 дня назад кафе 10").draft.date)
        assertEquals(LocalDate.of(2026, 10, 1), one("кофе 4.50 2026-10-01").draft.date)
        assertEquals(LocalDate.of(2026, 10, 15), one("кофе 4.50 15/10").draft.date)
        assertEquals(LocalDate.of(2025, 3, 2), one("кофе 4.50 02.03.25").draft.date)
        assertEquals(LocalDate.of(2026, 10, 18), one("coffee 4.50 on sunday").draft.date)
        assertEquals(LocalDate.of(2026, 10, 19), one("coffee 4.50 yesterday").draft.date)
        assertEquals("coffee", one("coffee 4.50 yesterday").draft.description)
    }

    @Test
    fun `a day and month in the future is last year, and says so`() {
        val r = one("ужин 30 12.11")
        assertEquals(LocalDate.of(2025, 11, 12), r.draft.date)
        assertEquals("30", r.draft.amount)
        assertTrue(r.issues.any { it is LineIssue.DateLastYear })
    }

    @Test
    fun `a date-like number alone is the amount`() {
        val r = one("кофе 3.10")
        assertEquals("3.10", r.draft.amount)
        assertNull(r.draft.date)
        val r2 = one("аренда 800 1.10")
        assertEquals("800", r2.draft.amount)
        assertEquals(LocalDate.of(2026, 10, 1), r2.draft.date)
    }

    @Test
    fun `accounts by abbreviation and case, not by look-alike words`() {
        assertEquals("cash", one("кофе 4.50 нал").draft.accountId)
        assertEquals("card", one("кофе 4.50 картой").draft.accountId)
        assertEquals("card", one("кофе 4.50 с карты").draft.accountId)
        assertEquals("", one("кофе 4.50 с карты").draft.description.substringAfter("кофе").trim())
        val potato = one("картошка 3")
        assertNull(potato.draft.accountId)
        assertEquals("картошка", potato.draft.description)
        assertNull(one("карман 3").draft.accountId)
        assertTrue(one("кофе 4.50 картой").accountWritten)
    }

    @Test
    fun `several operations in one line`() {
        val rs = parse("кофе 4.50, круассан 3")
        assertEquals(2, rs.size)
        assertEquals("4.50", rs[0].draft.amount)
        assertEquals("3", rs[1].draft.amount)
        assertEquals("круассан", rs[1].draft.description)
        assertEquals(2, parse("кофе 4.50\nтакси 12").size)
        assertEquals(2, parse("кофе 4.50; такси 12").size)
        // one amount for two things is one operation
        assertEquals(1, parse("кофе, круассан 7.50").size)
        // a decimal comma does not split
        assertEquals("4,50", parse("кофе 4,50").single().draft.amount)
    }

    @Test
    fun `no amount blocks, several numbers are shown`() {
        assertTrue(one("кофе").blocking)
        assertTrue(one("кофе").issues.contains(LineIssue.NoAmount))
        val r = one("2 кофе 4.50")
        assertEquals("4.50", r.draft.amount)
        assertEquals("2 кофе", r.draft.description)
        assertTrue(r.issues.any { it is LineIssue.SeveralNumbers })
        assertFalse(r.blocking)
        val withCurrency = one("2 кофе 9 €")
        assertEquals("9", withCurrency.draft.amount)
    }

    @Test
    fun `a space groups thousands only where it cannot be two numbers`() {
        assertEquals("1 200", one("продукты 1 200").draft.amount)
        assertEquals("12 300", one("ремонт 12 300").draft.amount)
        assertEquals("250 000", one("машина 250 000").draft.amount)
        assertEquals("1 234,50", one("продукты 1 234,50").draft.amount)
        val two = one("обед 100 108")
        assertEquals("108", two.draft.amount)
        assertTrue(two.issues.any { it is LineIssue.SeveralNumbers })
    }

    @Test
    fun `an ambiguous thousands comma is flagged`() {
        val r = one("продукты 1,200")
        assertEquals("1,200", r.draft.amount)
        assertTrue(r.issues.any { it is LineIssue.AmbiguousNumber })
    }

    @Test
    fun `what the account moved after an equals sign`() {
        val r = one("такси 12$ = 11,05€")
        assertEquals("12", r.draft.amount)
        assertEquals("USD", r.draft.currency)
        assertEquals("11,05", r.draft.accountAmount)
    }

    @Test
    fun `an adjustment keeps its sign`() {
        val r = one("корректировка -20 нал")
        assertEquals(TxType.Adjustment, r.draft.type)
        assertEquals("-20", r.draft.amount)
        assertEquals("cash", r.draft.accountId)
    }

    @Test
    fun `learned category and account for a description seen before`() {
        val r = one("аренда 800") { if (it == "аренда") Hint("shopping", "card") else null }
        assertEquals("shopping", r.draft.categoryId)
        assertEquals(CategorySource.Learned, r.categorySource)
        assertEquals("card", r.draft.accountId)
        assertTrue(r.accountLearned)
        assertFalse(r.accountWritten)
    }

    @Test
    fun `a category that does not fit the written type is left out and said`() {
        val r = one("+кофе 5")
        assertEquals(TxType.Income, r.draft.type)
        assertNull(r.draft.categoryId)
        assertTrue(r.issues.any { it is LineIssue.CategoryConflict })
    }

    @Test
    fun `inflected and partial category words`() {
        assertEquals("food", one("продуктов на 20").draft.categoryId)
        assertEquals("cafe", one("рестораны 40").draft.categoryId)
        assertEquals("transport", one("taxi 9").draft.categoryId)
    }

    @Test
    fun `word matching rules`() {
        assertTrue(QuickLine.wordMatches("карты", "карта"))
        assertTrue(QuickLine.wordMatches("картой", "карта"))
        assertTrue(QuickLine.wordMatches("нал", "наличные"))
        assertTrue(QuickLine.wordMatches("наличными", "наличные"))
        assertFalse(QuickLine.wordMatches("картошка", "карта"))
        assertFalse(QuickLine.wordMatches("карман", "карта"))
        assertFalse(QuickLine.wordMatches("ка", "карта"))
    }

    @Test
    fun `the empty line reads as nothing to record`() {
        val r = parse("   ")
        assertEquals(1, r.size)
        assertTrue(r.single().blocking)
    }
}
