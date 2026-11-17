package com.derstakip.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvDailyTotal: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnDiscover: Button
    private lateinit var btnStartStudy: Button
    private lateinit var btnFinishStudy: Button

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var targetHostAddress: String? = null
    private val peers = mutableListOf<WifiP2pDevice>()

    private val prefs by lazy { getSharedPreferences("DersTakipPrefs", Context.MODE_PRIVATE) }
    private val timerHandler = Handler(Looper.getMainLooper())

    private val timerRunnable = object : Runnable {
        override fun run() {
            val startTime = prefs.getLong("study_start_time", 0L)
            if (startTime > 0L) {
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
                tvTimer.text = formatClock(elapsedSec)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (hasWifiPermissions()) {
                        manager.requestPeers(channel) { peerList ->
                            peers.clear()
                            peers.addAll(peerList.deviceList)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                        if (info != null && info.groupFormed) {
                            if (!info.isGroupOwner && info.groupOwnerAddress != null) {
                                targetHostAddress = info.groupOwnerAddress.hostAddress
                                tvConnectionStatus.text = "Durum: Bağlandı (${targetHostAddress})"
                            } else {
                                tvConnectionStatus.text = "Durum: Grup Sahibi (Bağlantı hazır)"
                            }
                        } else {
                            targetHostAddress = null
                            tvConnectionStatus.text = "Durum: Bağlı cihaz yok (Dinlemede)"
                        }
                    }
                }
            }
        }
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(WifiDirectService.EXTRA_MESSAGE) ?: return
            appendLog("Gelen: $msg")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvTimer = findViewById(R.id.tvTimer)
        tvDailyTotal = findViewById(R.id.tvDailyTotal)
        tvLogs = findViewById(R.id.tvLogs)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnStartStudy = findViewById(R.id.btnStartStudy)
        btnFinishStudy = findViewById(R.id.btnFinishStudy)

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        requestRequiredPermissions()
        startBackgroundService()
        restoreState()

        btnDiscover.setOnClickListener { discoverAndShowPeers() }
        btnStartStudy.setOnClickListener { onStartStudyClicked() }
        btnFinishStudy.setOnClickListener { onFinishStudyClicked() }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, WifiDirectService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun restoreState() {
        checkAndResetDailyTotalIfNewDay()
        val dailySec = prefs.getLong("daily_total_sec", 0L)
        tvDailyTotal.text = "Bugün Toplam: ${formatDurationText(dailySec)}"

        val startTime = prefs.getLong("study_start_time", 0L)
        if (startTime > 0L) {
            timerHandler.post(timerRunnable)
        } else {
            tvTimer.text = "00:00:00"
        }

        val savedLogs = prefs.getString("saved_logs", "")
        if (!savedLogs.isNullOrBlank()) {
            tvLogs.text = savedLogs
        }
    }

    private fun onStartStudyClicked() {
        val currentStart = prefs.getLong("study_start_time", 0L)
        if (currentStart > 0L) {
            Toast.makeText(this, "Zaten devam eden bir dersin var!", Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        prefs.edit().putLong("study_start_time", now).apply()
        timerHandler.post(timerRunnable)

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        val msg = "$timeStr - Derse başladım."
        sendOrLogMessage(msg)
    }

    private fun onFinishStudyClicked() {
        val startTime = prefs.getLong("study_start_time", 0L)
        if (startTime == 0L) {
            Toast.makeText(this, "Önce 'Derse Başladım' butonuna basmalısın!", Toast.LENGTH_SHORT).show()
            return
        }

        val endTime = System.currentTimeMillis()
        val sessionSec = ((endTime - startTime) / 1000).coerceAtLeast(1)

        prefs.edit().remove("study_start_time").apply()
        timerHandler.removeCallbacks(timerRunnable)
        tvTimer.text = "00:00:00"

        checkAndResetDailyTotalIfNewDay()
        val prevDailySec = prefs.getLong("daily_total_sec", 0L)
        val newDailySec = prevDailySec + sessionSec
        prefs.edit().putLong("daily_total_sec", newDailySec).apply()

        tvDailyTotal.text = "Bugün Toplam: ${formatDurationText(newDailySec)}"

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endTime))
        val msg = "$timeStr - Dersimi bitirdim. Bu ders: ${formatDurationText(sessionSec)} | Bugün toplam: ${formatDurationText(newDailySec)}"
        sendOrLogMessage(msg)
    }

    private fun sendOrLogMessage(msg: String) {
        appendLog("Giden: $msg")
        val host = targetHostAddress
        if (host != null) {
            WifiDirectService.sendMessage(host, msg) { success ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "Mesaj karşı tarafa iletildi!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Mesaj gönderilemedi, bağlantıyı kontrol et.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Bağlı cihaz yok, mesaj sadece kaydedildi.", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverAndShowPeers() {
        if (!hasWifiPermissions()) {
            requestRequiredPermissions()
            return
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Yakındaki cihazlar aranıyor...", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).postDelayed({
                    showPeersDialog()
                }, 1500)
            }

            override fun onFailure(reason: Int) {
                Toast.makeText(this@MainActivity, "Arama başlatılamadı (Wi-Fi açık mı?)", Toast.LENGTH_SHORT).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun showPeersDialog() {
        if (peers.isEmpty()) {
            Toast.makeText(this, "Yakında cihaz bulunamadı. Diğer telefonda da uygulamayı açın.", Toast.LENGTH_LONG).show()
            return
        }

        val names = peers.map { it.deviceName ?: it.deviceAddress }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bağlanılacak Telefonu Seç")
            .setItems(names) { _, which ->
                val device = peers[which]
                val config = WifiP2pConfig().apply {
                    deviceAddress = device.deviceAddress
                }
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Toast.makeText(this@MainActivity, "${device.deviceName} ile bağlanılıyor...", Toast.LENGTH_SHORT).show()
                    }

                    override fun onFailure(reason: Int) {
                        Toast.makeText(this@MainActivity, "Bağlantı başarısız oldu.", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun checkAndResetDailyTotalIfNewDay() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString("last_study_date", "")
        if (savedDate != today) {
            prefs.edit()
                .putString("last_study_date", today)
                .putLong("daily_total_sec", 0L)
                .apply()
        }
    }

    private fun appendLog(line: String) {
        val current = tvLogs.text.toString()
        val updated = if (current == "Henüz mesaj yok.\n") "$line\n" else "$line\n$current"
        tvLogs.text = updated
        prefs.edit().putString("saved_logs", updated).apply()
    }

    private fun formatClock(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }

    private fun formatDurationText(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "$h sa $m dk $s sn"
            m > 0 -> "$m dk $s sn"
            else -> "$s sn"
        }
    }

    private fun hasWifiPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onResume() {
        super.onResume()
        val p2pFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        registerReceiver(p2pReceiver, p2pFilter)

        val msgFilter = IntentFilter(WifiDirectService.ACTION_MESSAGE_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, msgFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, msgFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(p2pReceiver)
        unregisterReceiver(messageReceiver)
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }
}
