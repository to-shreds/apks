package app.torboxtv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class PortableVaultTest {
    private val sampleKey = "0123456789abcdef0123456789abcdef"
    private fun b64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
    private fun sample(password: String = "simple local password", salt: ByteArray = ByteArray(16) { it.toByte() },
                       iv: ByteArray = ByteArray(12) { (it + 7).toByte() }): String {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, PortableVault.ITERATIONS, 256)
        val material = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
        keySpec.clearPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(material, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(PortableVault.AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(sampleKey.toByteArray(Charsets.UTF_8))
        return JSONObject().put("format", PortableVault.FORMAT).put("iterations", PortableVault.ITERATIONS)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("ciphertext", b64(encrypted)).toString()
    }
    @Test fun unlocksPortableEncryptedKeyWithoutPersistingThePassphrase() {
        assertEquals(sampleKey, PortableVault.decrypt(sample(), "simple local password"))
    }
    @Test fun wrongPasswordDoesNotRecoverKey() {
        assertThrows(Exception::class.java) { PortableVault.decrypt(sample(), "incorrect") }
    }
    @Test fun tamperedCiphertextIsRejected() {
        val json = JSONObject(sample())
        val data = Base64.getDecoder().decode(json.getString("ciphertext"))
        data[0] = (data[0].toInt() xor 1).toByte()
        json.put("ciphertext", b64(data))
        assertThrows(Exception::class.java) { PortableVault.decrypt(json.toString(), "simple local password") }
    }
    @Test fun rejectsUnsupportedKdfIterationsAndOversizedData() {
        val doc = JSONObject(sample()).put("iterations", 1)
        assertThrows(Exception::class.java) { PortableVault.decrypt(doc.toString(), "simple local password") }
        assertThrows(Exception::class.java) { PortableVault.decrypt("x".repeat(4097), "simple local password") }
        assertThrows(Exception::class.java) { PortableVault.decrypt(sample(), "") }
    }
    @Test fun vaultIsPortableAcrossDifferentEncryptionSaltsAndInitializationVectors() {
        val original = sample()
        val other = sample(salt=ByteArray(16) { (it+44).toByte() }, iv=ByteArray(12) { (it+99).toByte() })
        assertNotEquals(JSONObject(original).getString("ciphertext"), JSONObject(other).getString("ciphertext"))
        assertEquals(sampleKey, PortableVault.decrypt(other, "simple local password"))
    }
}
