package app.torboxtv.data

import app.torboxtv.core.Policies
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Decrypts an owner-provisioned encrypted configuration locally; performs no network I/O. */
object PasswordVault {
    const val SCHEMA = "torbox-tv-key-v1"
    const val ITERATIONS = 200_000
    const val MAX_BYTES = 16_384
    fun validPassword(value: CharArray): Boolean = value.size in 4..128 && value.none { it.isISOControl() }

    fun unlock(raw: String, password: CharArray): String {
        require(validPassword(password)) { "Enter your app password (4 to 128 characters)." }
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Encrypted file is too large." }
        val json = JSONObject(raw)
        require(json.getString("schema") == SCHEMA && json.getString("cipher") == "AES-256-GCM" &&
            json.getString("kdf") == "PBKDF2-SHA256" && json.get("iterations").toString() == ITERATIONS.toString()) {
            "Unsupported encrypted file."
        }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(json.getString("salt"))
        val nonce = decoder.decode(json.getString("nonce"))
        val data = decoder.decode(json.getString("data"))
        require(salt.size == 16 && nonce.size == 12 && data.size in 32..4096) { "Invalid encrypted file." }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val derived = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(SCHEMA.toByteArray(Charsets.UTF_8))
            cipher.doFinal(data)
        } finally { derived.fill(0) }
        return try {
            val value = plain.toString(Charsets.UTF_8)
            require(Policies.validApiKey(value) && value == value.trim()) { "Invalid unlocked configuration." }
            value
        } finally { plain.fill(0) }
    }
}
