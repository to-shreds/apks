package app.torboxtv.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.torboxtv.data.LanAddressCandidate
import app.torboxtv.data.LanAddressSelector
import app.torboxtv.data.PhoneRemoteServer
import app.torboxtv.data.RemoteCommand
import app.torboxtv.data.StateStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TorBoxApplication : Application() {
    val phone by lazy { PhoneRemote(this) }
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = phone.resumed(activity)
            override fun onActivityPaused(activity: Activity) = phone.paused(activity)
            override fun onActivityStopped(activity: Activity) = phone.stopped()
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

class PhoneRemote(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var current = WeakReference<Activity>(null)
    private var server: PhoneRemoteServer? = null
    private var pairing: AlertDialog? = null
    private var generation = 0
    private val closeWhenBackground = Runnable { if (current.get() == null) disconnect() }
    val pairingUrl: String? get() = server?.takeUnless { it.isClosed }?.pairingUrl

    fun resumed(activity: Activity) { handler.removeCallbacks(closeWhenBackground); current = WeakReference(activity) }
    fun paused(activity: Activity) { if (current.get() === activity) current.clear() }
    fun stopped() { handler.postDelayed(closeWhenBackground, 300) }

    fun showPairing(activity: Activity) {
        try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val networks = listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks.toList()
            // The active network can be a VPN which also reports WIFI as an underlying
            // transport. Use the real physical-link address, never that VPN tunnel.
            val candidates = networks.distinct().flatMap { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@flatMap emptyList()
                val properties = connectivity.getLinkProperties(network) ?: return@flatMap emptyList()
                properties.linkAddresses.map { link ->
                    LanAddressCandidate(
                        link.address, link.prefixLength, properties.interfaceName,
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    )
                }
            }
            val address = LanAddressSelector.select(candidates)
                ?: throw IllegalStateException("No usable Wi-Fi or Ethernet LAN address was found. Check the Firestick network connection, disable any VPN that blocks local traffic, and try again.")
            val existing = server
            if (existing == null || existing.isClosed || !existing.origin.startsWith("http://${address.address.hostAddress}:")) {
                disconnect()
                val session = ++generation
                server = PhoneRemoteServer(address.address, address.prefixLength, context.assets.open("phone-remote.html").bufferedReader().use { it.readText() }) { dispatch(it, session) }
            }
            val url = server!!.pairingUrl
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 480, 480, mapOf(EncodeHintType.MARGIN to 3))
            val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE }
            val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)); gravity = android.view.Gravity.CENTER }
            box.addView(ImageView(activity).apply { setImageBitmap(bitmap); contentDescription = "Phone remote pairing QR code" }, LinearLayout.LayoutParams(dp(210), dp(210)))
            box.addView(TextView(activity).apply {
                text = "Firestick LAN address: ${address.address.hostAddress}\nScan with your phone camera on the same Wi-Fi. If the page does not load, check that this matches the Firestick network settings and disable VPN or guest-network isolation."
                textSize = 15f; gravity = android.view.Gravity.CENTER; setPadding(0, dp(12), 0, 0)
            })
            pairing?.dismiss()
            pairing = AlertDialog.Builder(activity).setTitle("Use your phone as a remote").setView(box)
                .setNegativeButton("Disconnect") { _, _ -> disconnect() }
                .setOnCancelListener { disconnect() }.show()
        } catch (error: Exception) {
            disconnect()
            AlertDialog.Builder(activity).setTitle("Phone remote unavailable")
                .setMessage(if (error is IllegalStateException) error.message else "Connect the Firestick and phone to the same home Wi-Fi and try again. Guest networks or a VPN may block local connections.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun dispatch(command: RemoteCommand, session: Int): Boolean {
        val done = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + 2000
        var handled = false
        handler.post {
            try {
                val activity = current.get()
                if (SystemClock.uptimeMillis() <= deadline && session == generation && server?.isClosed == false && activity != null && !activity.isFinishing) {
                    when (command) {
                        RemoteCommand.Hello -> { pairing?.dismiss(); pairing = null; handled = true }
                        RemoteCommand.Disconnect -> { handler.postDelayed({ disconnect() }, 100); handled = true }
                        RemoteCommand.Browse -> {
                            activity.startActivity(Intent(activity, MainActivity::class.java).putExtra("phoneHome", true)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)); handled = true
                        }
                        is RemoteCommand.Key -> {
                            val code = keys.getValue(command.name)
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); handled = true
                        }
                        is RemoteCommand.Search -> {
                            if (command.sports) {
                                if (!StateStore(context).currentProfile().kidMode) {
                                    if (activity is SportsActivity) activity.searchFromPhone(command.query)
                                    else activity.startActivity(Intent(activity, SportsActivity::class.java).putExtra("phoneQuery", command.query))
                                    handled = true
                                }
                            } else {
                                if (!StateStore(context).key().isNullOrBlank()) {
                                    if (activity is MainActivity) activity.searchFromPhone(command.query)
                                    else activity.startActivity(Intent(activity, MainActivity::class.java).putExtra("phoneQuery", command.query)
                                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                                    handled = true
                                }
                            }
                        }
                    }
                }
            } finally { done.countDown() }
        }
        return done.await(2, TimeUnit.SECONDS) && handled
    }

    fun disconnect() {
        generation++
        server?.close(); server = null
        pairing?.dismiss(); pairing = null
    }

    companion object {
        private val keys = mapOf("UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT, "OK" to KeyEvent.KEYCODE_DPAD_CENTER,
            "BACK" to KeyEvent.KEYCODE_BACK, "PLAY" to KeyEvent.KEYCODE_MEDIA_PLAY, "PAUSE" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "TOGGLE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "REWIND" to KeyEvent.KEYCODE_MEDIA_REWIND, "FORWARD" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    }
}
