package app.torboxtv.data

import app.torboxtv.core.UserError
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PortableVaultClientTest {
    private val fixture = """{"format":"torbox-tv-portable-key-v1","iterations":180000,"salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"AAECAwQFBgcICQoL","ciphertext":"TUO+pL5WEk5psfKLfZkpzeDqjC/J/xf5LF4iL1i4ZvZMlka5W1hWcmAwppyBV+i2"}"""
    private val key = "0123456789abcdef0123456789abcdef"

    @Test fun newInstallationFetchesAndUnlocksWithoutAnotherKeyEntry() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))
            val client = PortableVaultClient(server.url("/torbox-tv.vault.json").toString())
            assertEquals(key, client.unlock("reusable sample password"))
            assertEquals("/torbox-tv.vault.json", server.takeRequest()!!.path)
        }
    }
    @Test fun wrongPasswordNeverReturnsACredential() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))
            val error = assertThrows(UserError::class.java) {
                PortableVaultClient(server.url("/").toString()).unlock("not the password")
            }
            assertFalse(error.message.orEmpty().contains(key))
        }
    }
    @Test fun vaultNotYetUploadedProducesActionableMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val error = assertThrows(UserError::class.java) {
                PortableVaultClient(server.url("/").toString()).unlock("some password")
            }
            assertTrue(error.message.orEmpty().contains("upload"))
        }
    }
    @Test fun excessivelyLargeVaultIsRejectedWithoutReadingAllContent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(9000)))
            assertThrows(UserError::class.java) {
                PortableVaultClient(server.url("/").toString()).unlock("some password")
            }
        }
    }
}
