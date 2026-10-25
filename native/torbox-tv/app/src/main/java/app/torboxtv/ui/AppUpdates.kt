package app.torboxtv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.torboxtv.data.AppUpdate
import app.torboxtv.data.UpdateClient
import app.torboxtv.data.UpdateInstaller
import kotlinx.coroutines.*
import java.io.File

/** Foreground-only checks and user-approved downloads. No service, account or playback dependency. */
internal class AppUpdates(private val activity: Activity, private val adult: () -> Boolean,
                          private val available: (AppUpdate?) -> Unit) {
    private val prefs = activity.getSharedPreferences("torbox_tv_updates_v1", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = UpdateClient()
    private var job: Job? = null
    private var automatic: Job? = null
    private var dialog: AlertDialog? = null
    private var visible = false
    private var permissionPending = false
    private var pending: Pair<AppUpdate, File>? = null
    val version: String get() = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    var automaticEnabled: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply(); automatic?.cancel(); if (value) schedule() }

    fun onResume() {
        visible = true
        if (permissionPending) {
            permissionPending = false
            pending?.let { (update, file) -> offerInstall(update, file) }
        } else schedule()
    }

    private fun schedule() {
        automatic?.cancel()
        if (!visible || !automaticEnabled || !adult()) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("lastAttempt", 0)
        if (last > 0 && now >= last && now - last < 24 * 60 * 60_000L) return
        automatic = scope.launch {
            delay(4000)
            if (!visible || !adult()) return@launch
            prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
            try {
                val update = client.check(UpdateInstaller.installedCode(activity))
                if (visible && adult()) available(update)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Offline GitHub must never block browsing or playback. Manual retry stays available. */ }
        }
    }

    fun check() {
        if (!visible || job?.isActive == true) return
        automatic?.cancel()
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity).setTitle("Checking for updates…")
            .setMessage("Installed: TorBox TV $version")
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .setOnCancelListener { job?.cancel() }.show()
        job = scope.launch {
            try {
                val update = client.check(UpdateInstaller.installedCode(activity))
                prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
                dialog?.dismiss(); dialog = null
                available(update)
                if (update == null) message("You're up to date", "TorBox TV $version is the newest published version.")
                else offer(update)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("Could not check for updates", "GitHub could not be reached or its update information was invalid. Try again from Settings. Your installed app is unchanged.") }
        }
    }

    fun offer(update: AppUpdate) {
        if (!visible) return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity).setTitle("TorBox TV ${update.version} is available")
            .setMessage(listOf(update.notes, "Download the update from GitHub? Your settings, profiles and viewing history stay on this device. Fire TV will ask you to approve installation.").filter { it.isNotBlank() }.joinToString("\n\n"))
            .setPositiveButton("Download update") { _, _ -> download(update) }
            .setNegativeButton("Later", null).show()
    }

    private fun download(update: AppUpdate) {
        if (!visible || job?.isActive == true) return
        dialog?.dismiss()
        val progress = AlertDialog.Builder(activity).setTitle("Downloading TorBox TV ${update.version}")
            .setMessage("Downloading… 0%")
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .setOnCancelListener { job?.cancel() }.show()
        dialog = progress
        job = scope.launch {
            try {
                val file = client.download(update, File(activity.cacheDir, "updates")) { percent ->
                    activity.runOnUiThread { if (visible && dialog === progress) progress.setMessage("Downloading… $percent%") }
                }
                progress.setMessage("Verifying update…")
                withContext(Dispatchers.IO) { UpdateInstaller.verify(activity, file, update) }
                dialog?.dismiss(); dialog = null
                offerInstall(update, file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("Update could not be downloaded", "The download was interrupted, storage is full, or the APK did not pass verification. Your installed app is unchanged. Check your connection and storage, then try again.") }
        }
    }

    private fun offerInstall(update: AppUpdate, file: File) {
        if (!visible) return
        pending = update to file
        dialog?.dismiss()
        if (!activity.packageManager.canRequestPackageInstalls()) {
            dialog = AlertDialog.Builder(activity).setTitle("Allow TorBox TV to install updates")
                .setMessage("Enable TorBox TV under Install unknown apps, then return here. On Fire TV this is usually in Settings › My Fire TV › Developer options. You still approve each installation.")
                .setPositiveButton("Open settings") { _, _ ->
                    permissionPending = true
                    try { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))) }
                    catch (_: ActivityNotFoundException) {
                        permissionPending = false
                        try { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                        catch (_: ActivityNotFoundException) { message("Open Fire TV settings", "Open Settings › My Fire TV › Developer options › Install unknown apps and allow TorBox TV. Then choose Check for updates again.") }
                    }
                }.setNegativeButton("Later", null).show()
        } else {
            dialog = AlertDialog.Builder(activity).setTitle("Update ready to install")
                .setMessage("TorBox TV ${update.version} is verified. Choose Install, then approve the Fire TV installer. Do not uninstall the current app.")
                .setPositiveButton("Install") { _, _ ->
                    job = scope.launch {
                        try {
                            withContext(Dispatchers.IO) { UpdateInstaller.verify(activity, file, update) }
                            activity.startActivity(UpdateInstaller.intent(activity, file))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message("Could not open installer", "The APK could not be verified or this device did not open its installer. Choose Check for updates to try again. Your installed app is unchanged.") }
                    }
                }.setNegativeButton("Later", null).show()
        }
    }

    private fun message(title: String, text: String) {
        dialog?.dismiss(); dialog = null
        if (visible) dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    fun onStop() {
        visible = false
        automatic?.cancel(); job?.cancel()
        dialog?.dismiss(); dialog = null
    }
    fun destroy() { scope.cancel() }
}
