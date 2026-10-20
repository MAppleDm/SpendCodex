package dev.spendcodex.ui.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File

const val SOURCE_URL = "https://github.com/MAppleDm/SpendCodex"
const val RELEASES_URL = "$SOURCE_URL/releases"
internal const val LATEST_RELEASE_URL = "https://api.github.com/repos/MAppleDm/SpendCodex/releases/latest"

/** Compare numbers, never strings: 0.10.0 comes after 0.9.0. Only stable SemVer tags are offered. */
data class AppVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(value.trim()) ?: return null
            val numbers = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            return AppVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

data class UpdateApk(val name: String, val url: String, val size: Long, val sha256: String? = null)
data class UpdateRelease(val tag: String, val notes: String, val apk: UpdateApk?) {
    val version: AppVersion get() = requireNotNull(AppVersion.parse(tag))

    companion object {
        fun parse(json: JSONObject): UpdateRelease {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw UpdateException(UpdateProblem.Release)
            val tag = json.optString("tag_name")
            if (AppVersion.parse(tag) == null) throw UpdateException(UpdateProblem.Release)
            val assets = json.optJSONArray("assets")
            val apks = (0 until (assets?.length() ?: 0)).mapNotNull { index ->
                val asset = assets!!.getJSONObject(index)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true) || asset.optString("state", "uploaded") != "uploaded") return@mapNotNull null
                val url = asset.optString("browser_download_url")
                val parsed = url.toHttpUrlOrNull()
                if (parsed == null || parsed.scheme != "https" || parsed.host != "github.com" || parsed.port != 443 ||
                    parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
                    !parsed.encodedPath.startsWith("/MAppleDm/SpendCodex/releases/download/")) throw UpdateException(UpdateProblem.Release)
                val digest = asset.optString("digest").takeUnless { it.isBlank() || it == "null" }
                if (digest != null && !Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) throw UpdateException(UpdateProblem.Release)
                val size = asset.optLong("size")
                if (size <= 0) throw UpdateException(UpdateProblem.Release)
                UpdateApk(name, url, size, digest?.substringAfter(':')?.lowercase())
            }
            // Do not guess which of several ABI/split APKs can be installed. A universal APK is unambiguous.
            val universal = apks.filter { it.name.contains("universal", ignoreCase = true) }
            val apk = universal.singleOrNull() ?: apks.singleOrNull()
            return UpdateRelease(tag, json.optString("body").takeUnless { it == "null" }.orEmpty().take(12_000), apk)
        }
    }

    internal fun json(): JSONObject = JSONObject().put("tag_name", tag).put("body", notes).put("assets", org.json.JSONArray().apply {
        apk?.let { add -> put(JSONObject().put("name", add.name).put("browser_download_url", add.url).put("size", add.size)
            .apply { add.sha256?.let { put("digest", "sha256:$it") } }) }
    })
}

enum class UpdateProblem { Network, RateLimit, Release, Download, Storage, Integrity, Incompatible, Signature, Permission, Installer }
class UpdateException(val problem: UpdateProblem) : Exception(problem.name)
data class PendingUpdate(val id: Long, val release: UpdateRelease)
sealed interface UpdateTransfer {
    data class Progress(val bytes: Long, val total: Long, val paused: Boolean = false) : UpdateTransfer
    data object Complete : UpdateTransfer
}

interface UpdateRepository {
    suspend fun latest(): UpdateRelease?
    fun pending(): PendingUpdate?
    fun download(release: UpdateRelease): PendingUpdate
    suspend fun transfer(pending: PendingUpdate): UpdateTransfer
    suspend fun verifiedFile(pending: PendingUpdate): File
    fun cancel()
}

enum class UpdatePhase { Idle, Checking, Current, NoReleases, Available, Downloading, Verifying, Ready }
data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: UpdateRelease? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val paused: Boolean = false,
    val problem: UpdateProblem? = null,
)
