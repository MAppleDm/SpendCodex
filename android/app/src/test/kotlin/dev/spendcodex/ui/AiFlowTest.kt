package dev.spendcodex.ui

import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendcodex.AppEnv
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.settings.Language
import dev.spendcodex.ui.journal.AiUi
import dev.spendcodex.ui.journal.JournalScreen
import dev.spendcodex.ui.journal.JournalViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise the real UI, HTTP client and database together. No real key or paid API calls. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class AiFlowTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer().also { it.start() }
    private val env = AppEnv(Language.Ru, modelUrl = server.url("/").toString())
    private lateinit var vm: JournalViewModel
    @After fun close() { env.close(); server.shutdown() }
    private fun count() = runBlocking { env.ledger.feedNow(Filter(), 100).size }

    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("condition not met; composer=${vm.state.value.composer}")
    }

    private fun show(key: Boolean = true) {
        runBlocking { env.ledger.seed(Language.Ru, "EUR") }
        if (key) env.secrets.saveKey("sk-test-0123456789")
        // Old installations can have this flag off. Text entry is primary AI regardless of the old fallback setting.
        env.settings.setAiEnabled(false)
        vm = env.journal()
        compose.setContent { AppTheme(darkTheme = false) {
            val s by vm.state.collectAsState()
            JournalScreen(s, vm, vm.effects, {}, {}, {}, {}, {})
        } }
        settle { vm.state.value.loaded && vm.state.value.names.active.size == 1 }
    }

    private fun answer(ops: String, status: String = "ok"): MockResponse {
        val args = """{"status":"$status","operations":[$ops]}"""
        return MockResponse().setResponseCode(200).setBody(
            """{"choices":[{"message":{"tool_calls":[{"id":"1","type":"function","function":{"name":"record_operations","arguments":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(args))}}}]}}]}""",
        )
    }
    private val coffee = """{"type":"expense","amount":"4.50","description":"кофе","category":"Кафе и рестораны","tags":["кофе","кафе"]}"""
    private fun type(text: String) = compose.onNodeWithContentDescription("кофе 4.50").performTextReplacement(text)
    private fun send() = compose.onNodeWithContentDescription(Texts.AI_SEND).performClick()

    @Test fun `even a simple line goes to DeepSeek on send and saves with generated tags and no undo line`() {
        server.enqueue(answer(coffee))
        show()
        type("кофе 4.50")
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
        send()
        settle { count() == 1 && vm.state.value.composer.text.isEmpty() }
        assertEquals(1, server.requestCount)
        val tx = runBlocking { env.ledger.get(env.ledger.feedNow(Filter(), 5).single().id)!! }
        assertEquals(Origin.Ai, tx.origin)
        assertEquals("кофе 4.50", tx.inputText)
        assertEquals(450L, tx.amountMinor)
        assertEquals(setOf("кофе", "кафе"), runBlocking { env.ledger.tagNames(tx.tagIds).toSet() })
        compose.onNodeWithTag("notice").assertDoesNotExist()
        compose.onNodeWithTag("operation").assertTextContains("4,50 €")
    }

    @Test fun `a swipe right deletes a record, a short one does not, and the deletion can be taken back`() {
        server.enqueue(answer(coffee))
        show()
        type("кофе 4.50")
        send()
        settle { count() == 1 && vm.state.value.composer.text.isEmpty() }
        val id = runBlocking { env.ledger.feedNow(Filter(), 5).single().id }
        compose.onNodeWithTag("operation").performTouchInput { swipeRight(startX = left, endX = left + width * 0.25f) }
        settle { vm.state.value.rows.size == 1 }
        compose.onNodeWithTag("operation").assertIsDisplayed()
        assertEquals(1, count())
        compose.onNodeWithTag("operation").performTouchInput { swipeRight() }
        settle { count() == 0 && vm.state.value.rows.isEmpty() }
        compose.onNodeWithText("Удалено: кофе 4,50 €").assertIsDisplayed()
        compose.onNodeWithTag("notice-action").performClick()
        settle { count() == 1 }
        val back = runBlocking { env.ledger.get(id)!! }
        assertEquals(setOf("кофе", "кафе"), runBlocking { env.ledger.tagNames(back.tagIds).toSet() })
        compose.onNodeWithTag("notice").assertDoesNotExist()
    }

    @Test fun `one invalid amount blocks the whole batch and cannot be bypassed by confirming`() {
        val invented = """{"type":"expense","amount":"15","description":"обед","tags":["обед"]}"""
        server.enqueue(answer("$coffee,$invented"))
        show()
        type("кофе 4.50, обед 30 пополам")
        send()
        settle { vm.state.value.composer.ai is AiUi.Result }
        compose.onNodeWithTag("ai-record").assertIsNotEnabled()
        compose.runOnIdle { vm.onAiRecord() }
        assertEquals(0, count())
        assertEquals("кофе 4.50, обед 30 пополам", vm.state.value.composer.text)
    }

    @Test fun `several expenses save atomically in their own currencies without converting to account currency`() {
        val taxi = """{"type":"expense","amount":"1200","currency":"JPY","description":"такси","tags":["такси"]}"""
        server.enqueue(answer("$coffee,$taxi"))
        show()
        type("кофе 4.50 евро, такси 1200 JPY")
        send()
        settle { count() == 2 && !vm.state.value.composer.busy }
        val rows = runBlocking { env.ledger.feedNow(Filter(), 10) }
        assertEquals(setOf("EUR", "JPY"), rows.map { it.currency }.toSet())
        assertEquals(1200L, rows.single { it.currency == "JPY" }.amountMinor)
        assertTrue(rows.all { it.accountAmountMinor == null })
        compose.onNodeWithTag("notice").assertDoesNotExist()
    }

    @Test fun `network failure preserves text and retry plus repeated taps creates exactly one expense`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(answer(coffee).setBodyDelay(300, TimeUnit.MILLISECONDS))
        show()
        type("кофе 4.50")
        send()
        settle { vm.state.value.composer.ai is AiUi.Failed }
        assertEquals("кофе 4.50", vm.state.value.composer.text)
        assertEquals(0, count())
        compose.runOnIdle { vm.onAiRetry(); vm.onAiRetry(); vm.onSend() }
        settle { count() == 1 && !vm.state.value.composer.busy }
        assertEquals(2, server.requestCount)
    }

    @Test fun `editing the text cancels an in flight response so a stale expense is never saved`() {
        server.enqueue(answer(coffee).setBodyDelay(600, TimeUnit.MILLISECONDS))
        show()
        type("кофе 4.50")
        send()
        settle { server.requestCount == 1 }
        type("такси 12")
        settle { vm.state.value.composer.text == "такси 12" && !vm.state.value.composer.busy }
        Thread.sleep(750)
        compose.waitForIdle()
        assertEquals(0, count())
        assertEquals("такси 12", vm.state.value.composer.text)
    }

    @Test fun `no key means no request and no silent local fallback`() {
        show(key = false)
        type("кофе 4.50")
        send()
        settle { vm.state.value.composer.ai is AiUi.Failed }
        assertTrue((vm.state.value.composer.ai as AiUi.Failed).needsKey)
        assertEquals(0, server.requestCount)
        assertEquals(0, count())
        compose.onNodeWithTag("ai-manual").assertExists()
        val draft = vm.formDraft()
        assertEquals("кофе 4.50", draft.description)
        assertEquals("", draft.amount)
    }

    @Test fun `income is rejected instead of being turned into an expense`() {
        server.enqueue(answer("", status = "unsupported"))
        show()
        type("зарплата 3000")
        send()
        settle { vm.state.value.composer.ai is AiUi.Failed }
        compose.onNodeWithText(Texts.AI_EXPENSES_ONLY).assertExists()
        assertEquals(0, count())
    }

    @Test fun `an unknown category waits for explicit review and confirmation is idempotent`() {
        server.enqueue(answer(coffee.replace("Кафе и рестораны", "Кофейни")))
        show()
        type("кофе 4.50")
        send()
        settle { vm.state.value.composer.ai is AiUi.Result }
        assertEquals(0, count())
        compose.onNodeWithTag("ai-record").assertIsEnabled()
        compose.runOnIdle { vm.onAiRecord(); vm.onAiRecord() }
        settle { count() == 1 }
        assertEquals(1, server.requestCount)
    }
}
