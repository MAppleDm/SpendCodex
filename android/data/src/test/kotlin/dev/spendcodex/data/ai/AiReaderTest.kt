package dev.spendcodex.data.ai

import dev.spendcodex.data.domain.Account
import dev.spendcodex.data.domain.AccountKind
import dev.spendcodex.data.domain.Category
import dev.spendcodex.data.domain.CategoryKind
import dev.spendcodex.data.domain.DraftTime
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.domain.TxType
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AiReaderTest {
    private val server = MockWebServer()
    private var key: String? = "sk-test-0123456789"
    private lateinit var reader: AiReader

    private val today = LocalDate.of(2026, 10, 20)
    private val ctx = AiContext(
        today = today, timeZone = "Europe/Berlin", english = false,
        accounts = listOf(Account("card", "Карта", AccountKind.Card, "EUR", 1_000_000, 0, 0), Account("cash", "Наличные", AccountKind.Cash, "EUR", null, 0, 0)),
        categories = listOf(Category("cafe", "Кафе", CategoryKind.Expense, null, emptyList(), 0, 0), Category("salary", "Зарплата", CategoryKind.Income, null, emptyList(), 0, 0)),
        defaultCurrency = "EUR",
    )

    @Before fun start() {
        server.start()
        reader = AiReader(OkHttpClient(), server.url("/").toString(), key = { key })
    }

    @After fun stop() = server.shutdown()

    private fun answer(arguments: String) = MockResponse().setResponseCode(200).setBody(
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"1","type":"function",
           "function":{"name":"record_operations","arguments":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(arguments))}}}]}}]}""",
    )

    @Test
    fun `the request forces the function, sends the line and names only, never amounts or history`() = runTest {
        server.enqueue(answer("""{"operations":[{"type":"expense","amount":"4.50","description":"кофе","category":"Кафе"}]}"""))
        reader.read("выпил кофе на 4,50 у вокзала", ctx)
        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer sk-test-0123456789", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("record_operations", body["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val user = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(user.contains("выпил кофе на 4,50 у вокзала"))
        assertTrue(user.contains("\"Карта\"") && user.contains("\"Кафе\"") && user.contains("2026-10-20"))
        // the opening balance of an account (10 000,00) is not part of what is sent
        assertFalse(user.contains("1000000") || user.contains("10000"))
    }

    @Test
    fun `operations come back as drafts with names turned into ids`() = runTest {
        server.enqueue(answer("""{"operations":[
            {"type":"expense","amount":"4.50","description":"кофе","category":"Кафе","account":"наличные","date":"2026-10-19","time":"08:30","tags":["#утро"]},
            {"type":"income","amount":"3000","description":"зарплата","category":"Зарплата"}
        ]}"""))
        val drafts = reader.read("кофе 4.50 вчера в 8:30 налом #утро и зарплата 3000", ctx)
        assertEquals(2, drafts.size)
        val coffee = drafts[0]
        assertEquals(TxType.Expense, coffee.draft.type)
        assertEquals("4.50", coffee.draft.amount)
        assertEquals("cash", coffee.draft.accountId)
        assertEquals("cafe", coffee.draft.categoryId)
        assertEquals(LocalDate.of(2026, 10, 19), coffee.draft.date)
        assertEquals(DraftTime.At(LocalTime.of(8, 30)), coffee.draft.time)
        assertEquals(listOf("утро"), coffee.draft.tags)
        assertEquals(Origin.Ai, coffee.draft.origin)
        assertTrue(coffee.amountInText)
        assertEquals("salary", drafts[1].draft.categoryId)
        assertTrue(drafts[1].amountInText)
    }

    @Test
    fun `an amount that is not in the text is marked, unknown names are reported, future dates dropped`() = runTest {
        server.enqueue(answer("""{"operations":[{"type":"expense","amount":"15","description":"обед пополам","account":"Сбер","category":"Рестораны","date":"2027-01-01"}]}"""))
        val d = reader.read("обед на двоих 30 евро пополам", ctx).single()
        assertFalse(d.amountInText)
        assertEquals("Сбер", d.unknownAccount)
        assertEquals("Рестораны", d.unknownCategory)
        assertNull(d.draft.accountId)
        assertNull(d.draft.date)
    }

    @Test
    fun `a refused key, a busy server and a useless answer are told apart`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Authentication Fails (sk-test-0123456789)"}}"""))
        try { reader.read("кофе", ctx); fail() } catch (e: AiFailure.Auth) {
            assertFalse("the key must not be echoed", e.detail.contains("0123456789"))
        }
        server.enqueue(MockResponse().setResponseCode(503))
        try { reader.read("кофе", ctx); fail() } catch (e: AiFailure.Unavailable) { assertTrue(e.retryable) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Не понял"}}]}"""))
        try { reader.read("кофе", ctx); fail() } catch (e: AiFailure.BadOutput) { }
        key = null
        try { reader.read("кофе", ctx); fail() } catch (e: AiFailure.NoKey) { }
    }

    @Test
    fun `numbers in text are compared as exact decimals`() {
        assertTrue(AiReader.numberInText("4.5", "кофе 4,50"))
        assertTrue(AiReader.numberInText("1200", "аренда 1 200 ₽"))
        assertTrue(AiReader.numberInText("12000", "ноутбук 12к"))
        assertFalse(AiReader.numberInText("15", "обед 30 пополам"))
        assertFalse(AiReader.numberInText("abc", "обед 30"))
    }

    @Suppress("unused")
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
}
