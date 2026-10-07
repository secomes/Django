package com.secomes.netswitch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Measures the current connection periodically and switches when it is slow:
 *  1. Slow Wi-Fi -> a known Wi-Fi in range, otherwise mobile data (Wi-Fi turned off).
 *  2. While Wi-Fi is off, it is re-checked periodically (with backoff) or as soon as
 *     mobile data gets slow too.
 *  3. Slow mobile data with Wi-Fi on but unused -> a known Wi-Fi in range.
 */
class SwitchService : Service() {

    companion object {
        val running = MutableStateFlow(false)
        private const val ACTION_STOP = "com.secomes.netswitch.STOP"
        private const val CHANNEL = "status"
        private const val NOTIFY_ID = 1
        private const val SWITCH_COOLDOWN_MS = 30_000L
        private const val CELL_COMPARE_GAP_MS = 120_000L
        private const val SKIP_BAD_WIFI_MS = 10 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SwitchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SwitchService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: Prefs
    private lateinit var cm: ConnectivityManager
    private lateinit var wm: WifiManager

    private var badCount = 0
    private var cellBadCount = 0
    private var retryBackoff = 1
    private var lastSwitchAt = 0L
    private var lastCellCompareAt = 0L
    private val recentlyBadSsids = mutableMapOf<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        cm = getSystemService(ConnectivityManager::class.java)
        wm = applicationContext.getSystemService(WifiManager::class.java)
        createChannel()
        val n = buildNotification("시작 중…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, n)
        }
        running.value = true
        EventLog.add("감시 시작")
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        running.value = false
        if (prefs.wifiOffByApp) {
            prefs.wifiOffByApp = false
            CoroutineScope(Dispatchers.IO).launch { WifiCtl.setWifi(true) }
            EventLog.add("감시 중지 — 꺼뒀던 Wi-Fi를 다시 켬")
        } else {
            EventLog.add("감시 중지")
        }
        super.onDestroy()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.add("오류: $e")
            }
            delay(prefs.intervalSec * 1000L)
        }
    }

    private suspend fun tick() {
        if (!Shell.isAvailable()) {
            status("Shizuku 연결 안 됨 — Shizuku 앱에서 다시 시작하세요")
            return
        }
        val wifiNet = findNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        when {
            wifiNet != null -> onWifi(wifiNet)
            prefs.wifiOffByApp && wm.isWifiEnabled -> {
                // Someone turned Wi-Fi back on manually; stop managing it.
                prefs.wifiOffByApp = false
            }
            prefs.wifiOffByApp -> onWifiOffByApp()
            wm.isWifiEnabled -> onCellularWithWifiOn()
            else -> status("Wi-Fi가 꺼져 있음(사용자 설정) — 관여하지 않음")
        }
    }

    // --- 1. On Wi-Fi ---

    private suspend fun onWifi(wifiNet: Network) {
        val w = Prober.probe(wifiNet, prefs.probeBytes)
        val st = WifiCtl.status()
        val name = st.ssid ?: "Wi-Fi"
        status("$name · $w")
        if (!isBad(w)) {
            badCount = 0
            return
        }
        badCount++
        EventLog.add("$name 느림 ($badCount/${prefs.badChecks}): $w")
        if (badCount < prefs.badChecks || inCooldown()) return

        st.ssid?.let { recentlyBadSsids[it] = now() }
        val candidate = findKnownWifi(exclude = st.ssid)
        if (candidate != null) {
            connectKnown(candidate, "$name 느림")
            return
        }

        if (now() - lastCellCompareAt < CELL_COMPARE_GAP_MS) return
        lastCellCompareAt = now()
        val c = withCellular { Prober.probe(it, prefs.probeBytes) }
        if (c == null) {
            EventLog.add("모바일 데이터 사용 불가 (꺼져 있음?)")
            return
        }
        EventLog.add("비교 — Wi-Fi $w / 모바일 $c")
        if (!isBad(c) && c.score >= w.score * prefs.cellAdvantage) {
            WifiCtl.setWifi(false)
            prefs.wifiOffByApp = true
            prefs.wifiOffAt = System.currentTimeMillis()
            markSwitched()
            EventLog.add("→ 모바일 데이터로 전환 (Wi-Fi 일시 끔)")
        } else {
            EventLog.add("모바일 데이터도 더 낫지 않음 → 유지")
        }
    }

    // --- 2. Wi-Fi turned off by this app ---

    private suspend fun onWifiOffByApp() {
        val cell = findNetwork(NetworkCapabilities.TRANSPORT_CELLULAR)
        val p = cell?.let { Prober.probe(it, 0) }
        val waitMin = prefs.retryWifiMin * retryBackoff
        status("모바일 데이터 · ${p ?: "연결 없음"} · Wi-Fi 일시 끔 (${waitMin}분마다 재확인)")

        val cellBad = p == null || isBad(p)
        if (cellBad) cellBadCount++ else cellBadCount = 0
        val due = System.currentTimeMillis() - prefs.wifiOffAt >= waitMin * 60_000L
        val cellTooSlow = cellBadCount >= prefs.badChecks
        if (!cellTooSlow && !due) return

        EventLog.add(if (cellTooSlow) "모바일 데이터도 느림 → Wi-Fi 다시 켬" else "Wi-Fi 재확인")
        WifiCtl.setWifi(true)
        prefs.wifiOffByApp = false
        cellBadCount = 0
        markSwitched()

        val wifiNet = waitForWifi(20_000)
        if (wifiNet == null) {
            EventLog.add("연결된 Wi-Fi 없음 → Wi-Fi는 켜둔 채 모바일 유지")
            retryBackoff = 1
            return
        }
        val w = Prober.probe(wifiNet, prefs.probeBytes)
        if (!isBad(w)) {
            EventLog.add("Wi-Fi 복귀: $w")
            retryBackoff = 1
            badCount = 0
            return
        }
        if (!cellTooSlow) {
            val c = withCellular { Prober.probe(it, prefs.probeBytes) }
            if (c != null && !isBad(c) && c.score >= w.score * prefs.cellAdvantage) {
                WifiCtl.setWifi(false)
                prefs.wifiOffByApp = true
                prefs.wifiOffAt = System.currentTimeMillis()
                retryBackoff = (retryBackoff * 2).coerceAtMost(8)
                EventLog.add("Wi-Fi 여전히 느림($w) → 다시 끔, ${prefs.retryWifiMin * retryBackoff}분 후 재확인")
                return
            }
        }
        EventLog.add("Wi-Fi 유지: $w")
        retryBackoff = 1
    }

    // --- 3. Mobile data while Wi-Fi is on but not connected ---

    private suspend fun onCellularWithWifiOn() {
        val cell = findNetwork(NetworkCapabilities.TRANSPORT_CELLULAR)
        val p = cell?.let { Prober.probe(it, 0) }
        status("모바일 데이터 · ${p ?: "연결 없음"}")
        if (p != null && !isBad(p)) {
            badCount = 0
            return
        }
        badCount++
        if (badCount < prefs.badChecks || inCooldown()) return
        val candidate = findKnownWifi(exclude = null) ?: return
        connectKnown(candidate, "모바일 데이터 느림")
    }

    // --- helpers ---

    private fun isBad(p: Probe): Boolean =
        !p.ok || p.latencyMs > prefs.maxLatencyMs || (p.mbps >= 0 && p.mbps < prefs.minMbps)

    private fun now() = SystemClock.elapsedRealtime()

    private fun inCooldown() = now() - lastSwitchAt < SWITCH_COOLDOWN_MS

    private fun markSwitched() {
        lastSwitchAt = now()
        badCount = 0
    }

    private suspend fun findKnownWifi(exclude: String?): Pair<KnownWifi, ScanEntry>? {
        val known = prefs.knownNetworks().associateBy { it.ssid }
        if (known.isEmpty()) return null
        val t = now()
        recentlyBadSsids.entries.removeAll { t - it.value > SKIP_BAD_WIFI_MS }
        return WifiCtl.scan()
            .filter { it.ssid in known && it.ssid != exclude && it.ssid !in recentlyBadSsids && it.rssi >= -75 }
            .maxByOrNull { it.rssi }
            ?.let { known.getValue(it.ssid) to it }
    }

    private suspend fun connectKnown(candidate: Pair<KnownWifi, ScanEntry>, reason: String) {
        val (wifi, scan) = candidate
        EventLog.add("$reason → ${wifi.ssid} (${scan.rssi}dBm) 연결 시도")
        if (!wm.isWifiEnabled) WifiCtl.setWifi(true)
        val r = WifiCtl.connect(wifi.ssid, wifi.password, scan.flags)
        if (!r.ok) EventLog.add("연결 명령 실패: ${r.out.trim().take(200)}")
        markSwitched()
        delay(8000)
    }

    @Suppress("DEPRECATION")
    private fun findNetwork(transport: Int): Network? = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(transport) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
    }

    private suspend fun waitForWifi(timeoutMs: Long): Network? = withTimeoutOrNull(timeoutMs) {
        var network = findNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        while (network == null) {
            delay(1000)
            network = findNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        }
        network
    }

    /** Brings up mobile data alongside Wi-Fi just long enough to run [block]. */
    private suspend fun <T> withCellular(block: suspend (Network) -> T): T? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val got = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                got.complete(network)
            }
        }
        cm.requestNetwork(request, callback)
        return try {
            val network = withTimeoutOrNull(8000) { got.await() } ?: return null
            block(network)
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "연결 상태", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SwitchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("넷스위치")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "중지", stop)
            .build()
    }

    private fun status(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, buildNotification(text))
        } catch (e: SecurityException) {
            // Notification permission denied; the service keeps working.
        }
    }
}
