package com.couchtommouth.bridge.update

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.couchtommouth.bridge.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages checking for app updates and installing them.
 * 
 * How it works:
 * 1. On app start, fetches version.json from server
 * 2. Compares server versionCode with installed BuildConfig.VERSION_CODE
 * 3. If newer version exists, shows dialog to user
 * 4. Downloads APK and triggers Android installer
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        
        // GitHub releases API URL (public repo for auto-updates)
        private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/xjfc76/couchToMouth_app/releases/latest"
        private const val APK_NAME = "couch2mouth-bridge.apk"
    }

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    /**
     * Data class for GitHub release info
     */
    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String?,
        val minAndroidSdk: Int? = 26
    )
    
    /**
     * Data class for GitHub API response
     */
    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("name") val name: String,
        @SerializedName("body") val body: String?,
        @SerializedName("assets") val assets: List<GitHubAsset>
    )
    
    data class GitHubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String
    )

    /**
     * Check for updates. Call this from MainActivity.onCreate()
     */
    suspend fun checkForUpdates(activity: Activity) {
        try {
            Log.d(TAG, "Checking for updates...")
            val versionInfo = fetchVersionInfo()
            
            if (versionInfo == null) {
                Log.w(TAG, "Could not fetch version info")
                return
            }

            Log.d(TAG, "Server version: ${versionInfo.versionCode}, App version: ${BuildConfig.VERSION_CODE}")

            // Check if server has newer version
            if (versionInfo.versionCode > BuildConfig.VERSION_CODE) {
                // Check minimum SDK requirement
                if (versionInfo.minAndroidSdk != null && Build.VERSION.SDK_INT < versionInfo.minAndroidSdk) {
                    Log.w(TAG, "Device SDK ${Build.VERSION.SDK_INT} < required ${versionInfo.minAndroidSdk}")
                    return
                }

                // Show update dialog on UI thread
                withContext(Dispatchers.Main) {
                    showUpdateDialog(activity, versionInfo)
                }
            } else {
                Log.d(TAG, "App is up to date")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        }
    }

    /**
     * Fetch latest release info from GitHub
     */
    private suspend fun fetchVersionInfo(): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_RELEASES_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "CouchToMouth-Bridge-App")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val json = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                
                val release = Gson().fromJson(json, GitHubRelease::class.java)
                
                // Parse version from tag (e.g., "v1.1.0" -> "1.1.0")
                val versionName = release.tagName.removePrefix("v")
                
                // Find APK asset
                val apkAsset = release.assets.find { it.name == APK_NAME }
                if (apkAsset == null) {
                    Log.w(TAG, "APK asset not found in release")
                    return@withContext null
                }
                
                // Calculate version code from version name (e.g., "1.1.0" -> 110, "1.2.3" -> 123)
                val versionParts = versionName.split(".")
                val versionCode = try {
                    versionParts[0].toInt() * 100 + 
                    versionParts.getOrNull(1)?.toInt().orZero() * 10 + 
                    versionParts.getOrNull(2)?.toInt().orZero()
                } catch (e: Exception) {
                    1 // Fallback
                }
                
                VersionInfo(
                    versionCode = versionCode,
                    versionName = versionName,
                    apkUrl = apkAsset.downloadUrl,
                    releaseNotes = release.body
                )
            } else {
                Log.w(TAG, "Version check failed: ${connection.responseCode}")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch version info", e)
            null
        }
    }
    
    private fun Int?.orZero(): Int = this ?: 0

    /**
     * Show dialog asking user to update
     */
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
                downloadAndInstall(activity, versionInfo)
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    /**
     * Download APK and trigger installation
     */
    private fun downloadAndInstall(activity: Activity, versionInfo: VersionInfo) {
        try {
            val fileName = "couch2mouth-bridge-${versionInfo.versionName}.apk"
            
            // Use DownloadManager for reliable download with progress
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            val request = DownloadManager.Request(Uri.parse(versionInfo.apkUrl))
                .setTitle("Downloading Update")
                .setDescription("CouchToMouth Bridge ${versionInfo.versionName}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            downloadId = downloadManager.enqueue(request)
            
            // Get the actual file path for later installation
            val apkFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            Log.d(TAG, "Download started: $downloadId to ${apkFile.absolutePath}")

            // Register receiver to handle download completion
            registerDownloadReceiver(activity, fileName)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download", e)
            AlertDialog.Builder(activity)
                .setTitle("Download Failed")
                .setMessage("Could not download update: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /**
     * Register receiver to handle download completion
     */
    private fun registerDownloadReceiver(activity: Activity, fileName: String) {
        // Unregister any existing receiver
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore
            }
        }

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    Log.d(TAG, "Download completed: $id")
                    
                    // Unregister this receiver
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        // Ignore
                    }

                    // Get the downloaded file from Downloads folder
                    val apkFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                    
                    // Install the APK
                    installApk(activity, apkFile)
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, filter)
        }
    }

    /**
     * Install the downloaded APK
     */
    private fun installApk(activity: Activity, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
                return
            }

            Log.d(TAG, "Installing APK: ${apkFile.absolutePath}")

            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ requires FileProvider
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

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

    /**
     * Clean up receivers
     */
    fun cleanup() {
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        downloadReceiver = null
    }
}
