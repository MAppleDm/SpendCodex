package dev.spendcodex.data.ai

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
        categories = listOf(Category("cafe", "Кафе", CategoryKind.Expense, null, emptyList(), 0, 0), Category("salary", "Зарплата", CategoryKind.Income, null, emptyList(), 0, 0)),
        defaultCurrency = "EUR", tags = listOf("утро"),
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
    fun `the request forces the function, sends the line and names only, never amounts, history or payment methods`() = runTest {
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
        assertTrue(user.contains("\"Кафе\"") && user.contains("2026-10-20"))
        // the journal counts spending, not balances: no payment methods are sent, and none is asked for
        assertFalse(user.contains("accounts"))
        assertFalse(body["tools"].toString().contains("\"account\""))
        assertFalse(user.contains("Зарплата"))
        assertTrue(user.contains("existing_tags") && user.contains("утро"))
    }

    @Test
    fun `operations come back as drafts with names turned into ids`() = runTest {
        server.enqueue(answer("""{"operations":[
            {"type":"expense","amount":"4.50","description":"кофе","category":"Кафе","account":"наличные","date":"2026-10-19","time":"08:30","tags":["#утро"]},
            {"type":"expense","amount":"12","description":"такси","tags":["транспорт"]}
        ]}"""))
        val drafts = reader.read("кофе 4.50 вчера в 8:30 налом #утро и такси 12", ctx)
        assertEquals(2, drafts.size)
        val coffee = drafts[0]
        assertEquals(TxType.Expense, coffee.draft.type)
        assertEquals("4.50", coffee.draft.amount)
        // a payment method the model still names is not taken: every record goes to the journal's one account
        assertNull(coffee.draft.accountId)
        assertEquals("cafe", coffee.draft.categoryId)
        assertEquals(LocalDate.of(2026, 10, 19), coffee.draft.date)
        assertEquals(DraftTime.At(LocalTime.of(8, 30)), coffee.draft.time)
        assertEquals(listOf("утро"), coffee.draft.tags)
        assertEquals(Origin.Ai, coffee.draft.origin)
        assertTrue(coffee.amountInText)
        assertEquals(listOf("транспорт"), drafts[1].draft.tags)
        assertTrue(drafts[1].amountInText)
    }

    @Test
    fun `an amount that is not in the text is marked, unknown names are reported, future dates dropped`() = runTest {
        server.enqueue(answer("""{"operations":[{"type":"expense","amount":"15","description":"обед пополам","account":"Сбер","category":"Рестораны","date":"2027-01-01"}]}"""))
        val d = reader.read("обед на двоих 30 евро пополам", ctx).single()
        assertFalse(d.amountInText)
        assertEquals("Рестораны", d.unknownCategory)
        assertNull(d.draft.accountId)
        assertNull(d.draft.date)
        assertTrue(AiIssue.Date in d.issues)
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

    @Test fun `income transfers and refunds never become expenses even if the model ignores the schema`() = runTest {
        for (type in listOf("income", "transfer", "refund", "adjustment", "unknown")) {
            server.enqueue(answer("""{"operations":[{"type":"$type","amount":"3000","description":"зарплата"}]}"""))
            try { reader.read("зарплата 3000", ctx); fail(type) } catch (e: AiFailure.Input) { assertEquals("unsupported", e.reason) }
        }
    }

    @Test fun `missing amounts and ambiguous notes ask for clarification`() = runTest {
        for (status in listOf("needs_amount", "unsupported", "unclear")) {
            server.enqueue(answer("""{"status":"$status","operations":[]}"""))
            try { reader.read("кофе", ctx); fail(status) } catch (e: AiFailure.Input) { assertEquals(status, e.reason) }
        }
    }

    @Test fun `a malformed or duplicated batch is refused in full instead of silently dropping an item`() = runTest {
        val op = """{"type":"expense","amount":"4.50","description":"кофе","tags":["кофе"]}"""
        for (ops in listOf("$op,null", "$op,{}", "$op,$op")) {
            server.enqueue(answer("""{"operations":[$ops]}"""))
            try { reader.read("кофе 4.50", ctx); fail(ops) } catch (e: AiFailure.BadOutput) { }
        }
    }

    @Test fun `invalid currency date time and tags are surfaced instead of becoming silent defaults`() = runTest {
        server.enqueue(answer("""{"operations":[{"type":"expense","amount":"4.50","description":"кофе","currency":"XXX-NO","date":"2026-02-30","time":"25:00","tags":["два слова"]}]}"""))
        val result = reader.read("кофе 4.50", ctx).single()
        assertEquals(AiIssue.entries.toSet(), result.issues.toSet())
    }

    @Test fun `subcategory paths and generated tags are preserved and existing tags are offered as vocabulary`() = runTest {
        val child = Category("espresso", "Эспрессо", CategoryKind.Expense, "cafe", listOf("эспрессо"), 0, 0)
        server.enqueue(answer("""{"operations":[{"type":"expense","amount":"4.50","description":"эспрессо","category":"Кафе › Эспрессо","tags":["#утро","кофе","КОФЕ"]}]}"""))
        val result = reader.read("эспрессо 4.50", ctx.copy(categories = ctx.categories + child)).single()
        assertEquals("espresso", result.draft.categoryId)
        assertEquals(listOf("утро", "кофе"), result.draft.tags)
        assertTrue(result.issues.isEmpty())
        val request = server.takeRequest().body.readUtf8()
        assertTrue(request.contains("existing_tags") && request.contains("Кафе › Эспрессо"))
    }

    @Test fun `a truncated answer is refused even when its JSON can be decoded`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"{\"operations\":[]}"}}]}"""))
        try { reader.read("кофе 4.50", ctx); fail() } catch (e: AiFailure.BadOutput) { assertEquals("incomplete answer", e.detail) }
    }

    // ---------- receipt photos ----------

    private fun receipts(arguments: String, tool: String = "record_receipts") = MockResponse().setResponseCode(200).setBody(
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"1","type":"function",
           "function":{"name":"$tool","arguments":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(arguments))}}}]}}]}""",
    )
    private val photoA = byteArrayOf(-1, -40, -1, 1, 2, 3)
    private val photoB = byteArrayOf(-1, -40, -1, 4, 5, 6)
    private val shop = """{"photos":[0,1],"payee":"Пятёрочка","date":"2026-10-19","time":"18:40","currency":"RUB","total":"1290.50",
        "category":"Кафе","description":"кофе и бытовая химия","items":["Кофе 3в1 340.50","Круассан 500.00","Средство для посуды 450.00"],"tags":["кофе","дом"]}"""

    @Test
    fun `photos go as images with the receipt function forced, and each receipt comes back as one expense with its photos`() = runTest {
        server.enqueue(receipts("""{"status":"ok","receipts":[$shop]}"""))
        val drafts = reader.readPhotos(listOf(photoA, photoB), ctx)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("record_receipts", body["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // images only in the user's message, in the order chosen, each after its number
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        val images = content.filter { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }
            .map { it.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content }
        assertEquals(listOf(photoA, photoB).map { "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(it) }, images)
        assertTrue(content.toString().contains("photo 1"))
        assertTrue(content[0].jsonObject["text"]!!.jsonPrimitive.content.contains("\"Кафе\""))
        assertFalse(body["messages"]!!.jsonArray[0].toString().contains("image_url"))
        // no payment methods either way: none is sent, none asked for
        assertFalse(content[0].jsonObject["text"]!!.jsonPrimitive.content.contains("accounts"))
        assertFalse(body["tools"].toString().contains("\"account\""))
        // a receipt is never asked for in parts
        assertFalse(body["tools"].toString().contains("\"parts\""))

        val receipt = drafts.single()
        assertEquals("1290.50", receipt.draft.amount)
        assertEquals("cafe", receipt.draft.categoryId)
        assertEquals("кофе и бытовая химия", receipt.draft.description)
        assertEquals("Пятёрочка", receipt.draft.payee)
        assertEquals("RUB", receipt.draft.currency)
        assertEquals(LocalDate.of(2026, 10, 19), receipt.draft.date)
        assertEquals(DraftTime.At(LocalTime.of(18, 40)), receipt.draft.time)
        assertEquals("Кофе 3в1 340.50; Круассан 500.00; Средство для посуды 450.00", receipt.draft.note)
        assertEquals(listOf("кофе", "дом"), receipt.draft.tags)
        assertEquals(Origin.Ai, receipt.draft.origin)
        assertNull(receipt.draft.inputText)
        assertEquals(AiReceipt(0, listOf(0, 1), "1290.50"), receipt.receipt)
        assertTrue(receipt.amountInText)
        assertTrue(receipt.issues.isEmpty())
    }

    @Test
    fun `an order on two screenshots, with delivery and discounts, is one purchase for the amount paid`() = runTest {
        // the 0.4.0 split made four records of this one order, one of them the discount, and they did not add up
        server.enqueue(receipts("""{"status":"ok","receipts":[{"photos":[0,1],"payee":"Самокат","date":null,"time":null,"currency":"RUB",
            "total":"617.89","category":null,"description":"продукты и хозтовары","items":["Овощная смесь Hortex 2 шт 289.98",
            "Растворитель Уайт-Спирит 500 мл 99.99","Салфетки Paclan 174.99","Бумажные полотенца Zemma 119.99","Приправа Orient 5 шт 184.95",
            "Фасоль Бондюэль 159.99","Упаковка 29.00","Доставка 59.00","Скидка на заказ −265.00","Промокод −500.00"],"tags":["продукты","хозтовары"]}]}"""))
        val order = reader.readPhotos(listOf(photoA, photoB), ctx).single()
        assertEquals("617.89", order.draft.amount)
        assertEquals(listOf(0, 1), order.receipt!!.photos)
        assertTrue(order.draft.note.endsWith("Доставка 59.00; Скидка на заказ −265.00; Промокод −500.00"))
        assertNull(order.draft.categoryId)
        assertNull(order.unknownCategory)
    }

    @Test
    fun `separate receipts are separate expenses, and a receipt without a total comes back without an amount`() = runTest {
        val other = shop.replace("[0,1]", "[2]").replace("\"1290.50\"", "\"99.90\"").replace("Пятёрочка", "Аптека")
        server.enqueue(receipts("""{"status":"ok","receipts":[$shop,$other]}"""))
        val (first, second) = reader.readPhotos(listOf(photoA, photoB, photoA), ctx)
        assertEquals("1290.50" to listOf(0, 1), first.draft.amount to first.receipt!!.photos)
        assertEquals("99.90" to listOf(2), second.draft.amount to second.receipt!!.photos)
        assertEquals(1, second.receipt!!.index)
        // no total, or one of nothing or less: the person gives the amount, a sign is never flipped
        for (total in listOf("null", "\"-617.89\"", "\"0\"")) {
            server.enqueue(receipts("""{"status":"ok","receipts":[${shop.replace("\"1290.50\"", total)}]}"""))
            val d = reader.readPhotos(listOf(photoA, photoB), ctx).single()
            assertEquals(total, "", d.draft.amount)
            assertNull(d.receipt!!.total)
        }
    }

    @Test
    fun `a wrong photo number or malformed items refuse the answer, as does the line's function`() = runTest {
        for (bad in listOf(shop.replace("[0,1]", "[0,2]"), shop.replace("[0,1]", "[]"), shop.replace("[0,1]", "[\"0\"]"), shop.replace("\"items\":[\"Кофе", "\"items\":[1,\"Кофе"))) {
            server.enqueue(receipts("""{"status":"ok","receipts":[$bad]}"""))
            try { reader.readPhotos(listOf(photoA, photoB), ctx); fail(bad) } catch (_: AiFailure.BadOutput) { }
        }
        server.enqueue(receipts("""{"status":"ok","receipts":[$shop]}""", tool = "record_operations"))
        try { reader.readPhotos(listOf(photoA, photoB), ctx); fail() } catch (_: AiFailure.BadOutput) { }
    }

    @Test
    fun `unreadable photos, photos without a purchase and refund receipts are refused with their reason`() = runTest {
        for (status in listOf("unreadable", "not_a_purchase", "unsupported")) {
            server.enqueue(receipts("""{"status":"$status","receipts":[]}"""))
            try { reader.readPhotos(listOf(photoA), ctx); fail(status) } catch (e: AiFailure.Input) { assertEquals(status, e.reason) }
        }
        key = null
        try { reader.readPhotos(listOf(photoA), ctx); fail() } catch (_: AiFailure.NoKey) { }
        assertEquals(3, server.requestCount)
    }

    @Suppress("unused")
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
}
