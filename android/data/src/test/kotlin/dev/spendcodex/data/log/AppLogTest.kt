package dev.spendcodex.data.log

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun `lines go to one folder, a key never does, and the folder comes out as a zip`() {
        val folder = Files.createTempDirectory("logs").toFile()
        AppLog.init(folder)
        AppLog.i("DeepSeek", "reading a line: кофе 4,50")
        AppLog.w("DeepSeek", "refused: Authentication Fails (sk-test-0123456789abcdef)")
        AppLog.e("Journal", "recording failed", IllegalStateException("disk full"))
        AppLog.flush()

        val file = AppLog.files().single()
        assertEquals(folder, file.parentFile)
        val text = file.readText()
        assertTrue(text.contains(" I/DeepSeek [") && text.contains("reading a line: кофе 4,50"))
        assertFalse("the key must not be written", text.contains("0123456789abcdef"))
        assertTrue(text.contains("Authentication Fails (sk-…)"))
        assertTrue(text.contains("java.lang.IllegalStateException: disk full") && text.contains("\tat "))

        val zip = ByteArrayOutputStream().also { AppLog.zip(it, mapOf("device.txt" to "Pixel")) }
        val entries = ZipInputStream(zip.toByteArray().inputStream()).use { input ->
            generateSequence { input.nextEntry }.associate { it.name to input.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals("Pixel", entries["device.txt"])
        assertEquals(text, entries["logs/${file.name}"])

        AppLog.clear()
        assertTrue(AppLog.files().isEmpty())
        AppLog.i("Settings", "erased everything")
        AppLog.flush()
        assertTrue(AppLog.files().single().readText().contains("erased everything"))
        folder.deleteRecursively()
    }
}
