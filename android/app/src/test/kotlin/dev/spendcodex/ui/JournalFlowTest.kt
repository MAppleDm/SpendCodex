package dev.spendcodex.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
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
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.settings.Language
import dev.spendcodex.ui.journal.JournalScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The everyday path, through the real journal: type a line, see what it means, record it, take it back. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class JournalFlowTest {
    @get:Rule val compose = createComposeRule()
    private val env = AppEnv(Language.Ru)

    @Before fun seed() = runBlocking { env.ledger.seed(Language.Ru, "EUR") }

    @After fun close() = env.close()

    private fun count() = runBlocking { env.ledger.count.first() }

    private lateinit var vm: dev.spendcodex.ui.journal.JournalViewModel

    private fun show() {
        vm = env.journal()
        compose.setContent {
            AppTheme(darkTheme = false) {
                val s by vm.state.collectAsState()
                JournalScreen(s, vm, vm.effects, onOpen = {}, onForm = {}, onSearch = {}, onSettings = {}, onAccount = {})
            }
        }
    }

    /**
     * Waits in real time: the view model's work hops between Room's threads and the main looper, which Robolectric only
     * runs when asked, so each round lets time pass and runs what was posted to it.
     */
    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("condition not met in 5 s")
    }

    private fun waitFor(tag: String) = settle { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `a line is read word by word, recorded with one tap, and can be taken back`() {
        show()
        compose.onNodeWithTag("empty").assertExists()
        compose.onNodeWithContentDescription("кофе 4.50").performTextInput("кофе 4.50")
        waitFor("token-amount")
        compose.onNodeWithTag("token-amount").assertTextEquals("4,50 €")
        compose.onNodeWithTag("token-category").assertTextContains("Кафе", substring = true)
        compose.onNodeWithTag("token-account").assertTextEquals("Карта")
        compose.onNodeWithTag("token-type").assertTextEquals("расход")

        compose.onNodeWithContentDescription("Записать").performClick()
        settle { count() == 1 }
        waitFor("operation")
        val tx = runBlocking { env.ledger.feedNow(Filter(), 10) }.single()
        assertEquals("кофе", tx.description)
        assertEquals(450L, tx.amountMinor)
        assertEquals("Кафе и рестораны", tx.categoryName)
        val stored = runBlocking { env.ledger.get(tx.id)!! }
        assertEquals(Origin.Line, stored.origin)
        assertEquals("кофе 4.50", stored.inputText)

        // the line is empty again, and the notice offers to take it back
        waitFor("notice")
        compose.onNodeWithTag("notice-action").performClick()
        settle { count() == 0 }
    }

    @Test
    fun `a line without an amount cannot be recorded and says why`() {
        show()
        compose.onNodeWithContentDescription("кофе 4.50").performTextInput("кофе")
        waitFor("preview-problem")
        compose.onNodeWithTag("preview-problem").assertTextEquals("Не вижу суммы.")
        compose.onNodeWithContentDescription("Записать").performClick()
        compose.waitForIdle()
        assertEquals(0, count())
    }

    @Test
    fun `tapping a word of the preview changes it before recording`() {
        show()
        compose.onNodeWithContentDescription("кофе 4.50").performTextInput("такси 12")
        waitFor("token-type")
        compose.onNodeWithTag("token-category").assertTextEquals("Транспорт")
        compose.onNodeWithTag("token-type").performClick()
        waitFor("picker")
        compose.onNodeWithText("Доход").performClick()
        settle { compose.onAllNodesWithTag("picker").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("token-type").assertTextEquals("доход")
        // an expense category does not stay on an income
        compose.onNodeWithTag("token-category").assertTextEquals("без категории")
        compose.onNodeWithTag("token-account").performClick()
        waitFor("picker")
        compose.onNodeWithText("Наличные").performClick()
        settle { compose.onAllNodesWithTag("picker").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Записать").performClick()
        settle { count() == 1 }
        val tx = runBlocking { env.ledger.feedNow(Filter(), 10) }.single()
        assertEquals(TxType.Income.code, tx.type)
        assertEquals("Наличные", tx.accountName)
        assertEquals(null, tx.categoryId)
    }

    @Test
    fun `several operations in one line are recorded together`() {
        show()
        compose.onNodeWithContentDescription("кофе 4.50").performTextInput("кофе 4.50, круассан 3")
        settle { compose.onAllNodesWithTag("token-amount").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithContentDescription("Записать").performClick()
        settle { count() == 2 }
        assertTrue(runBlocking { env.ledger.feedNow(Filter(), 10) }.map { it.description }.containsAll(listOf("кофе", "круассан")))
    }
}
