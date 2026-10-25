package app.torboxtv.data

import android.util.Base64
import app.torboxtv.core.Policies
import app.torboxtv.core.UserError
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable password vault. Only ciphertext is stored in GitHub. The password
 * exists just for this unlock, and the recovered TorBox key is immediately
 * moved to the app's existing Android Keystore-protected credential storage.
 */
object PortableVault {
    const val FORMAT = "torbox-tv-portable-key-v1"
    const val ITERATIONS = 180_000
    const val AAD = "torbox-tv-portable-vault-v1"
    const val FILE_URL = "https://raw.githubusercontent.com/to-shreds/apks/main/torbox-tv.vault.json"
    const val SETUP_URL = "https://to-shreds.github.io/Misc/TorBoxVault/"

    fun decrypt(jsonText: String, password: String): String {
        require(jsonText.length in 1..4096 && password.length in 1..128)
        val doc = JSONObject(jsonText)
        require(doc.getString("format") == FORMAT && doc.getInt("iterations") == ITERATIONS)
        require(doc.length() == 5)
        val salt = Base64.decode(doc.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(doc.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(doc.getString("ciphertext"), Base64.NO_WRAP)
        require(salt.size == 16 && iv.size == 12 && data.size in 32..1024)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword(); salt.fill(0) }
        val secret = SecretKeySpec(keyBytes, "AES")
        keyBytes.fill(0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(data)
        val value = try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
        require(Policies.validApiKey(value))
        return value
    }
}

class PortableVaultClient {
    fun unlock(password: String): String {
        if (password.isBlank()) throw UserError("Enter your vault password.")
        val connection = URL(PortableVault.FILE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 7000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        try {
            val code = connection.responseCode
            if (code == 404) throw UserError(
                "No saved encrypted key yet. Open ${PortableVault.SETUP_URL} on your phone, download the encrypted vault, and upload it to the root of to-shreds/apks."
            )
            if (code != 200) throw UserError("Could not load the saved key from GitHub (HTTP $code). Try again.")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4096) throw UserError("The saved vault file is too large.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return try {
                PortableVault.decrypt(bytes.toString(Charsets.UTF_8), password)
            } catch (_: Exception) {
                throw UserError("That password did not unlock the saved key. Check the password and try again.")
            } finally {
                bytes.fill(0)
            }
        } finally {
            connection.disconnect()
        }
    }
}
