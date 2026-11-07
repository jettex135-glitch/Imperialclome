package com.imperialreader.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.imperialreader.util.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class AppUpdateManager(context: Context) {

    private val ctx = context.applicationContext

    suspend fun downloadLatestApk(): File = withContext(Dispatchers.IO) {
        downloadApk(latestApkAsset(), allowOlderVersion = false)
    }

    private fun packageInfoFlags(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun signingCertificates(info: PackageInfo): Set<String> {
    val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        info.signatures
    } ?: throw IOException("Could not verify the APK signing certificate")
    return signers.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
    }.toSet()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    suspend fun checkAndDownloadUpdate(): File? = withContext(Dispatchers.IO) {
        val asset = latestApkAsset()
        val assetId = asset.optLong("id")
        val assetIdText = if (assetId > 0L) assetId.toString()
        else asset.optString("fallback_key").takeIf { it.isNotBlank() }
            ?: throw IOException("GitHub returned an invalid APK asset")
        if (ctx.app.prefs.automaticAppUpdateLastAssetId == assetIdText) return@withContext null

        val apk = downloadApk(asset, allowOlderVersion = true)
        val archiveInfo = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IOException("The downloaded file is not a valid APK")
        val installedInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (versionCode(archiveInfo) <= versionCode(installedInfo)) {
            if (!apk.delete()) throw IOException("Could not remove the already-installed APK")
            ctx.app.prefs.automaticAppUpdateLastAssetId = assetIdText
            return@withContext null
        }
        ctx.app.prefs.automaticAppUpdateLastAssetId = assetIdText
        apk
    }

    private fun latestApkAsset(): JSONObject {
        val releaseRequest = Request.Builder()
            .url(RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ImperialReader")
            .build()
        val release = ctx.app.network.client.newCall(releaseRequest).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 403 || response.code == 429) return@use null
                throw IOException("GitHub release lookup failed (HTTP ${response.code})")
            }
            JSONObject(response.body?.string() ?: throw IOException("GitHub returned an empty release response"))
        } ?: return fallbackApkAsset()
        return release.optJSONArray("assets")
            ?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    .maxByOrNull { it.optString("created_at") }
            }
            ?: throw IOException("No APK is available in the V9 release")
    }

    private fun fallbackApkAsset(): JSONObject {
        val request = Request.Builder()
            .url(FALLBACK_APK_URL)
            .head()
            .header("User-Agent", "ImperialReader")
            .build()
        val fallbackKey = ctx.app.network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub release lookup was rate-limited and APK fallback failed (HTTP ${response.code})")
            }
            response.header("ETag") ?: response.header("Last-Modified")
                ?: throw IOException("GitHub APK fallback did not provide an asset version")
        }
        return JSONObject()
            .put("name", FALLBACK_APK_NAME)
            .put("browser_download_url", FALLBACK_APK_URL)
            .put("fallback_key", fallbackKey)
    }

    private suspend fun downloadApk(latestAsset: JSONObject, allowOlderVersion: Boolean): File {
        val assetUrl = latestAsset.optString("browser_download_url")
        if (!assetUrl.startsWith("https://github.com/")) {
            throw IOException("The latest APK has an invalid download address")
        }
        val assetName = latestAsset.optString("name").ifBlank { "latest APK" }

        val updateDir = File(ctx.cacheDir, UPDATE_DIRECTORY)
        if (!updateDir.exists() && !updateDir.mkdirs()) {
            throw IOException("Could not create the update download folder")
        }
        val temporary = File(updateDir, "$UPDATE_FILE.part")
        val apk = File(updateDir, UPDATE_FILE)
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Could not prepare the APK download")
        }

        try {
            val downloadRequest = Request.Builder().url(assetUrl).build()
            ctx.app.network.client.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) throw IOException("APK download failed (HTTP ${response.code})")
                val body = response.body ?: throw IOException("GitHub returned an empty APK")
                if (body.contentLength() > MAX_APK_SIZE_BYTES) {
                    throw IOException("The APK download is too large")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_SIZE_BYTES) throw IOException("The APK download is too large")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val expectedDigest = latestAsset.optString("digest")
                    .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                    ?.substringAfter(':')
                if (expectedDigest != null && !digest.digest().toHex().equals(expectedDigest, true)) {
                    throw IOException("The downloaded APK failed its SHA-256 check")
                }
            }

            val flags = packageInfoFlags()
            val archiveInfo = ctx.packageManager.getPackageArchiveInfo(temporary.absolutePath, flags)
                ?: throw IOException("The downloaded file is not a valid APK")
            if (archiveInfo.packageName != ctx.packageName) {
                throw IOException("The downloaded APK is for a different app")
            }
            val installedInfo = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
            if (!allowOlderVersion && versionCode(archiveInfo) < versionCode(installedInfo)) {
                throw IOException("The latest release APK is older than the installed app")
            }
            if (signingCertificates(archiveInfo) != signingCertificates(installedInfo)) {
                throw IOException(
                    "This APK is signed with a different key and cannot update the installed app. " +
                        "A matching release signing key is required."
                )
            }
            if (apk.exists() && !apk.delete()) throw IOException("Could not replace the previous APK download")
            if (!temporary.renameTo(apk)) throw IOException("Could not finish saving $assetName")
            return apk
        } catch (e: CancellationException) {
            temporary.delete()
            throw e
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private companion object {
        const val RELEASE_API_URL =
            "https://api.github.com/repos/Kvngz3n0/ImperialreaderApp/releases/tags/V9"
        const val FALLBACK_APK_URL =
            "https://github.com/Kvngz3n0/ImperialreaderApp/releases/download/V9/ImperialReader-V16-latest.apk"
        const val FALLBACK_APK_NAME = "ImperialReader-V16-latest.apk"
        const val UPDATE_DIRECTORY = "app-updates"
        const val UPDATE_FILE = "latest.apk"
        const val MAX_APK_SIZE_BYTES = 200L * 1024L * 1024L
    }
}
