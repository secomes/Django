package com.secomes.netswitch

import kotlinx.coroutines.delay

data class ScanEntry(val ssid: String, val rssi: Int, val flags: String)
data class WifiStatus(val ssid: String?, val rssi: Int?)

/** Wi-Fi / mobile data control through shell commands (needs Shizuku). */
object WifiCtl {
    // e.g. "  aa:bb:cc:dd:ee:ff   5180   -45(0:-47/1:-50)   3.2   My Wifi   [WPA2-PSK-CCMP][ESS]"
    private val SCAN_LINE = Regex(
        """^\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\s+(\d+)\s+(-?\d+)\S*\s+(\S+)\s+(.*?)\s*((?:\[[^\]]*\])*)$"""
    )

    suspend fun setWifi(on: Boolean) = Shell.run("svc", "wifi", if (on) "enable" else "disable")

    suspend fun status(): WifiStatus {
        val out = Shell.run("cmd", "wifi", "status").out
        val ssid = (Regex("""connected to "(.*?)"""").find(out)
            ?: Regex("""SSID: "(.*?)"""").find(out))?.groupValues?.get(1)
        val rssi = Regex("""RSSI: (-?\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        return WifiStatus(ssid, rssi)
    }

    suspend fun scan(): List<ScanEntry> {
        Shell.run("cmd", "wifi", "start-scan")
        delay(3000)
        return parseScan(Shell.run("cmd", "wifi", "list-scan-results").out)
    }

    fun parseScan(out: String): List<ScanEntry> = out.lineSequence().mapNotNull { line ->
        val m = SCAN_LINE.matchEntire(line.trimEnd()) ?: return@mapNotNull null
        val ssid = m.groupValues[5]
        if (ssid.isBlank()) null else ScanEntry(ssid, m.groupValues[3].toInt(), m.groupValues[6])
    }.toList()

    suspend fun connect(ssid: String, password: String, flags: String): Shell.Result {
        val security = when {
            flags.contains("PSK") -> "wpa2"
            flags.contains("SAE") -> "wpa3"
            flags.contains("OWE") -> "owe"
            password.isEmpty() -> "open"
            else -> "wpa2"
        }
        return if (security == "open" || security == "owe") {
            Shell.run("cmd", "wifi", "connect-network", ssid, security)
        } else {
            Shell.run("cmd", "wifi", "connect-network", ssid, security, password)
        }
    }
}
