package app.torboxtv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AppUpdate(val code: Long, val version: String, val url: String, val sha256: String, val bytes: Long, val notes: String)

/** Public APK distribution only. This client never receives account credentials or media URLs. */
object UpdateChannel {
    const val BASE = "https://raw.githubusercontent.com/to-shreds/torbox-web-player/native-updates/native/updates/"
    const val MANIFEST = BASE + "latest.json"
    const val CERTIFICATE = "a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf"
    const val MAX_APK_BYTES = 100L * 1024 * 1024

    fun parse(raw: String): AppUpdate {
        require(raw.toByteArray(Charsets.UTF_8).size <= 32_768) { "Update information is too large." }
        val json = JSONObject(raw)
        require(json.getInt("schema") == 1 && json.getString("package") == "app.torboxtv") { "This update is for a different app." }
        val code = json.getLong("versionCode")
        val version = json.getString("versionName")
        val url = json.getString("apkUrl")
        val hash = json.getString("sha256").lowercase()
        val bytes = json.getLong("bytes")
        require(code in 1..Int.MAX_VALUE.toLong() && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid update version." }
        require(hash.matches(Regex("[0-9a-f]{64}")) && bytes in 1..MAX_APK_BYTES) { "Invalid update file information." }
        // A fixed owner/repository/branch/path, HTTPS, no credentials, query or redirects.
        require(url == BASE + "releases/TorBox-TV-$version-test.apk") { "Unapproved update download." }
        val notes = json.optString("notes").take(1500)
        return AppUpdate(code, version, url, hash, bytes, notes)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class UpdateClient internal constructor(private val http: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()) {

    suspend fun check(installedCode: Long): AppUpdate? = request(UpdateChannel.MANIFEST) { response, active ->
        val body = response.body ?: throw IOException("GitHub returned no update information.")
        if (body.contentLength() > 32_768) throw IOException("Update information is too large.")
        val data = body.byteStream().use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                if (!active()) throw IOException("Update check canceled.")
                val count = input.read(chunk)
                if (count < 0) break
                if (buffer.size() + count > 32_768) throw IOException("Update information is too large.")
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        }
        UpdateChannel.parse(data.toString(Charsets.UTF_8)).takeIf { it.code > installedCode }
    }

    suspend fun download(update: AppUpdate, directory: File, progress: (Int) -> Unit = {}): File {
        // Validate callers as well as remote manifests before any request or file mutation.
        UpdateChannel.parse(JSONObject().put("schema", 1).put("package", "app.torboxtv")
            .put("versionCode", update.code).put("versionName", update.version).put("apkUrl", update.url)
            .put("sha256", update.sha256).put("bytes", update.bytes).toString())
        return request(update.url) { response, active ->
            directory.mkdirs()
            // A canceled HTTP callback may still be closing while another Activity
            // starts a retry. Each callback owns only its own partial file.
            val partial = File.createTempFile("update-", ".part", directory)
            val complete = File(directory, "update.apk")
            try {
                val body = response.body ?: throw IOException("GitHub returned no APK.")
                if (body.contentLength() >= 0 && body.contentLength() != update.bytes) throw IOException("The update download is incomplete. Try again.")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val chunk = ByteArray(32_768)
                        while (true) {
                            if (!active()) throw IOException("Update download canceled.")
                            val count = input.read(chunk)
                            if (count < 0) break
                            total += count
                            if (total > update.bytes) throw IOException("The update file is larger than expected.")
                            digest.update(chunk, 0, count); output.write(chunk, 0, count)
                            val percent = (total * 100 / update.bytes).toInt()
                            if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                        }
                        output.fd.sync()
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != update.bytes || hash != update.sha256) throw IOException("The update file could not be verified. Try again.")
                if (!active()) throw IOException("Update download canceled.")
                if (!partial.renameTo(complete)) throw IOException("There is not enough storage to save the update.")
                complete
            } finally { partial.delete() }
        }
    }

    private suspend fun <T> request(url: String, read: (Response, () -> Boolean) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url.toHttpUrl()).header("Accept", "application/octet-stream").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw IOException("GitHub update check/download failed (${it.code}). Try again later.")
                        val value = read(it) { continuation.isActive }
                        if (continuation.isActive) continuation.resume(value)
                    }
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}

class UpdateFileProvider : FileProvider()

object UpdateInstaller {
    @Suppress("DEPRECATION")
    fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    internal fun verify(context: Context, apk: File, update: AppUpdate, installedCode: Long = installedCode(context),
                        expectedCertificate: String = UpdateChannel.CERTIFICATE) {
        require(apk.isFile && apk.length() == update.bytes) { "The downloaded update is incomplete." }
        apk.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32_768)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            require(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) { "The update file failed verification." }
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalArgumentException("This download is not a valid APK.")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && code == update.code && code > installedCode && archive.versionName == update.version) {
            "The update is for a different app or is not a newer version."
        }
        val signers = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        require(signers?.size == 1 && UpdateChannel.sha256(signers.single().toByteArray()) == expectedCertificate) {
            "The update was not signed with TorBox TV's original certificate."
        }
    }

    fun intent(context: Context, apk: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(context, context.packageName + ".updates", apk), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
