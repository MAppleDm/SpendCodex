package dev.spendcodex.ui.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdatesModule {
    @Binds abstract fun updates(impl: GitHubUpdates): UpdateRepository
}

/** Downloads belong to Android, so leaving the screen or killing the app does not lose them. */
@Singleton
class GitHubUpdates @Inject constructor(@ApplicationContext private val context: Context) : UpdateRepository {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(DownloadManager::class.java)
    // Its own client: GitHub must never receive the DeepSeek key.
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun latest(): UpdateRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_RELEASE_URL).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10").header("User-Agent", "SpendCodex-Android").build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> null // no published stable release yet
                403, 429 -> throw UpdateException(UpdateProblem.RateLimit)
                200 -> {
                    val source = response.body.source()
                    source.request(1_048_577)
                    if (source.buffer.size > 1_048_576) throw UpdateException(UpdateProblem.Release)
                    try { UpdateRelease.parse(JSONObject(source.readUtf8())) }
                    catch (e: org.json.JSONException) { throw UpdateException(UpdateProblem.Release) }
                }
                else -> throw UpdateException(UpdateProblem.Network)
            }
        }
    }

    override fun pending(): PendingUpdate? {
        val id = prefs.getLong("id", -1)
        if (id < 0) return null
        return runCatching { PendingUpdate(id, UpdateRelease.parse(JSONObject(prefs.getString("release", "")!!))) }.getOrNull()
    }

    private fun file() = File(context.getExternalFilesDir(null) ?: throw UpdateException(UpdateProblem.Storage), "updates/update.apk")

    override fun download(release: UpdateRelease): PendingUpdate {
        val apk = release.apk ?: throw UpdateException(UpdateProblem.Release)
        cancel()
        val target = file()
        if (target.exists() && !target.delete()) throw UpdateException(UpdateProblem.Storage)
        val request = DownloadManager.Request(Uri.parse(apk.url))
            .setTitle("SpendCodex ${release.tag}").setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "updates/update.apk")
        val id = manager.enqueue(request)
        prefs.edit().putLong("id", id).putString("release", release.json().toString()).apply()
        return PendingUpdate(id, release)
    }

    override suspend fun transfer(pending: PendingUpdate): UpdateTransfer = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(pending.id)).use { cursor ->
            if (!cursor.moveToFirst()) throw UpdateException(UpdateProblem.Download)
            fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            // Status/reason are integers; downloaded byte counts need Long.
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> UpdateTransfer.Complete
                DownloadManager.STATUS_FAILED -> throw UpdateException(
                    if (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) == DownloadManager.ERROR_INSUFFICIENT_SPACE) UpdateProblem.Storage else UpdateProblem.Download,
                )
                else -> UpdateTransfer.Progress(
                    number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0),
                    number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 } ?: pending.release.apk!!.size,
                    status == DownloadManager.STATUS_PAUSED,
                )
            }
        }
    }

    override suspend fun verifiedFile(pending: PendingUpdate): File = withContext(Dispatchers.IO) {
        val apk = pending.release.apk ?: throw UpdateException(UpdateProblem.Release)
        val file = file()
        if (!file.isFile || file.length() != apk.size) throw UpdateException(UpdateProblem.Integrity)
        apk.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != expected) throw UpdateException(UpdateProblem.Integrity)
        }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val candidate = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: throw UpdateException(UpdateProblem.Integrity)
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        validatePackage(candidate, installed, pending.release)
        file
    }

    override fun cancel() {
        val id = prefs.getLong("id", -1)
        if (id >= 0) manager.remove(id)
        prefs.edit().clear().apply()
    }
}

/** Checked before handing anything to Android's installer; a different signing key must never suggest uninstalling the journal. */
internal fun validatePackage(candidate: PackageInfo, installed: PackageInfo, release: UpdateRelease) {
    if (candidate.packageName != installed.packageName || PackageInfoCompat.getLongVersionCode(candidate) <= PackageInfoCompat.getLongVersionCode(installed) ||
        AppVersion.parse(candidate.versionName.orEmpty()) != release.version || (candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) {
        throw UpdateException(UpdateProblem.Incompatible)
    }
    @Suppress("DEPRECATION")
    val matches = if (Build.VERSION.SDK_INT >= 28) {
        val old = installed.signingInfo
        val next = candidate.signingInfo
        old != null && next != null && if (old.hasMultipleSigners() || next.hasMultipleSigners()) {
            old.apkContentsSigners.toSet() == next.apkContentsSigners.toSet()
        } else {
            next.signingCertificateHistory?.toList()?.containsAll(old.apkContentsSigners.toList()) == true
        }
    } else {
        !installed.signatures.isNullOrEmpty() && candidate.signatures?.toSet() == installed.signatures?.toSet()
    }
    if (!matches) throw UpdateException(UpdateProblem.Signature)
}

object UpdateInstaller {
    fun permissionIntent(context: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    fun intent(context: Context, file: File): Intent = intent(FileProvider.getUriForFile(context, context.packageName + ".files", file))
    internal fun intent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
