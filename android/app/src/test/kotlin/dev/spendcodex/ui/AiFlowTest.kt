package dev.spendcodex.ui

import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendcodex.AppEnv
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.Origin
import dev.spendcodex.data.settings.Language
import dev.spendcodex.ui.journal.JournalScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * DeepSeek's path, against a stand-in server: only a line the rules could not read goes there, and only when it is turned on;
 * what comes back waits for "записать" and says when an amount is not one that was written.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class AiFlowTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer().also { it.start() }
    private val env = AppEnv(Language.Ru, modelUrl = server.url("/").toString())

    @After fun close() {
        env.close()
        server.shutdown()
    }

    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("condition not met in 5 s")
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun show() {
        runBlocking { env.ledger.seed(Language.Ru, "EUR") }
        val vm = env.journal()
        compose.setContent {
            AppTheme(darkTheme = false) {
                val s by vm.state.collectAsState()
                JournalScreen(s, vm, vm.effects, onOpen = {}, onForm = {}, onSearch = {}, onSettings = {}, onAccount = {})
            }
        }
    }

    @Test
    fun `with DeepSeek off a line without an amount stays here`() {
        show()
        compose.onNodeWithContentDescription("кофе 4.50").performTextInput("кофе с круассаном как вчера")
        settle { has("preview-problem") }
        compose.onNodeWithContentDescription("Записать").performClick()
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with DeepSeek on, the unread line is read there and recorded only after confirming`() {
        env.secrets.saveKey("sk-test-0123456789")
        env.settings.setAiEnabled(true)
        val args = """{"operations":[{"type":"expense","amount":"4.50","description":"кофе с круассаном","category":"Кафе и рестораны"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"choices":[{"message":{"tool_calls":[{"id":"1","type":"function","function":{"name":"record_operations","arguments":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(args))}}}]}}]}""",
        ))
        show()
        // a line the rules read fully never leaves the phone
        compose.onNodeWithContentDescription("кофе 4.50").performTextInput("кофе с круассаном как вчера")
        settle { has("will-ask-ai") }
        compose.onNodeWithContentDescription("Записать").performClick()
        settle { has("ai-result") }
        assertEquals(1, server.requestCount)
        compose.onNodeWithText("кофе с круассаном · Кафе и рестораны · Карта").assertExists()
        // 4.50 was not written in the line: the panel says so
        compose.onNodeWithText("суммы нет в тексте: проверь").assertExists()
        assertEquals(0, runBlocking { env.ledger.count.first() })
        compose.onNodeWithTag("ai-record").performClick()
        settle { runBlocking { env.ledger.count.first() } == 1 }
        val row = runBlocking { env.ledger.feedNow(Filter(), 5) }.single()
        val tx = runBlocking { env.ledger.get(row.id)!! }
        assertEquals(Origin.Ai, tx.origin)
        assertEquals("кофе с круассаном как вчера", tx.inputText)
        assertEquals(450L, tx.amountMinor)
        assertTrue(server.takeRequest().body.readUtf8().contains("кофе с круассаном как вчера"))
    }
}
