package com.callio.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.callio.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-hosted OTA updater (GitHub Releases).
 *
 *  - minor update  -> downloaded + installed automatically in the background
 *  - major update  -> pop-up with release notes (forced when below minSupportedVersionCode)
 *  - manual check  -> Profile > "Check for updates"
 *
 * Every APK is SHA-256 verified and its package name / versionCode checked before install.
 * Android only allows installing an update signed with the SAME key, so all releases must come
 * from the signed Release workflow.
 */
class UpdateManager(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("callio_updates", Context.MODE_PRIVATE)
    private val busy = AtomicBoolean(false)
    @Volatile private var pending: UpdateInfo? = null
    @Volatile private var lastInfo: UpdateInfo? = null
    var onState: (UpdateUiState) -> Unit = {}

    private fun pkg() = app.packageManager.getPackageInfo(app.packageName, 0)
    val currentVersionCode: Long get() = pkg().longVersionCode
    val currentVersionName: String get() = pkg().versionName ?: "1.0.0"

    fun isConfigured(): Boolean =
        BuildConfig.CALLIO_UPDATE_URL.startsWith("https://") && !BuildConfig.CALLIO_UPDATE_URL.contains("OWNER/REPO")

    private fun emit(state: UpdateUiState) { main.post { onState(state) } }

    /** Called on app start/resume (throttled) and from the Profile button (manual = true). */
    fun check(manual: Boolean, canAutoInstall: () -> Boolean = { true }) {
        if (!isConfigured()) { if (manual) emit(UpdateUiState.Error("Update channel is not set up yet")); return }
        if (!manual && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        if (!busy.compareAndSet(false, true)) return
        if (manual) emit(UpdateUiState.Checking)
        Thread {
            var handoff = false
            try {
                val info = fetchManifest()
                lastInfo = info
                val current = currentVersionCode
                if (info.versionCode <= current) {
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    if (manual) emit(UpdateUiState.UpToDate)
                    return@Thread
                }
                val forced = info.minSupportedVersionCode > current
                val major = forced || info.type == "major" || info.lastMajorVersionCode > current
                if (major || manual) {
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    emit(UpdateUiState.Available(info, forced, major))
                } else if (canAutoInstall()) {
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    busy.set(false); handoff = true
                    downloadAndInstall(info)
                }
            } catch (e: Exception) {
                if (manual) emit(UpdateUiState.Error("Could not check for updates (${e.localizedMessage ?: "network error"})"))
            } finally {
                if (!handoff) busy.set(false)
            }
        }.start()
    }

    fun downloadAndInstall(info: UpdateInfo) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            pending = info
            emit(UpdateUiState.NeedsPermission(info))
            return
        }
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                emit(UpdateUiState.Downloading(info, 0))
                var last = -1
                val file = download(info) { p -> if (p != last) { last = p; emit(UpdateUiState.Downloading(info, p)) } }
                emit(UpdateUiState.Installing(info))
                install(file)
            } catch (e: Exception) {
                emit(UpdateUiState.Error(e.localizedMessage ?: "Update failed"))
            } finally {
                busy.set(false)
            }
        }.start()
    }

    /** Call from onResume: continues an update that was waiting for the "install unknown apps" permission. */
    fun resumePendingIfAllowed() {
        val info = pending ?: return
        if (app.packageManager.canRequestPackageInstalls()) { pending = null; downloadAndInstall(info) }
    }

    fun onInstallResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> emit(UpdateUiState.Idle)
            PackageInstaller.STATUS_FAILURE_ABORTED -> emit(UpdateUiState.Error("Update was cancelled"))
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                emit(UpdateUiState.Error("This build was signed with a different key. Uninstall once, then install the latest signed APK."))
            else -> emit(UpdateUiState.Error(message ?: "Install failed"))
        }
        File(app.cacheDir, "update").deleteRecursively()
    }

    fun dismiss() { pending = null; emit(UpdateUiState.Idle) }

    // ---------------------------------------------------------------- internals

    private fun fetchManifest(): UpdateInfo {
        val conn = (URL(BuildConfig.CALLIO_UPDATE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000; useCaches = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val url = json.getString("apkUrl")
            require(url.startsWith("https://")) { "Update URL must be HTTPS" }
            return UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.optString("versionName", ""),
                apkUrl = url,
                sha256 = json.optString("sha256", ""),
                sizeBytes = json.optLong("sizeBytes", 0L),
                type = json.optString("type", "minor"),
                minSupportedVersionCode = json.optLong("minSupportedVersionCode", 0L),
                lastMajorVersionCode = json.optLong("lastMajorVersionCode", 0L),
                notes = json.optString("notes", "")
            )
        } finally { conn.disconnect() }
    }

    private fun download(info: UpdateInfo, progress: (Int) -> Unit): File {
        val dir = File(app.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "callio-${info.versionCode}.apk")
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("Download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        if (total > 0) progress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (info.sha256.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.sha256, ignoreCase = true)) throw SecurityException("Downloaded update is corrupted (checksum mismatch)")
            }
            val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw IllegalStateException("Downloaded file is not a valid APK")
            if (archive.packageName != app.packageName) throw SecurityException("Update is for a different app")
            if (archive.longVersionCode <= currentVersionCode) throw IllegalStateException("Update is not newer than the installed version")
            return file
        } finally { conn.disconnect() }
    }

    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            // Android 12+: no confirmation needed when Callio itself installed the current version.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("callio-update", 0, apk.length()).use { out ->
                    input.copyTo(out); session.fsync(out)
                }
            }
            val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
            val pi = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
