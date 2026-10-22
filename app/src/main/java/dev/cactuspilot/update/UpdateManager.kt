package dev.cactuspilot.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.cactuspilot.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val build: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val fraction: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class NeedsInstallPermission(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Self-updater for sideloaded builds. CI publishes each build as a GitHub pre-release `build-<N>` whose
 * number equals the app's versionCode, so "newer" simply means a higher N. Because every build is signed
 * with the same key, Android installs the downloaded APK over the existing app and keeps its data
 * (including downloaded models). The signer is checked before the installer is opened.
 */
class UpdateManager(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val currentBuild: Int = BuildConfig.VERSION_CODE
    val currentVersion: String = BuildConfig.VERSION_NAME

    /** [manual] = user pressed the button (always checks and reports); otherwise throttled and silent. */
    fun checkAsync(manual: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) return
        if (!manual) {
            if (s is UpdateState.Available || s is UpdateState.ReadyToInstall) return
            if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        }
        scope.launch { check(manual) }
    }

    private fun check(manual: Boolean) {
        if (manual) _state.value = UpdateState.Checking
        try {
            val body = httpGetText(RELEASES_URL)
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            val info = pickLatest(body, currentBuild)
            _state.value = when {
                info != null -> UpdateState.Available(info)
                manual -> UpdateState.UpToDate
                else -> UpdateState.Idle
            }
        } catch (e: Exception) {
            _state.value = if (manual) UpdateState.Error(e.message ?: "Update check failed") else UpdateState.Idle
        }
    }

    fun downloadAsync(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, 0f)
                val file = download(info)
                verifySigner(file)
                _state.value = UpdateState.ReadyToInstall(info, file)
                install(info, file)
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    /** Opens the system package installer (or the "install unknown apps" screen the first time). */
    fun install(info: UpdateInfo, file: File) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsInstallPermission(info, file)
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        _state.value = UpdateState.ReadyToInstall(info, file)
    }

    private fun download(info: UpdateInfo): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "CactusPilot-${info.build}.apk")
        val part = File(dir, out.name + ".part")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} downloading update")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            var lastPublished = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastPublished > 256 * 1024) {
                            lastPublished = done
                            _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                        }
                    }
                }
            }
            if (info.sizeBytes > 0 && part.length() != info.sizeBytes) {
                throw IOException("Downloaded ${part.length()} bytes, expected ${info.sizeBytes}")
            }
            if (!part.renameTo(out)) throw IOException("Could not save update file")
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** Refuse APKs signed by a different key: Android would reject them anyway ("App not installed"). */
    private fun verifySigner(apk: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val downloaded = pm.getPackageArchiveInfo(apk.absolutePath, flags)?.signingInfo?.apkContentsSigners
            ?: throw IOException("Downloaded file is not a valid APK")
        val installed = pm.getPackageInfo(app.packageName, flags).signingInfo?.apkContentsSigners
            ?: return
        val same = downloaded.any { d -> installed.any { it.toByteArray().contentEquals(d.toByteArray()) } }
        if (!same) throw IOException("Update is signed with a different key; it cannot replace this install.")
    }

    private fun httpGetText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "CactusPilot/${BuildConfig.VERSION_NAME}")
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode != 200) throw IOException("GitHub returned HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/dhawan9898/Cactus-app/releases?per_page=10"
        private const val KEY_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private val TAG = Regex("^build-(\\d+)$")

        /** Newest non-draft `build-N` release with an APK asset and N > [currentBuild], or null. */
        fun pickLatest(json: String, currentBuild: Int): UpdateInfo? {
            val releases = JSONArray(json)
            var best: UpdateInfo? = null
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft", false)) continue
                val build = TAG.matchEntire(r.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (build <= currentBuild || build <= (best?.build ?: 0)) continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk")) {
                        best = UpdateInfo(
                            build = build,
                            versionName = r.optString("name").ifBlank { "build $build" },
                            apkUrl = a.getString("browser_download_url"),
                            sizeBytes = a.optLong("size", 0L),
                            notes = r.optString("body", "").trim(),
                        )
                        break
                    }
                }
            }
            return best
        }
    }
}
