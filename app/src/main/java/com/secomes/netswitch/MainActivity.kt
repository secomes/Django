package com.secomes.netswitch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvShizuku: TextView
    private lateinit var btnToggle: Button
    private lateinit var etInterval: EditText
    private lateinit var etMinMbps: EditText
    private lateinit var etMaxLatency: EditText
    private lateinit var etBadChecks: EditText
    private lateinit var etAdvantage: EditText
    private lateinit var etRetry: EditText
    private lateinit var knownList: LinearLayout

    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshShizuku() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { refreshShizuku() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refreshShizuku() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvShizuku = findViewById(R.id.tvShizuku)
        btnToggle = findViewById(R.id.btnToggle)
        etInterval = findViewById(R.id.etInterval)
        etMinMbps = findViewById(R.id.etMinMbps)
        etMaxLatency = findViewById(R.id.etMaxLatency)
        etBadChecks = findViewById(R.id.etBadChecks)
        etAdvantage = findViewById(R.id.etAdvantage)
        etRetry = findViewById(R.id.etRetry)
        knownList = findViewById(R.id.knownList)
        val tvLog = findViewById<TextView>(R.id.tvLog)

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        findViewById<Button>(R.id.btnShizuku).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testShell() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
            toast("저장됨")
        }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { addKnown() }
        btnToggle.setOnClickListener {
            if (SwitchService.running.value) {
                SwitchService.stop(this)
            } else if (!Shell.isAvailable()) {
                toast("먼저 Shizuku를 실행하고 권한을 허용하세요")
            } else {
                saveSettings()
                SwitchService.start(this)
            }
        }

        loadSettings()
        renderKnown()
        refreshShizuku()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { EventLog.lines.collect { tvLog.text = it.asReversed().joinToString("\n") } }
                launch {
                    SwitchService.running.collect { btnToggle.text = if (it) "감시 중지" else "감시 시작" }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    private fun refreshShizuku() {
        tvShizuku.text = when {
            !Shell.isRunning() -> "✗ Shizuku가 실행 중이 아닙니다. Shizuku 앱에서 '무선 디버깅으로 시작'을 하세요."
            !Shell.isAvailable() -> "△ Shizuku 실행 중 — '권한 요청'을 눌러 허용하세요."
            else -> "✓ 연결됨"
        }
    }

    private fun requestShizuku() {
        when {
            !Shell.isRunning() -> {
                toast("Shizuku 앱을 먼저 실행하세요")
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
            }
            Shell.isAvailable() -> toast("이미 허용됨")
            else -> Shizuku.requestPermission(100)
        }
    }

    private fun testShell() {
        lifecycleScope.launch {
            val r = Shell.run("cmd", "wifi", "status")
            EventLog.add("연결 테스트 (code=${r.code})\n${r.out.trim().take(500)}")
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("이미 제외됨")
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun loadSettings() {
        etInterval.setText(prefs.intervalSec.toString())
        etMinMbps.setText(prefs.minMbps.toString())
        etMaxLatency.setText(prefs.maxLatencyMs.toString())
        etBadChecks.setText(prefs.badChecks.toString())
        etAdvantage.setText(prefs.cellAdvantage.toString())
        etRetry.setText(prefs.retryWifiMin.toString())
    }

    private fun saveSettings() {
        etInterval.text.toString().toIntOrNull()?.let { prefs.intervalSec = it }
        etMinMbps.text.toString().toFloatOrNull()?.let { prefs.minMbps = it }
        etMaxLatency.text.toString().toIntOrNull()?.let { prefs.maxLatencyMs = it }
        etBadChecks.text.toString().toIntOrNull()?.let { prefs.badChecks = it }
        etAdvantage.text.toString().toFloatOrNull()?.let { prefs.cellAdvantage = it }
        etRetry.text.toString().toIntOrNull()?.let { prefs.retryWifiMin = it }
        loadSettings()
    }

    private fun addKnown() {
        val etSsid = findViewById<EditText>(R.id.etSsid)
        val etPass = findViewById<EditText>(R.id.etPass)
        val ssid = etSsid.text.toString().trim()
        if (ssid.isEmpty()) {
            toast("Wi-Fi 이름을 입력하세요")
            return
        }
        val list = prefs.knownNetworks().filter { it.ssid != ssid } + KnownWifi(ssid, etPass.text.toString())
        prefs.saveKnownNetworks(list)
        etSsid.text.clear()
        etPass.text.clear()
        renderKnown()
    }

    private fun renderKnown() {
        knownList.removeAllViews()
        val list = prefs.knownNetworks()
        if (list.isEmpty()) {
            knownList.addView(TextView(this).apply { text = "(없음)"; setPadding(0, 16, 0, 16) })
            return
        }
        list.forEach { wifi ->
            knownList.addView(TextView(this).apply {
                text = "• ${wifi.ssid}" + if (wifi.password.isEmpty()) " (개방)" else ""
                textSize = 16f
                setPadding(0, 16, 0, 16)
                setOnLongClickListener {
                    prefs.saveKnownNetworks(prefs.knownNetworks().filter { it.ssid != wifi.ssid })
                    renderKnown()
                    true
                }
            })
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
