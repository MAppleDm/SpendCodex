package dev.spendcodex.data.backup

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/** ZIPs with checked, bounded streams. Image collections are never accumulated in memory. */
object Archive {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true; explicitNulls = true }
    private val reader = Json { ignoreUnknownKeys = false }
    const val MANIFEST = "manifest.json"
    const val DATA = "data/spendcodex.json"
    const val MAX_BYTES = 512L * 1024 * 1024
    const val MAX_DATA_BYTES = 128L * 1024 * 1024
    const val MAX_ENTRIES = 10_000

    class Entry(val path: String, val size: Long, val hash: String, val open: suspend () -> InputStream) {
        constructor(path: String, bytes: ByteArray) : this(path, bytes.size.toLong(), sha256(bytes), { ByteArrayInputStream(bytes) })
    }

    suspend fun write(out: OutputStream, root: String, kind: String, createdAt: String, app: AppDto, counts: Counts, entries: List<Entry>) {
        require(entries.size < MAX_ENTRIES && entries.sumOf { it.size } <= MAX_BYTES - 4 * 1024 * 1024) { "archive too large" }
        require(entries.map { it.path }.distinct().size == entries.size && entries.all { safePath(it.path) })
        require(entries.all { it.size >= 0 && it.size <= if (it.path.endsWith(".jpg"))
            dev.spendcodex.data.receipts.ReceiptLimits.MAX_BYTES.toLong() else MAX_DATA_BYTES }) { "entry too large" }
        val manifest = Manifest(kind = kind, createdAt = createdAt, app = app, counts = counts,
            files = entries.map { ManifestFile(it.path, it.size, it.hash) })
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("$root/$MANIFEST"))
            zip.write((json.encodeToString(Manifest.serializer(), manifest) + "\n").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (entry in entries) {
                zip.putNextEntry(ZipEntry("$root/${entry.path}"))
                val digest = MessageDigest.getInstance("SHA-256")
                val size = entry.open().use { copyChecked(it, zip, entry.size, digest) }
                check(size == entry.size && hex(digest.digest()) == entry.hash) { "changed ${entry.path}" }
                zip.closeEntry()
            }
        }
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun safePath(path: String) = path.isNotEmpty() && '\\' !in path && ':' !in path && '\u0000' !in path &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    sealed interface Opened {
        class Data(val bytes: ByteArray, val manifest: Manifest?, val files: Map<String, File>, private val staging: File) : Opened, Closeable {
            override fun close() { staging.deleteRecursively() }
        }
        class Failed(val problem: ImportProblem, val detail: String? = null) : Opened
    }

    /** Files get generated numeric names in [staging]; archive paths are only map keys, never filesystem paths. */
    fun open(input: InputStream, staging: File): Opened {
        try {
            val buffered = BufferedInputStream(input)
            buffered.mark(4)
            val head = ByteArray(4)
            var n = 0
            while (n < 4) {
                val read = buffered.read(head, n, 4 - n)
                if (read < 0) break
                n += read
            }
            buffered.reset()
            val isZip = n == 4 && head[0] == 80.toByte() && head[1] == 75.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
            if (!isZip) {
                val out = ByteArrayOutputStream()
                copyChecked(buffered, out, MAX_DATA_BYTES)
                return Opened.Data(out.toByteArray(), null, emptyMap(), staging)
            }
            val raw = LinkedHashMap<String, File>()
            val sums = HashMap<String, Pair<Long, String>>()
            var total = 0L
            var count = 0
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) return Opened.Failed(ImportProblem.TooLarge)
                    if (!safePath(entry.name.removeSuffix("/"))) return Opened.Failed(ImportProblem.Damaged, "unsafe path")
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name in raw) return Opened.Failed(ImportProblem.Damaged, "duplicate $name")
                    val file = File(staging, "$count.part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val perFile = if (name.endsWith(".jpg")) dev.spendcodex.data.receipts.ReceiptLimits.MAX_BYTES.toLong()
                        else if (name.endsWith(MANIFEST)) 4L * 1024 * 1024 else MAX_DATA_BYTES
                    val size = file.outputStream().use { copyChecked(zip, it, minOf(MAX_BYTES - total, perFile), digest) }
                    total += size
                    raw[name] = file
                    sums[name] = size to hex(digest.digest())
                }
            }
            val anchors = raw.keys.filter { it == MANIFEST || it.endsWith("/$MANIFEST") && it.count { c -> c == '/' } == 1 }
            if (anchors.size != 1) return Opened.Failed(ImportProblem.NotSpendCodex, "manifest.json")
            val root = anchors.single().removeSuffix(MANIFEST)
            if (raw.keys.any { !it.startsWith(root) }) return Opened.Failed(ImportProblem.Damaged, "multiple roots")
            val files = raw.mapKeys { it.key.removePrefix(root) }
            val manifest = try { reader.decodeFromString(Manifest.serializer(), files.getValue(MANIFEST).readText()) }
                catch (e: Exception) { return Opened.Failed(ImportProblem.Damaged, "manifest.json") }
            if (manifest.format != "spendcodex-archive") return Opened.Failed(ImportProblem.NotSpendCodex)
            if (manifest.formatVersion > FORMAT_VERSION) return Opened.Failed(ImportProblem.TooNew)
            if (manifest.formatVersion < 1) return Opened.Failed(ImportProblem.UnsupportedVersion)
            if (manifest.kind != "backup") return Opened.Failed(ImportProblem.Partial)
            val paths = manifest.files.map { it.path }
            if (paths.distinct().size != paths.size || paths.any { !safePath(it) } || paths.toSet() != files.keys - MANIFEST) {
                return Opened.Failed(ImportProblem.Damaged, "file list")
            }
            for (file in manifest.files) {
                val sum = sums[root + file.path]
                if (sum == null || sum.first != file.bytes || sum.second != file.sha256) return Opened.Failed(ImportProblem.Damaged, file.path)
            }
            val data = files[DATA] ?: return Opened.Failed(ImportProblem.NotSpendCodex, "no $DATA")
            return Opened.Data(data.readBytes(), manifest, files, staging)
        } catch (e: LimitExceeded) {
            return Opened.Failed(ImportProblem.TooLarge)
        } catch (e: IOException) {
            return Opened.Failed(ImportProblem.NotReadable, e.message)
        } catch (e: IllegalArgumentException) {
            return Opened.Failed(ImportProblem.NotReadable, e.message)
        }
    }

    private class LimitExceeded : IOException()
    private fun copyChecked(input: InputStream, out: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw LimitExceeded()
            digest?.update(buffer, 0, n)
            out.write(buffer, 0, n)
        }
        return total
    }
}
