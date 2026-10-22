package dev.spendcodex.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendcodex.data.backup.*
import dev.spendcodex.data.domain.Draft
import dev.spendcodex.data.domain.Filter
import dev.spendcodex.data.receipts.*
import dev.spendcodex.data.repo.SaveResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptTest {
    private val envs = mutableListOf<TestEnv>()
    private fun fresh() = TestEnv().also { envs += it }
    private val env = fresh()
    @After fun close() { envs.forEach { it.close() } }

    private fun photo(tx: String, number: Int = 1): ReceiptPhoto {
        val image = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; textSize = 24f; isAntiAlias = true }
            drawText("RECEIPT $number", 32f, 60f, paint)
            for (i in 1..20) drawText("Item $i .......... ${(i + number) * 19}.50", 32f, 70f + i * 38, paint)
        }
        return try { ReceiptImages.compress(image, 1, tx, env.clock.millis()) } finally { image.recycle() }
    }

    private suspend fun journal(): Draft {
        val a = env.account("Card")
        val d = Draft(amount = "12.34", accountId = a, description = "Groceries")
        env.ledger.save(d, listOf(photo(d.id)))
        return d.copy(createdAt = env.ledger.get(d.id)!!.createdAt)
    }

    private suspend fun export(kind: ExportKind = ExportKind.Backup, e: TestEnv = env) =
        ByteArrayOutputStream().also { e.backups.write(kind, ExportScope(), it) }.toByteArray()

    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { files ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()

    private fun renewManifest(files: MutableMap<String, ByteArray>) {
        val key = files.keys.single { it.endsWith("/manifest.json") }
        val root = key.removeSuffix("manifest.json")
        val old = Json.decodeFromString(Manifest.serializer(), String(files.getValue(key)))
        files[key] = Json.encodeToString(Manifest.serializer(), old.copy(files = files.filterKeys { it != key }.map { (path, bytes) ->
            ManifestFile(path.removePrefix(root), bytes.size.toLong(), Archive.sha256(bytes))
        })).toByteArray()
    }

    private suspend fun unchangedAfterFailure(bytes: ByteArray) {
        val before = Codec.encode(env.backups.file(ExportScope(includeReceipts = true)))
        assertTrue(env.backups.read(ByteArrayInputStream(bytes)) is ReadResult.Failed)
        assertEquals(before, Codec.encode(env.backups.file(ExportScope(includeReceipts = true))))
    }

    @Test fun `gallery compresses without changing the source and removes EXIF`() {
        val source = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val file = File(env.context.cacheDir, "source-${UUID.randomUUID()}.jpg")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }; source.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "55/1,0/1,0/1")
            saveAttributes()
        }
        val before = file.readBytes()
        val prepared = ReceiptImages.read(env.context, Uri.fromFile(file), "tx", env.clock.millis())
        assertArrayEquals(before, file.readBytes())
        assertEquals(1600, prepared.receipt.width)
        assertEquals(2133, prepared.receipt.height)
        assertTrue(ReceiptImages.valid(prepared.bytes!!, prepared.receipt))
        val exif = ExifInterface(ByteArrayInputStream(prepared.bytes))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertTrue(prepared.bytes.size < before.size)
        file.delete()
    }

    @Test fun `long receipts keep detail and small photos are not enlarged`() {
        assertEquals(600 to 1000, ReceiptImages.fit(600, 1000))
        assertEquals(683 to 8192, ReceiptImages.fit(1000, 12000))
        val (w, h) = ReceiptImages.fit(10000, 20000)
        assertTrue(w.toLong() * h <= ReceiptLimits.MAX_PIXELS)
        assertTrue(w <= 1600 && h <= 8192)
    }

    @Test fun `all eight camera orientations including mirrors become upright pixels`() {
        val src = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        Canvas(src).apply {
            val p = Paint()
            p.color = Color.RED; drawRect(0f, 0f, 50f, 30f, p)
            p.color = Color.GREEN; drawRect(50f, 0f, 100f, 30f, p)
            p.color = Color.BLUE; drawRect(0f, 30f, 50f, 60f, p)
            p.color = Color.YELLOW; drawRect(50f, 30f, 100f, 60f, p)
        }
        val expected = listOf(Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE, Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN)
        for (orientation in 1..8) {
            val p = ReceiptImages.compress(src, orientation, "tx", 1)
            val result = BitmapFactory.decodeByteArray(p.bytes!!, 0, p.bytes.size)
            assertEquals(if (orientation >= 5) 60 else 100, result.width)
            val pixel = result.getPixel(8, 8)
            val target = expected[orientation - 1]
            assertTrue("orientation $orientation", kotlin.math.abs(Color.red(pixel) - Color.red(target)) < 30 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(target)) < 30 && kotlin.math.abs(Color.blue(pixel) - Color.blue(target)) < 30)
            result.recycle()
        }
        src.recycle()
    }

    @Test fun `not an image is rejected and temporary source is removed`() {
        val file = File(env.context.cacheDir, "not-a-photo.txt").also { it.writeText("hello") }
        val before = env.context.cacheDir.listFiles()!!.map { it.name }.toSet()
        assertThrows(IllegalArgumentException::class.java) { ReceiptImages.read(env.context, Uri.fromFile(file), "tx", 1) }
        assertEquals(before, env.context.cacheDir.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun `saving editing deletion undo and repeat keep correct receipt ownership`() = runTest {
        val d = journal()
        val original = env.ledger.receiptsOf(d.id).single()
        val bytes = env.ledger.receiptBytes(original.id)!!
        assertEquals(1, env.ledger.feedNow(Filter(), 10).single().receiptCount)
        env.tick()
        env.ledger.save(d.copy(description = "changed"), listOf(ReceiptPhoto(original)))
        assertEquals(original, env.ledger.receiptsOf(d.id).single())
        assertArrayEquals(bytes, env.ledger.receiptBytes(original.id))
        val repeated = env.ledger.repeat(d.id) as SaveResult.Saved
        assertTrue(env.ledger.receiptsOf(repeated.tx.id).isEmpty())
        val removed = env.ledger.delete(d.id)!!
        assertNull(env.ledger.receiptBytes(original.id))
        env.ledger.undo(removed)
        assertEquals(original, env.ledger.receiptsOf(d.id).single())
        assertArrayEquals(bytes, env.ledger.receiptBytes(original.id))
        env.ledger.eraseAll()
        assertNull(env.ledger.receiptBytes(original.id))
        assertTrue(env.db.dao().allReceipts().isEmpty())
    }

    @Test fun `a receipt split by category keeps the photo with every part, all of them or none`() = runTest {
        val a = env.account("Card")
        val shot = photo("")
        val food = Draft(amount = "840.50", accountId = a, description = "продукты")
        val home = Draft(amount = "450", accountId = a, description = "бытовая химия")
        val bad = Draft(amount = "0", accountId = a, description = "ничего")
        val refused = env.ledger.saveWithPhotos(listOf(food to listOf(shot.copyFor(food.id)), bad to listOf(shot.copyFor(bad.id))))
        assertTrue(refused.single() is SaveResult.Invalid)
        assertNull(env.ledger.get(food.id))

        val items = listOf(food to listOf(shot.copyFor(food.id)), home to listOf(shot.copyFor(home.id)))
        assertTrue(env.ledger.saveWithPhotos(items).all { it is SaveResult.Saved && it.created })
        val receipts = listOf(food, home).map { env.ledger.receiptsOf(it.id).single() }
        assertEquals(2, receipts.map { it.id }.distinct().size)
        receipts.forEach { assertEquals(shot.receipt.sha256, it.sha256); assertArrayEquals(shot.bytes, env.ledger.receiptBytes(it.id)) }
        // sent twice: nothing is stored again
        assertTrue(env.ledger.saveWithPhotos(items).all { it is SaveResult.Saved && !it.created })
        assertEquals(1, env.ledger.receiptsOf(food.id).size)
    }

    @Test fun `a photo is sent to DeepSeek no larger than it would make it, and a small one as it is`() {
        val large = photo("tx").bytes!!
        val image = Bitmap.createBitmap(1600, 2800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val big = try { ReceiptImages.compress(image, 1, "tx", 0).bytes!! } finally { image.recycle() }
        val sent = ReceiptImages.forReading(big)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sent, 0, sent.size, bounds)
        assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= ReceiptLimits.READ_PIXELS)
        assertEquals(1600.0 / 2800, bounds.outWidth.toDouble() / bounds.outHeight, 0.01)
        assertSame(large, ReceiptImages.forReading(large))
    }

    @Test fun `invalid photo cannot partly save an operation or remove an existing photo`() = runTest {
        val d = journal()
        val old = env.ledger.get(d.id)
        val receipt = env.ledger.receiptsOf(d.id).single()
        val bad = photo(d.id, 2).let { it.copy(bytes = byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking {
            env.ledger.save(d.copy(amount = "99", tags = listOf("must-rollback")), listOf(bad))
        } }
        assertEquals(old, env.ledger.get(d.id))
        assertEquals(listOf(receipt), env.ledger.receiptsOf(d.id))
        assertTrue(env.ledger.tagsNow().isEmpty())
    }

    @Test fun `photo limit and duplicates cannot bypass journal validation`() = runTest {
        val d = journal()
        val p = photo(d.id, 2)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking {
            env.ledger.save(d, List(11) { i -> p.copy(receipt = p.receipt.copy(id = UUID.randomUUID().toString(), sha256 = "${i}")) })
        } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking {
            env.ledger.save(d, listOf(p, p.copy(receipt = p.receipt.copy(id = UUID.randomUUID().toString()))))
        } }
        assertEquals(1, env.ledger.receiptsOf(d.id).size)
    }

    @Test fun `full manual copy restores exact photo bytes and safety copy keeps replaced photos`() = runTest {
        val d = journal()
        val p2 = photo(d.id, 2)
        env.ledger.save(d, listOf(ReceiptPhoto(env.ledger.receiptsOf(d.id).single()), p2))
        val original = export()
        val other = fresh()
        val a = other.account("Other")
        val replaced = Draft(amount = "7", accountId = a)
        val replacedPhoto = photo(replaced.id, 3)
        other.ledger.save(replaced, listOf(replacedPhoto))
        val read = other.backups.read(ByteArrayInputStream(original)) as ReadResult.Ok
        assertEquals(2, read.preview.snapshot.receipts.size)
        assertEquals(1, read.preview.currentReceipts)
        other.backups.restore(read.preview)
        assertEquals(env.ledger.receiptsOf(d.id), other.ledger.receiptsOf(d.id))
        for (r in env.ledger.receiptsOf(d.id)) assertArrayEquals(env.ledger.receiptBytes(r.id), other.ledger.receiptBytes(r.id))
        val again = unzip(export(e = other))
        val before = unzip(original)
        assertEquals(before.keys, again.keys)
        for (key in before.keys) assertArrayEquals(key, before[key], again[key])
        val safety = other.backups.read(other.backups.safetyCopy.inputStream()) as ReadResult.Ok
        assertEquals(replacedPhoto.receipt.id, safety.preview.snapshot.receipts.single().id)
        val third = fresh()
        third.backups.restore((third.backups.read(other.backups.safetyCopy.inputStream()) as ReadResult.Ok).preview)
        assertArrayEquals(replacedPhoto.bytes, third.ledger.receiptBytes(replacedPhoto.receipt.id))
        safety.preview.close()
    }

    @Test fun `missing corrupted and substituted images are refused without changes`() = runTest {
        journal()
        val original = unzip(export())
        val path = original.keys.single { it.endsWith(".jpg") }
        unchangedAfterFailure(zip(original.toMutableMap().also { it.remove(path) }))
        unchangedAfterFailure(zip(original.toMutableMap().also { it[path] = byteArrayOf(1, 2, 3) }))
        // Even a newly signed manifest cannot make bytes disagree with their data-file metadata.
        unchangedAfterFailure(zip(original.toMutableMap().also { it[path] = photo("other", 8).bytes!!; renewManifest(it) }))
        unchangedAfterFailure(zip(original.toMutableMap().also { it[path] = ByteArray(ReceiptLimits.MAX_BYTES + 1); renewManifest(it) }))
    }

    @Test fun `invalid references and unsafe archive paths are refused`() = runTest {
        val d = journal()
        val original = unzip(export())
        val data = original.keys.single { it.endsWith(Archive.DATA) }
        unchangedAfterFailure(zip(original.toMutableMap().also {
            it[data] = String(it.getValue(data)).replace("\"transaction_id\": \"${d.id}\"", "\"transaction_id\": \"missing\"").toByteArray()
            renewManifest(it)
        }))
        unchangedAfterFailure(zip(original.toMutableMap().also { it["../escape.jpg"] = byteArrayOf(1); renewManifest(it) }))
        unchangedAfterFailure(zip(original.toMutableMap().also {
            val m = it.keys.single { k -> k.endsWith("manifest.json") }
            val parsed = Json.decodeFromString(Manifest.serializer(), String(it.getValue(m)))
            it[m] = Json.encodeToString(Manifest.serializer(), parsed.copy(files = parsed.files + parsed.files.first())).toByteArray()
        }))
    }

    @Test fun `lost staging image rolls restore back including live photos`() = runTest {
        val d = journal()
        val bytes = export()
        val old = env.ledger.receiptsOf(d.id).single()
        val preview = (env.backups.read(ByteArrayInputStream(bytes)) as ReadResult.Ok).preview
        preview.archive!!.files.getValue(old.path).delete()
        assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { env.backups.restore(preview) } }
        assertNotNull(env.ledger.get(d.id))
        assertEquals(listOf(old), env.ledger.receiptsOf(d.id))
        assertNotNull(env.ledger.receiptBytes(old.id))
    }

    @Test fun `analysis includes metadata without images and cannot masquerade as a complete copy`() = runTest {
        journal()
        val files = unzip(export(ExportKind.Ai))
        assertFalse(files.keys.any { it.endsWith(".jpg") })
        val data = files.entries.single { it.key.endsWith("/data.json") }.value
        assertTrue(String(data).contains("\"receipts_included\": false"))
        assertTrue(String(data).contains("receipts/"))
        val read = env.backups.read(ByteArrayInputStream(data)) as ReadResult.Failed
        assertEquals(ImportProblem.Partial, read.errors.single().problem)
        assertTrue(env.backups.read(ByteArrayInputStream(export(ExportKind.Ai))) is ReadResult.Failed)
    }

    @Test fun `legacy version one bare data and archive still restore`() = runTest {
        val a = env.account("Old")
        env.saved(Draft(amount = "4.50", accountId = a))
        val current = Json.parseToJsonElement(String(export(ExportKind.Json))).jsonObject
        val legacy = JsonObject(current.toMutableMap().apply {
            this["format_version"] = JsonPrimitive(1); this["schema_version"] = JsonPrimitive(1); remove("receipts")
            this["scope"] = JsonObject(current.getValue("scope").jsonObject.toMutableMap().apply { remove("receipts_included") })
        }).toString().toByteArray()
        val other = fresh()
        other.backups.restore((other.backups.read(ByteArrayInputStream(legacy)) as ReadResult.Ok).preview)
        assertEquals(450, other.ledger.feedNow(Filter(), 10).single().amountMinor.toInt())
        val m = Manifest(formatVersion = 1, kind = "backup", createdAt = Codec.instant(1), app = AppDto("SpendCodex", "0.1.0"),
            counts = Counts(1, 0, 0, 1), files = listOf(ManifestFile(Archive.DATA, legacy.size.toLong(), Archive.sha256(legacy))))
        val archive = zip(mapOf("old/manifest.json" to Json.encodeToString(Manifest.serializer(), m).toByteArray(), "old/${Archive.DATA}" to legacy))
        val p = (other.backups.read(ByteArrayInputStream(archive)) as ReadResult.Ok).preview
        assertTrue(p.snapshot.receipts.isEmpty()); p.close()
    }
}
