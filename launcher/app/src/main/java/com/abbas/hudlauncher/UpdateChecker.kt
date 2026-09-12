package com.abbas.hudlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Update(val versionCode: Int, val versionName: String, val notes: String, val sha256: String)

/**
 * Self-update over Wi-Fi, borrowed from Rokid-Maps' over-the-air APK push (they use Bluetooth; we
 * already have the backend, so the glasses fetch it directly and no cable is involved).
 *
 * The system still shows its own install confirmation because we are not a privileged installer,
 * so this downloads and verifies in the background and the user confirms with one tap.
 */
class UpdateChecker(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    /** Set once an APK is downloaded and verified, ready for [install]. */
    var pending: Update? = null; private set
    private var pendingFile: File? = null

    /** Returns the update if a newer verified build is ready to install. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        try {
            val j = JSONObject(HudApi.get("/app-version"))
            if (!j.optBoolean("available")) return@withContext null
            val code = j.optInt("version_code")
            if (code <= BuildConfig.VERSION_CODE) return@withContext null
            val update = Update(code, j.optString("version_name"), j.optString("notes"), j.optString("sha256"))
            val file = File(context.cacheDir, "update-$code.apk")
            if (!file.exists() || sha256(file) != update.sha256) {
                download(file)
                if (update.sha256.isNotBlank() && sha256(file) != update.sha256) {
                    Log.w(TAG, "checksum mismatch, discarding download"); file.delete(); return@withContext null
                }
            }
            context.cacheDir.listFiles { f -> f.name.startsWith("update-") && f != file }?.forEach { it.delete() }
            pending = update; pendingFile = file
            Log.d(TAG, "update ready: ${update.versionName} (${update.versionCode})")
            update
        } catch (e: Exception) { Log.w(TAG, "update check failed: ${e.message}"); null }
    }

    /** Hands the APK to the system installer; it asks the user to confirm. */
    fun install() {
        val file = pendingFile ?: return
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (e: Exception) { Log.w(TAG, "installer: ${e.message}") }
    }

    private fun download(into: File) {
        val req = Request.Builder().url(Config.baseUrl + "/app.apk").header("X-API-KEY", Config.apiKey).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            into.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        Log.d(TAG, "downloaded ${into.length()} bytes")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { private const val TAG = "Update" }
}
