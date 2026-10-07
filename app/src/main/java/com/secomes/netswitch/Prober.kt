package com.secomes.netswitch

import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** mbps < 0 means throughput was not measured (latency-only probe). */
data class Probe(val ok: Boolean, val latencyMs: Long, val mbps: Double, val note: String = "") {
    override fun toString(): String = when {
        !ok -> "실패($note)"
        mbps < 0 -> "${latencyMs}ms"
        else -> "${latencyMs}ms / %.1fMbps".format(mbps)
    }

    /** Higher is better. Rewards throughput, penalizes latency. */
    val score: Double
        get() {
            if (!ok) return 0.0
            val m = if (mbps < 0) 10.0 else mbps.coerceAtMost(200.0)
            return m * 200.0 / (200.0 + latencyMs)
        }
}

object Prober {
    private const val CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes="

    /** Measures over [network] specifically, regardless of which network is the default. */
    suspend fun probe(network: Network, downloadBytes: Int): Probe = withContext(Dispatchers.IO) {
        try {
            val t0 = SystemClock.elapsedRealtime()
            val c = network.openConnection(URL(CHECK_URL)) as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.instanceFollowRedirects = false
            c.useCaches = false
            val code = c.responseCode
            c.disconnect()
            val latency = SystemClock.elapsedRealtime() - t0
            if (code != 204) return@withContext Probe(false, latency, 0.0, "HTTP $code, 로그인 필요?")
            if (downloadBytes <= 0) return@withContext Probe(true, latency, -1.0)

            val d = network.openConnection(URL(DOWNLOAD_URL + downloadBytes)) as HttpURLConnection
            d.connectTimeout = 3000
            d.readTimeout = 4000
            d.useCaches = false
            d.setRequestProperty("User-Agent", "NetSwitch")
            val t1 = SystemClock.elapsedRealtime()
            var total = 0L
            val buf = ByteArray(16 * 1024)
            d.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (SystemClock.elapsedRealtime() - t1 > 6000) break
                }
            }
            d.disconnect()
            val sec = (SystemClock.elapsedRealtime() - t1).coerceAtLeast(1) / 1000.0
            Probe(true, latency, total * 8 / sec / 1_000_000)
        } catch (e: Exception) {
            Probe(false, -1, 0.0, e.javaClass.simpleName)
        }
    }
}
