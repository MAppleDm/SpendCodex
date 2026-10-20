package dev.spendcodex.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendcodex.AppEnv
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.domain.TxType
import dev.spendcodex.data.repo.SaveResult
import dev.spendcodex.data.settings.Language
import dev.spendcodex.ui.journal.JournalScreen
import dev.spendcodex.ui.operation.OperationScreen
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JournalFlowTest {
    @get:Rule val compose = createComposeRule()
    private val env = AppEnv(Language.Ru)
    @Before fun seed() = runBlocking { env.ledger.seed(Language.Ru, "EUR") }
    @After fun close() = env.close()

    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("condition not met")
    }

    private fun screenshot(name: String) {
        val dir = System.getProperty("screenshots.dir")?.let(::File) ?: return
        dir.mkdirs()
        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            File(dir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun `manual entry is beside search and does not interrupt typing`() {
        var forms = 0
        var settings = 0
        val vm = env.journal()
        compose.setContent { AppTheme(darkTheme = false) {
            val s by vm.state.collectAsState()
            JournalScreen(s, vm, vm.effects, {}, { forms++ }, {}, {}, { settings++ })
        } }
        settle { vm.state.value.loaded }
        compose.onNodeWithContentDescription("кофе 4.50").performTextInput("кофе 350")
        compose.onNodeWithTag("open-form").performClick()
        assertEquals(1, forms)
        assertEquals("кофе 350", vm.state.value.composer.text)
        val form = compose.onNodeWithTag("open-form").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("open-search").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot
        assertEquals(search.top, form.top, 0.1f)
        assertTrue(form.bottom < input.top)
        compose.onNodeWithTag("balances").assertDoesNotExist()
        compose.onNodeWithTag("connect-ai").performClick()
        assertEquals(1, settings)
        screenshot("ai-expense-empty.png")
    }

    @Test fun `old income remains stored but the feed and daily total contain only expenses and refunds`() {
        runBlocking {
            env.ledger.save(Draft(type = TxType.Expense, amount = "10", description = "кофе"))
            env.ledger.save(Draft(type = TxType.Income, amount = "3000", description = "старая зарплата"))
            env.ledger.save(Draft(type = TxType.Refund, amount = "2", description = "возврат кофе"))
        }
        val vm = env.journal()
        compose.setContent { AppTheme(darkTheme = false) {
            val s by vm.state.collectAsState()
            JournalScreen(s, vm, vm.effects, {}, {}, {}, {}, {})
        } }
        settle { vm.state.value.rows.size == 2 && vm.state.value.todayTotals.size == 2 }
        compose.onNodeWithText("старая зарплата").assertDoesNotExist()
        assertTrue(vm.state.value.todayTotals.all { it.type in setOf("expense", "refund") })
        assertEquals(3, runBlocking { env.ledger.feedNow(Filter(), 10).size })
        val search = env.search()
        search.open("old-income", Filter(text = "старая"))
        settle { search.state.value.loaded }
        assertTrue(search.state.value.rows.isEmpty())
        search.onType(TxType.Income)
        settle { search.state.value.rows.size == 1 }
        assertEquals(setOf(TxType.Income), search.exportFilter().types)
        screenshot("ai-expense-journal.png")
    }

    @Test fun `manual form records an expense without income or balance controls`() {
        val vm = env.operation()
        vm.open("manual", OperationRequest.New(Draft(amount = "12.34", description = "обед")))
        compose.setContent { AppTheme(darkTheme = false) {
            val s by vm.state.collectAsState()
            OperationScreen(s, vm, {})
        } }
        settle { !vm.state.value.form.loading }
        compose.onNodeWithTag("type-income").assertDoesNotExist()
        compose.onNodeWithTag("type-transfer").assertDoesNotExist()
        compose.onNodeWithTag("form-save").performClick()
        settle { runBlocking { env.ledger.feedNow(Filter(), 10).size == 1 } }
        assertEquals("expense", runBlocking { env.ledger.feedNow(Filter(), 10).single().type })
    }

    @Test fun `editing a legacy income preserves its type and metadata`() {
        val tx = runBlocking { (env.ledger.save(Draft(type = TxType.Income, amount = "3000", description = "зарплата")) as SaveResult.Saved).tx }
        val vm = env.operation()
        vm.open("legacy", OperationRequest.Edit(tx.id))
        compose.setContent { AppTheme(darkTheme = false) {
            val s by vm.state.collectAsState()
            OperationScreen(s, vm, {})
        } }
        settle { !vm.state.value.form.loading }
        compose.runOnIdle { vm.update { it.copy(note = "из старого журнала") } }
        compose.onNodeWithTag("form-save").performClick()
        settle { runBlocking { env.ledger.get(tx.id)?.note == "из старого журнала" } }
        assertEquals(tx.copy(note = "из старого журнала"), runBlocking { env.ledger.get(tx.id) })
    }
}
