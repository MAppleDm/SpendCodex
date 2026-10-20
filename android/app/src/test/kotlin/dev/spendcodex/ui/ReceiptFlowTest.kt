package dev.spendcodex.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
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
import dev.spendcodex.data.settings.Language
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptFlowTest {
    @get:Rule val compose = createComposeRule()
    private val env = AppEnv(Language.Ru)
    private val vm = env.operation()
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

    private fun show() {
        vm.open("receipt-form", OperationRequest.New(Draft(amount = "12.34", description = "Продукты")))
        compose.setContent {
            AppTheme(darkTheme = false) {
                val s by vm.state.collectAsState()
                OperationScreen(s, vm, onBack = {})
            }
        }
        settle { !vm.state.value.form.loading }
    }

    private fun picture(): File = File(env.context.cacheDir, "test-receipt.jpg").also { file ->
        val image = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            val p = Paint().apply { color = Color.BLACK; textSize = 30f; isAntiAlias = true }
            drawText("SPEND CODEX", 32f, 80f, p)
            for (i in 1..12) drawText("Item $i ....... 1.00", 32f, 100f + i * 50, p)
            drawText("TOTAL ........ 12.34", 32f, 900f, p)
        }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }; image.recycle()
    }

    private fun screenshot(name: String) {
        val dir = System.getProperty("screenshots.dir")?.let(::File) ?: return
        dir.mkdirs()
        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            File(dir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun `gallery photos stay pending viewer removal has undo and save keeps the image`() {
        show()
        val uri = Uri.fromFile(picture())
        compose.runOnIdle { vm.onPhotosPicked(listOf(uri, uri)) }
        settle { !vm.state.value.form.photoBusy && vm.state.value.form.photos.size == 1 }
        assertTrue(runBlocking { env.ledger.feedNow(Filter(), 10) }.isEmpty())
        val photo = vm.state.value.form.photos.single()
        compose.onNodeWithTag("receipt-${photo.receipt.id}").performScrollTo().performClick()
        compose.onNodeWithTag("receipt-viewer").assertExists()
        screenshot("receipt-viewer.png")
        compose.onNodeWithTag("receipt-remove").performClick()
        settle { vm.state.value.form.photos.isEmpty() }
        compose.onNodeWithTag("receipt-undo").performScrollTo().performClick()
        settle { vm.state.value.form.photos.size == 1 }
        screenshot("receipt-form.png")
        compose.onNodeWithTag("form-save").performClick()
        settle { runBlocking { env.ledger.feedNow(Filter(), 10) }.size == 1 }
        val saved = runBlocking { env.ledger.feedNow(Filter(), 10) }.single()
        assertEquals(1, saved.receiptCount)
        assertArrayEquals(photo.bytes, runBlocking { env.ledger.receiptBytes(photo.receipt.id) })
    }

    @Test
    @Config(shadows = [CameraUriShadow::class])
    fun `camera output is read after confirmation and cancellation leaves no photo`() {
        show()
        var target: Uri? = null
        compose.runOnIdle { target = vm.cameraTarget() }
        assertNotNull("context=${env.context.packageName}; provider=${env.context.packageManager.resolveContentProvider("${env.context.packageName}.files", android.content.pm.PackageManager.GET_META_DATA)}; error=${vm.state.value.form.photoMessage}", target)
        assertEquals("content", target!!.scheme)
        File(env.context.cacheDir, "camera/${target!!.lastPathSegment}").writeBytes(picture().readBytes())
        compose.runOnIdle { vm.onCameraResult(true) }
        settle { !vm.state.value.form.photoBusy && vm.state.value.form.photos.size == 1 }
        assertTrue(File(env.context.cacheDir, "camera").listFiles()!!.isEmpty())
        compose.runOnIdle { vm.cameraTarget(); vm.onCameraResult(false) }
        assertEquals(1, vm.state.value.form.photos.size)
        assertTrue(File(env.context.cacheDir, "camera").listFiles()!!.isEmpty())
    }

    @Test fun `leaving an edited photo list does not delete the saved receipt`() {
        show()
        compose.runOnIdle { vm.onPhotosPicked(listOf(Uri.fromFile(picture()))) }
        settle { !vm.state.value.form.photoBusy && vm.state.value.form.photos.size == 1 }
        compose.onNodeWithTag("form-save").performClick()
        settle { runBlocking { env.ledger.feedNow(Filter(), 10) }.isNotEmpty() }
        val id = runBlocking { env.ledger.feedNow(Filter(), 10) }.single().id
        compose.runOnIdle { vm.open("edit", OperationRequest.Edit(id)) }
        settle { !vm.state.value.form.loading && vm.state.value.form.photos.size == 1 }
        val photo = vm.state.value.form.photos.single()
        compose.runOnIdle { vm.onRemovePhoto(photo.receipt.id); vm.open("abandon", OperationRequest.New(Draft(amount = "5"))) }
        settle { !vm.state.value.form.loading && vm.state.value.form.key == "abandon" }
        assertEquals(1, runBlocking { env.ledger.receiptsOf(id) }.size)
        assertNotNull(runBlocking { env.ledger.receiptBytes(photo.receipt.id) })
    }
}

/** AndroidX FileProvider uses '/' for canonical-root matching; that cannot match Windows JVM paths.
 * The real provider and camera round-trip are checked on Android; here only its URI boundary is substituted. */
@Implements(value = androidx.core.content.FileProvider::class, isInAndroidSdk = false)
class CameraUriShadow {
    companion object {
        @JvmStatic @Implementation
        fun getUriForFile(context: android.content.Context, authority: String, file: File): Uri =
            Uri.parse("content://$authority/camera/${file.name}")
    }
}
