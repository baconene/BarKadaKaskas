package com.barkadakaskas.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.barkadakaskas.app.BuildConfig
import com.barkadakaskas.app.MainActivity
import com.barkadakaskas.app.R
import com.barkadakaskas.app.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer build of the app, as published by CI. */
data class AppRelease(val versionCode: Int, val versionName: String, val apkUrl: String, val size: Long, val notes: String, val required: Boolean)

/**
 * In-app updates for the sideloaded APK: checks the server (or GitHub's latest release as a fallback) for a newer
 * build, notifies once per version, downloads the APK into the app's cache and hands it to Android's installer.
 * Updates install over the current app because every release is signed with the same key.
 */
object AppUpdates {
    const val EXTRA_SHOW_UPDATE = "show_update"
    private const val CHANNEL = "app-updates"
    private const val PREFS = "app-updates"; private const val NOTIFIED = "notified_version"; private const val SNOOZED_AT = "snoozed_at"; private const val SNOOZED_VERSION = "snoozed_version"
    private const val GITHUB = "https://api.github.com/repos/baconene/BarKadaKaskas/releases/latest"

    val currentVersion: Int get() = BuildConfig.VERSION_CODE

    /** The newest release if it is newer than this app, else null (also null when offline). */
    suspend fun check(context: Context): AppRelease? = withContext(Dispatchers.IO) {
        val server = TokenStore(context).serverUrl.trimEnd('/')
        val release = (if (server.isNotBlank()) runCatching { fromServer(get("$server/api/v1/app/android")) }.getOrNull() else null)
            ?: runCatching { fromGitHub(get(GITHUB)) }.getOrNull()
        release?.takeIf { it.versionCode > currentVersion }
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "BarkadaKasKas-Android")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun fromServer(json: JSONObject): AppRelease {
        val d = json.getJSONObject("data")
        return AppRelease(d.getInt("version_code"), d.getString("version_name"), d.getString("apk_url"), d.optLong("size"), d.optString("notes"),
            required = currentVersion < d.optInt("min_version_code", 0))
    }

    private fun fromGitHub(json: JSONObject): AppRelease {
        val match = Regex("""^app-v(\d+\.\d+)\.(\d+)$""").find(json.getString("tag_name")) ?: error("Unknown tag")
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.optString("name") == "BarkadaKasKas.apk" }
        val notes = json.optString("body").split(Regex("(?m)^\\s*---\\s*$")).first().lines().filterNot { Regex("""^[A-Za-z-]+: \S""").containsMatchIn(it.trim()) }.joinToString("\n").trim()
        return AppRelease(match.groupValues[2].toInt(), "${match.groupValues[1]}.${match.groupValues[2]}", apk.getString("browser_download_url"), apk.optLong("size"), notes.take(2000), required = false)
    }

    /** "Later" hides the prompt for a day (per version); a required update can't be put off. */
    fun shouldPrompt(context: Context, release: AppRelease): Boolean {
        if (release.required) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(SNOOZED_VERSION, -1) != release.versionCode || System.currentTimeMillis() - prefs.getLong(SNOOZED_AT, 0) > 24 * 3600_000L
    }
    fun snooze(context: Context, release: AppRelease) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(SNOOZED_VERSION, release.versionCode).putLong(SNOOZED_AT, System.currentTimeMillis()).apply()
    }

    /** A phone notification, once per version. Tapping it opens the app's update prompt. */
    fun notifyOnce(context: Context, release: AppRelease) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(NOTIFIED, -1) >= release.versionCode) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When a new version of BarkadaKasKas is out" })
        }
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_SHOW_UPDATE, true)
        val pending = PendingIntent.getActivity(context, 7001, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = release.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "Tap to update."
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BarkadaKasKas ${release.versionName} is available").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\nTap to update."))
            .setContentIntent(pending).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(7001, notification); prefs.edit().putInt(NOTIFIED, release.versionCode).apply() }
    }

    /** Downloads the APK into the cache (reporting 0..1, or -1 when the size is unknown). */
    suspend fun download(context: Context, release: AppRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "BarkadaKasKas-${release.versionName}.apk")
        var url = URL(release.apkUrl)
        var connection: HttpURLConnection
        // GitHub redirects to its file host; follow up to 5 hops.
        var hops = 0
        while (true) {
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "BarkadaKasKas-Android")
            val code = connection.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, connection.getHeaderField("Location")); connection.disconnect(); continue }
            if (code !in 200..299) { connection.disconnect(); error("The download failed (HTTP $code). Please try again.") }
            break
        }
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var done = 0L; var last = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); done += n
                    if (done - last > 256 * 1024) { last = done; withContext(Dispatchers.Main) { progress(if (total > 0) done.toFloat() / total else -1f) } }
                }
            } }
        } finally { connection.disconnect() }
        file
    }

    /** Whether Android lets this app install updates; if not, [openInstallSettings] asks the user to allow it. */
    fun canInstall(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    fun openInstallSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens Android's installer for the downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
