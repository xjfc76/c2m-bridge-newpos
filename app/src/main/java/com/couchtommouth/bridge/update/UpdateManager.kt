package com.couchtommouth.bridge.update

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.couchtommouth.bridge.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages checking for app updates and installing them.
 *
 * Flow:
 *  1. On start, fetch version.json from UPDATE_URL
 *  2. If server versionCode > installed, show Update Available dialog
 *  3. On Update Now: download APK into app-private storage (no Downloads/
 *     MediaStore path guessing — that silently broke installs before)
 *  4. Prompt for "install unknown apps" if needed, then open the installer
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String?,
        val minAndroidSdk: Int? = 26
    )

    suspend fun checkForUpdates(activity: Activity) {
        try {
            Log.d(TAG, "Checking for updates...")
            val versionInfo = fetchVersionInfo() ?: run {
                Log.w(TAG, "Could not fetch version info")
                return
            }

            Log.d(
                TAG,
                "Server version: ${versionInfo.versionCode}, App version: ${BuildConfig.VERSION_CODE}"
            )

            if (versionInfo.versionCode <= BuildConfig.VERSION_CODE) {
                Log.d(TAG, "App is up to date")
                return
            }

            if (versionInfo.minAndroidSdk != null &&
                Build.VERSION.SDK_INT < versionInfo.minAndroidSdk
            ) {
                Log.w(
                    TAG,
                    "Device SDK ${Build.VERSION.SDK_INT} < required ${versionInfo.minAndroidSdk}"
                )
                return
            }

            withContext(Dispatchers.Main) {
                showUpdateDialog(activity, versionInfo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        }
    }

    private suspend fun fetchVersionInfo(): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(BuildConfig.UPDATE_URL)
            Log.d(TAG, "Fetching version info from ${BuildConfig.UPDATE_URL}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CouchToMouth-Bridge-App")
                instanceFollowRedirects = true
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Version check failed: ${connection.responseCode}")
                connection.disconnect()
                return@withContext null
            }

            val json = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            Gson().fromJson(json, VersionInfo::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch version info", e)
            null
        }
    }

    private fun showUpdateDialog(activity: Activity, versionInfo: VersionInfo) {
        val message = buildString {
            append("A new version (${versionInfo.versionName}) is available.\n\n")
            append("Current version: ${BuildConfig.VERSION_NAME}\n")
            if (!versionInfo.releaseNotes.isNullOrBlank()) {
                append("\nWhat's new:\n${versionInfo.releaseNotes}")
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Update Now") { _, _ ->
                startDownloadAndInstall(activity, versionInfo)
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    @Suppress("DEPRECATION") // ProgressDialog is fine for a simple blocking download UX
    private fun startDownloadAndInstall(activity: Activity, versionInfo: VersionInfo) {
        val progress = ProgressDialog(activity).apply {
            setTitle("Downloading update")
            setMessage("Getting v${versionInfo.versionName}…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        // Download off the UI thread; install back on Main.
        Thread {
            try {
                val apkFile = downloadApk(versionInfo) { pct ->
                    activity.runOnUiThread {
                        if (progress.isShowing) {
                            progress.progress = pct
                            progress.setMessage("Getting v${versionInfo.versionName}… $pct%")
                        }
                    }
                }
                activity.runOnUiThread {
                    if (progress.isShowing) progress.dismiss()
                    installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                activity.runOnUiThread {
                    if (progress.isShowing) progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Download Failed")
                        .setMessage(
                            "Could not download the update:\n${e.message}\n\n" +
                                "You can also open this link in Chrome and install manually:\n" +
                                versionInfo.apkUrl
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    /**
     * Download APK into app-private external files. Follows redirects (GitHub → CDN).
     */
    private fun downloadApk(
        versionInfo: VersionInfo,
        onProgress: (Int) -> Unit
    ): File {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val outFile = File(dir, "c2m-bridge-${versionInfo.versionName}.apk")
        if (outFile.exists()) outFile.delete()

        var url = versionInfo.apkUrl
        var redirects = 0
        while (redirects < 8) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false // handle manually; some CDNs need it
                setRequestProperty("User-Agent", "CouchToMouth-Bridge-App")
                setRequestProperty("Accept", "*/*")
            }

            val code = connection.responseCode
            if (code in 301..308) {
                val next = connection.getHeaderField("Location")
                    ?: throw Exception("Redirect with no Location (HTTP $code)")
                connection.disconnect()
                url = next
                redirects++
                continue
            }

            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw Exception("HTTP $code fetching APK")
            }

            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
            connection.disconnect()

            if (!outFile.exists() || outFile.length() < 1_000_000) {
                throw Exception(
                    "Downloaded file looks too small (${outFile.length()} bytes) — check the APK URL"
                )
            }
            Log.d(TAG, "Downloaded ${outFile.length()} bytes to ${outFile.absolutePath}")
            onProgress(100)
            return outFile
        }
        throw Exception("Too many redirects fetching APK")
    }

    private fun installApk(activity: Activity, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                throw Exception("APK missing after download: ${apkFile.absolutePath}")
            }

            // Android 8+: must allow this app to install packages.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                AlertDialog.Builder(activity)
                    .setTitle("Allow install")
                    .setMessage(
                        "Android needs permission for this app to install updates. " +
                            "Tap Allow, enable the toggle, then come back and tap Update Now again."
                    )
                    .setPositiveButton("Open settings") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            Toast.makeText(activity, "Opening installer…", Toast.LENGTH_SHORT).show()
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
            AlertDialog.Builder(activity)
                .setTitle("Installation Failed")
                .setMessage("Could not install update: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    fun cleanup() {
        // No BroadcastReceiver any more — nothing to unregister.
    }
}
