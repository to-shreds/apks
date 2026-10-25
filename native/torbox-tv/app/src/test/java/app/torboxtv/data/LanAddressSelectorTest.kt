package app.torboxtv.data

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class LanAddressSelectorTest {
    private fun candidate(ip: String, name: String, wifi: Boolean = false,
                          ethernet: Boolean = false, vpn: Boolean = false, prefix: Int = 24) =
        LanAddressCandidate(InetAddress.getByName(ip), prefix, name, wifi, ethernet, vpn)

    @Test fun vpnWithWifiTransportDoesNotHijackQrCode() {
        val result = LanAddressSelector.select(listOf(
            candidate("10.5.0.2", "tun0", wifi = true, vpn = true),
            candidate("192.168.1.72", "wlan0", wifi = true)
        ))
        assertEquals("192.168.1.72", result?.address?.hostAddress)
    }

    @Test fun tunnelAddressRejectedEvenWhenVpnFlagIsAbsent() {
        val result = LanAddressSelector.select(listOf(
            candidate("10.5.0.2", "wg0", wifi = true),
            candidate("192.168.50.3", "wlan0", wifi = true)
        ))
        assertEquals("192.168.50.3", result?.address?.hostAddress)
    }

    @Test fun wifiPreferredWhenEthernetAndWifiBothHavePrivateAddresses() {
        val result = LanAddressSelector.select(listOf(
            candidate("172.16.2.10", "eth0", ethernet = true),
            candidate("192.168.1.60", "wlan0", wifi = true)
        ))
        assertEquals("192.168.1.60", result?.address?.hostAddress)
    }

    @Test fun ethernetWorksWithoutWifi() {
        val result = LanAddressSelector.select(listOf(candidate("192.168.12.30", "eth0", ethernet = true)))
        assertEquals("192.168.12.30", result?.address?.hostAddress)
    }

    @Test fun vpnOnlyRefusesPairingRatherThanAdvertisingUnreachableAddress() {
        assertNull(LanAddressSelector.select(listOf(candidate("10.5.0.2", "tun0", wifi = true, vpn = true))))
    }

    @Test fun publicLoopbackAndNonPhysicalAddressesAreIgnored() {
        assertNull(LanAddressSelector.select(listOf(
            candidate("8.8.8.8", "wlan0", wifi = true),
            candidate("127.0.0.1", "wlan0", wifi = true),
            candidate("192.168.1.40", "rmnet_data0"),
            candidate("192.168.1.40", "wlan0", wifi = true, prefix = 7)
        )))
    }

    @Test fun privateTenRangeIsValidWhenItReallyIsTheWifiAddress() {
        val result = LanAddressSelector.select(listOf(candidate("10.5.0.2", "wlan0", wifi = true)))
        assertEquals("10.5.0.2", result?.address?.hostAddress)
    }
}
