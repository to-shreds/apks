package app.torboxtv.data

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/**
 * Choose the actual local-link address that a phone on the same Wi-Fi can reach.
 * Android can report both WIFI and VPN transports on the *VPN* network, so
 * accepting WIFI alone (or the active network first) is unsafe for QR pairing.
 * Keep this selector independent of Android system classes for regression tests.
 */
internal data class LanAddressCandidate(
    val address: InetAddress,
    val prefixLength: Int,
    val interfaceName: String?,
    val wifi: Boolean,
    val ethernet: Boolean,
    val vpn: Boolean
)

internal object LanAddressSelector {
    fun select(candidates: List<LanAddressCandidate>): LanAddressCandidate? =
        candidates.asSequence()
            .filter { !it.vpn && (it.wifi || it.ethernet) }
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress && it.prefixLength in 8..32 }
            .filterNot { isTunnel(it.interfaceName) }
            .sortedWith(compareByDescending<LanAddressCandidate> { it.wifi })
            .firstOrNull()

    private fun isTunnel(interfaceName: String?): Boolean {
        val name = interfaceName?.lowercase(Locale.ROOT).orEmpty()
        return listOf("tun", "tap", "wg", "ppp", "ipsec", "vti", "utun", "tailscale").any { name.startsWith(it) }
    }
}
