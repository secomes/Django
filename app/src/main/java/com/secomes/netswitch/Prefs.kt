package com.secomes.netswitch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class KnownWifi(val ssid: String, val password: String)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("netswitch", Context.MODE_PRIVATE)

    var intervalSec: Int
        get() = sp.getInt("interval", 15)
        set(v) = sp.edit().putInt("interval", v.coerceIn(5, 600)).apply()

    var minMbps: Float
        get() = sp.getFloat("min_mbps", 2f)
        set(v) = sp.edit().putFloat("min_mbps", v.coerceAtLeast(0f)).apply()

    var maxLatencyMs: Int
        get() = sp.getInt("max_latency", 700)
        set(v) = sp.edit().putInt("max_latency", v.coerceIn(100, 10_000)).apply()

    var badChecks: Int
        get() = sp.getInt("bad_checks", 2)
        set(v) = sp.edit().putInt("bad_checks", v.coerceIn(1, 20)).apply()

    var cellAdvantage: Float
        get() = sp.getFloat("cell_advantage", 1.5f)
        set(v) = sp.edit().putFloat("cell_advantage", v.coerceAtLeast(1f)).apply()

    var retryWifiMin: Int
        get() = sp.getInt("retry_wifi_min", 3)
        set(v) = sp.edit().putInt("retry_wifi_min", v.coerceIn(1, 120)).apply()

    // Bytes downloaded per throughput test. Cellular is only fully tested when deciding a switch.
    val probeBytes: Int
        get() = sp.getInt("probe_bytes", 300_000)

    // Persisted so a restarted service still knows it must turn Wi-Fi back on.
    var wifiOffByApp: Boolean
        get() = sp.getBoolean("wifi_off_by_app", false)
        set(v) = sp.edit().putBoolean("wifi_off_by_app", v).apply()

    var wifiOffAt: Long
        get() = sp.getLong("wifi_off_at", 0L)
        set(v) = sp.edit().putLong("wifi_off_at", v).apply()

    fun knownNetworks(): List<KnownWifi> {
        val arr = JSONArray(sp.getString("known", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            KnownWifi(o.getString("ssid"), o.optString("password"))
        }
    }

    fun saveKnownNetworks(list: List<KnownWifi>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("ssid", it.ssid).put("password", it.password)) }
        sp.edit().putString("known", arr.toString()).apply()
    }
}
