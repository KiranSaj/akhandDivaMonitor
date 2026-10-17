package com.kiransaj.diyamonitor.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.kiransaj.diyamonitor.BuildConfig
import com.kiransaj.diyamonitor.core.AppVersion
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Finds the latest GitHub release, downloads its APK into the app cache and hands it to
 * Android's package installer. Network calls are blocking; call them off the main thread.
 */
class AppUpdater(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val context = context.applicationContext

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** The latest published release that has an APK attached, or null if there is none. */
    fun latestRelease(): Release? {
        val request = Request.Builder().url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("GitHub returned HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    return Release(
                        version = json.optString("tag_name"),
                        notes = json.optString("body").trim(),
                        apkUrl = asset.getString("browser_download_url"),
                        apkSize = asset.optLong("size", -1),
                    )
                }
            }
            return null
        }
    }

    fun isNewer(release: Release): Boolean = AppVersion.isNewer(release.version, currentVersion)

    /**
     * Downloads the release APK, reporting 0..100 progress on the calling thread.
     * Verifies the size against GitHub's listing; Android itself verifies the signature on install.
     */
    fun download(release: Release, onProgress: (Int) -> Unit = {}): File {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "diya-monitor-${release.version}.apk")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Download failed: empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        }
        if (release.apkSize > 0 && file.length() != release.apkSize) {
            file.delete()
            throw IOException("Download incomplete (${file.length()} of ${release.apkSize} bytes). Try again.")
        }
        return file
    }

    /** Android requires a one-time "Install unknown apps" permission for this app. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens Android's installer for [apk]; the user confirms with "Install". */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        private const val REPO = "KiranSaj/akhandDivaMonitor"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val USER_AGENT = "DiyaMonitor-updater"
        /** Must match res/xml/file_paths.xml. */
        private const val UPDATE_DIR = "updates"
        const val DAILY_CHECK_MS = 24 * 3600_000L
    }
}
