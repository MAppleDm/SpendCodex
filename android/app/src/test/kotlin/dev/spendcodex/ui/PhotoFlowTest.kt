package dev.spendcodex.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
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
import dev.spendcodex.data.settings.Language
import dev.spendcodex.ui.journal.AiUi
import dev.spendcodex.ui.journal.JournalScreen
import dev.spendcodex.ui.journal.JournalViewModel
import java.io.File
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
import org.robolectric.annotation.GraphicsMode

/** Receipt photos through the real UI, image code, HTTP client and database, against a stand-in for DeepSeek. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoFlowTest {
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

    private fun show() {
        runBlocking { env.ledger.seed(Language.Ru, "RUB") }
        env.secrets.saveKey("sk-test-0123456789")
        vm = env.journal()
        compose.setContent { AppTheme(darkTheme = false) {
            val s by vm.state.collectAsState()
            JournalScreen(s, vm, vm.effects, {}, {}, {}, {}, {})
        } }
        settle { vm.state.value.loaded }
    }

    private fun receiptPhoto(name: String = "receipt.jpg"): Uri = Uri.fromFile(File(env.context.cacheDir, name).also { file ->
        val image = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; textSize = 24f; isAntiAlias = true }
            drawText("ПЯТЁРОЧКА $name", 32f, 60f, paint)
            for (i in 1..12) drawText("Товар $i ........ ${i * 10}.00", 32f, 70f + i * 38, paint)
        }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        image.recycle()
    })

    private fun answer(args: String) = MockResponse().setResponseCode(200).setBody(
        """{"choices":[{"message":{"tool_calls":[{"id":"1","type":"function","function":{"name":"record_receipts","arguments":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(args))}}}]}}]}""",
    )
    private fun shop(total: String = "1290.50") = """{"status":"ok","receipts":[{"photos":[0],"payee":"Пятёрочка","date":"2026-10-19","time":"18:40","currency":"RUB","total":$total,"parts":[
        {"amount":"840.50","category":"Продукты","description":"продукты","items":["Молоко 89.90","Сыр 750.60"],"tags":["продукты"]},
        {"amount":"450","category":null,"description":"бытовая химия","items":["Средство для посуды 450.00"],"tags":["дом"]}]}]}"""

    @Test fun `a receipt photo is read, split by category, checked and recorded with the photo on every part`() {
        server.enqueue(answer(shop("\"1290.50\"")))
        show()
        compose.runOnIdle { vm.onText("такси 300") }
        compose.onNodeWithTag("open-photo").performClick()
        compose.onNodeWithTag("photo-choice").assertIsDisplayed()
        compose.onNodeWithTag("photo-gallery").assertIsDisplayed()
        compose.runOnIdle { vm.onPhotos(listOf(receiptPhoto())) }
        settle { vm.state.value.composer.ai is AiUi.Result }
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("data:image/jpeg;base64,") && body.contains("record_receipts"))

        // shown for a check, nothing recorded yet
        assertEquals(0, count())
        compose.onNodeWithTag("ai-photos").assertExists()
        compose.onAllNodesWithTag("ai-item").assertCountEquals(2)
        compose.onNodeWithText(Texts.PHOTO_RESULT).assertExists()
        compose.onNodeWithText("Молоко 89.90; Сыр 750.60").assertExists()
        compose.onNodeWithTag("ai-note").assertDoesNotExist()
        compose.onNodeWithTag("ai-record").performClick()
        settle { count() == 2 && vm.state.value.composer.ai == AiUi.Idle }

        val rows = runBlocking { env.ledger.feedNow(Filter(), 10) }
        assertEquals(setOf(84050L, 45000L), rows.map { it.amountMinor }.toSet())
        assertTrue(rows.all { it.payee == "Пятёрочка" && it.date == "2026-10-19" && it.receiptCount == 1 })
        val receipts = rows.map { runBlocking { env.ledger.receiptsOf(it.id).single() } }
        assertEquals(1, receipts.map { it.sha256 }.distinct().size)
        // the line was not part of it, and stays
        assertEquals("такси 300", vm.state.value.composer.text)
        assertFalse(vm.state.value.composer.fromPhotos)
    }

    @Test fun `parts that do not add up are flagged, and dropping the answer records nothing`() {
        server.enqueue(answer(shop("\"1300.00\"")))
        show()
        compose.runOnIdle { vm.onPhotos(listOf(receiptPhoto("a.jpg"), receiptPhoto("b.jpg"))) }
        settle { vm.state.value.composer.ai is AiUi.Result }
        val notes = (vm.state.value.composer.ai as AiUi.Result).notes
        assertTrue(notes.any { it.contains("не сходятся") && it.contains("Пятёрочка") })
        // the second photo is on no receipt
        assertTrue(notes.contains(Texts.photoUnread(2)))
        compose.onAllNodesWithTag("ai-note").assertCountEquals(2)
        compose.onNodeWithText(Texts.AI_DROP).performClick()
        settle { vm.state.value.composer.ai == AiUi.Idle }
        assertEquals(0, count())
        assertTrue(vm.state.value.composer.photos.isEmpty())
    }

    @Test fun `an unreadable photo explains itself and the manual form starts with the photo attached`() {
        server.enqueue(answer("""{"status":"unreadable","receipts":[]}"""))
        show()
        compose.runOnIdle { vm.onPhotos(listOf(receiptPhoto())) }
        settle { vm.state.value.composer.ai is AiUi.Failed }
        assertEquals(Texts.PHOTO_UNREADABLE, (vm.state.value.composer.ai as AiUi.Failed).message)
        compose.onNodeWithTag("ai-manual").assertExists()
        val start = vm.formStart()
        assertFalse(start.fromLine)
        assertEquals(start.draft.id, start.photos.single().receipt.transactionId)
        assertEquals(AiUi.Idle, vm.state.value.composer.ai)
    }

    @Test fun `without a key no photo is sent`() {
        show()
        env.secrets.clear()
        compose.runOnIdle { vm.onPhotos(listOf(receiptPhoto())) }
        settle { vm.state.value.composer.ai is AiUi.Failed }
        assertTrue((vm.state.value.composer.ai as AiUi.Failed).needsKey)
        assertEquals(0, server.requestCount)
    }
}
