package app.torboxtv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.torboxtv.data.AppUpdate
import app.torboxtv.data.UpdateChannel
import app.torboxtv.data.UpdateInstaller
import app.torboxtv.ui.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class UpdatesDeviceTest {
    @get:Rule val activity=ActivityTestRule(MainActivity::class.java,false,false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private lateinit var apk: File
    private lateinit var update: AppUpdate
    private lateinit var signer: String
    @Before fun fixture() {
        apk = File(context.cacheDir, "updates/update.apk").apply { parentFile!!.mkdirs() }
        File(context.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        signer = UpdateChannel.sha256(info.signingInfo!!.apkContentsSigners.single().toByteArray())
        update = AppUpdate(info.longVersionCode, info.versionName!!, UpdateChannel.BASE + "releases/TorBox-TV-${info.versionName}-test.apk",
            UpdateChannel.sha256(apk.readBytes()), apk.length(), "Device fixture")
    }
    @After fun clean() { activity.finishActivity(); apk.delete() }
    private fun rejects(action: () -> Unit) { try { action(); fail("Invalid APK was accepted") } catch (_: IllegalArgumentException) {} }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test fun archiveInspectionChecksActualPackageVersionAndSigningCertificate() {
        UpdateInstaller.verify(context, apk, update, update.code - 1, signer)
        if (signer != UpdateChannel.CERTIFICATE) rejects { UpdateInstaller.verify(context, apk, update, update.code - 1) }
        rejects { UpdateInstaller.verify(context, apk, update, update.code - 1, "0".repeat(64)) }
        rejects { UpdateInstaller.verify(context, apk, update.copy(code = update.code + 1), update.code - 1, signer) }
        rejects { UpdateInstaller.verify(context, apk, update.copy(version = "99.0.0"), update.code - 1, signer) }
        rejects { UpdateInstaller.verify(context, apk, update, update.code, signer) }
        rejects { UpdateInstaller.verify(context, apk, update.copy(sha256 = "0".repeat(64)), update.code - 1, signer) }
    }

    @Test fun fileProviderExposesOnlyUpdateCacheWithReadOnlyInstallIntent() {
        val intent = UpdateInstaller.intent(context, apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals(context.packageName + ".updates", intent.data!!.authority)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals("application/vnd.android.package-archive", intent.type)
        context.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals('P'.code, it.read()) }
        val outside = File(context.filesDir, "not-an-update.apk").apply { writeText("private") }
        try { rejects { UpdateInstaller.intent(context, outside) } } finally { outside.delete() }
    }

    @Test fun systemInstallerOpensAndCancelKeepsInstalledAppAndPreferences() {
        val before = UpdateInstaller.installedCode(context)
        val prefs = context.getSharedPreferences("updater_device_fixture", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("preserved", "history-marker").commit())
        assertTrue("Enable REQUEST_INSTALL_PACKAGES for the emulator before instrumentation starts",context.packageManager.canRequestPackageInstalls())
        try {
            activity.launchActivity(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
            instrumentation.runOnMainSync { activity.activity.startActivity(UpdateInstaller.intent(context, apk)) }
            assertTrue("Android must show its native package installer", device.wait(Until.hasObject(By.pkg("com.android.packageinstaller")), 8000))
            assertTrue(device.pressBack())
            assertEquals(before, UpdateInstaller.installedCode(context))
            assertEquals("history-marker", prefs.getString("preserved", null))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
