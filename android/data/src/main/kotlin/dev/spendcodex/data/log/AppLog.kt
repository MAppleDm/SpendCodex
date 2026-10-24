package dev.spendcodex.data.log

import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The app's own log, for finding out what went wrong on the phone: lines with time, level and place, written in the
 * background to one folder of the app's private storage (one file a day, a new one past [MAX_FILE], at most [MAX_TOTAL] in
 * all, the oldest going first) and mirrored to logcat. The person sends it from the settings as a .zip.
 *
 * What was typed and what DeepSeek answered is in it, since that is what a wrong reading is found by; the DeepSeek key
 * never is (anything shaped like one is masked). Not in Android backups; "erase everything" clears it.
 */
object AppLog {
    const val MAX_FILE = 8L * 1024 * 1024
    // as good as no limit (the author's wish): a busy year of logs is a few tens of megabytes
    const val MAX_TOTAL = 2L * 1024 * 1024 * 1024
    private const val PREFIX = "spendcodex-"

    @Volatile private var dir: File? = null
    @Volatile private var catching = false
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "AppLog").apply { isDaemon = true } }
    private var current: File? = null
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx")
    private val keyLike = Regex("sk-[A-Za-z0-9_\\-]{8,}")

    /** Starts writing to [folder]; before this, lines only go to logcat. */
    fun init(folder: File) {
        folder.mkdirs()
        dir = folder
        writer.execute { current = null; trim() }
    }

    fun d(tag: String, message: String) = log('D', tag, message, null)
    fun i(tag: String, message: String) = log('I', tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log('E', tag, message, error)

    private fun log(level: Char, tag: String, message: String, error: Throwable?) {
        val text = mask(message + (error?.let { "\n" + trace(it) } ?: ""))
        runCatching {
            val priority = when (level) { 'D' -> android.util.Log.DEBUG; 'I' -> android.util.Log.INFO; 'W' -> android.util.Log.WARN; else -> android.util.Log.ERROR }
            // logcat cuts long entries; the file keeps them whole
            text.chunked(3_500).forEach { android.util.Log.println(priority, "SpendCodex/$tag", it) }
        }
        if (dir == null) return
        val line = "${OffsetDateTime.now().format(stamp)} $level/$tag [${Thread.currentThread().name}] $text\n"
        runCatching { writer.execute { write(line) } }
    }

    /** Waits until what was logged so far is in the files: before a crash ends the process, before sending them. */
    fun flush(timeoutMs: Long = 2_000) {
        runCatching { writer.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** The log files, oldest first. */
    fun files(): List<File> = dir?.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }.orEmpty().sortedWith(compareBy({ it.lastModified() }, { it.name }))

    fun size(): Long = files().sumOf { it.length() }

    /** Deletes every log file; logging goes on into a new one. */
    fun clear() {
        runCatching { writer.submit { current = null; files().forEach { it.delete() } }.get(5, TimeUnit.SECONDS) }
    }

    /** All log files, then [extra] files (name to text), into one .zip. */
    fun zip(out: OutputStream, extra: Map<String, String> = emptyMap()) {
        flush()
        ZipOutputStream(out).use { zip ->
            extra.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            files().forEach { file ->
                zip.putNextEntry(ZipEntry("logs/${file.name}").apply { time = file.lastModified() })
                // a file still being written is copied as far as it goes
                runCatching { file.inputStream().use { it.copyTo(zip) } }
                zip.closeEntry()
            }
        }
    }

    /** Writes the crash itself, then lets the system's handler end the app as before. */
    fun catchCrashes() {
        if (catching) return
        catching = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Crash", "uncaught in thread ${thread.name}", error)
            flush()
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun mask(text: String) = text.replace(keyLike, "sk-…")

    private fun trace(error: Throwable) = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    // ---------- on the writer thread ----------

    private fun write(line: String) {
        val folder = dir ?: return
        runCatching {
            val today = PREFIX + LocalDate.now()
            val file = current?.takeIf { it.parentFile == folder && it.name.startsWith(today) && it.exists() && it.length() < MAX_FILE } ?: next(folder, today)
            file.appendText(line, Charsets.UTF_8)
        }
    }

    /** Today's file with room left, or a new one, after making room for it. */
    private fun next(folder: File, today: String): File {
        folder.mkdirs()
        val file = generateSequence(1) { it + 1 }.map { n -> File(folder, if (n == 1) "$today.log" else "$today-$n.log") }
            .first { !it.exists() || it.length() < MAX_FILE }
        current = file
        trim()
        return file
    }

    private fun trim() {
        val all = files()
        var total = all.sumOf { it.length() }
        for (file in all) {
            if (total <= MAX_TOTAL - MAX_FILE || file == current) continue
            total -= file.length()
            file.delete()
        }
    }
}
