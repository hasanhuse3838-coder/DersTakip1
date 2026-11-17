package com.derstakip.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var targetDevice: BluetoothDevice? = null

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

    private val messageReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(BluetoothService.EXTRA_MESSAGE) ?: return
            val savedAddr = prefs.getString("target_bt_address", null)
            val savedName = prefs.getString("target_bt_name", null)
            if (savedAddr != null && targetDevice == null) {
                targetDevice = bluetoothAdapter?.getRemoteDevice(savedAddr)
                tvConnectionStatus.text = "Durum: Bağlı (${savedName ?: savedAddr})"
            }
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

        btnDiscover.text = "Eşleşmiş Telefonu Seç"

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        requestRequiredPermissions()
        startBackgroundService()
        restoreState()

        btnDiscover.setOnClickListener { showPairedDevicesDialog() }
        btnStartStudy.setOnClickListener { onStartStudyClicked() }
        btnFinishStudy.setOnClickListener { onFinishStudyClicked() }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, BluetoothService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    @SuppressLint("MissingPermission")
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

        val savedAddr = prefs.getString("target_bt_address", null)
        val savedName = prefs.getString("target_bt_name", null)
        if (savedAddr != null && bluetoothAdapter != null) {
            targetDevice = bluetoothAdapter?.getRemoteDevice(savedAddr)
            tvConnectionStatus.text = "Durum: Seçili (${savedName ?: savedAddr})"
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
        val device = targetDevice
        if (device != null) {
            BluetoothService.sendMessage(device, msg) { success ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "Mesaj karşı tarafa iletildi!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Mesaj gönderilemedi. Karşı telefonda Bluetooth ve uygulama açık mı?", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Önce 'Eşleşmiş Telefonu Seç' butonundan cihaz seçin.", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        if (!hasBluetoothPermissions()) {
            requestRequiredPermissions()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Lütfen önce Bluetooth'u açın!", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "Eşleşmiş cihaz yok. Önce telefonun Bluetooth ayarlarından diğer telefonu eşleştirin.", Toast.LENGTH_LONG).show()
            return
        }

        val names = pairedDevices.map { "${it.name ?: "Bilinmeyen"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mesaj Gidecek Telefonu Seç")
            .setItems(names) { _, which ->
                val selected = pairedDevices[which]
                targetDevice = selected
                prefs.edit()
                    .putString("target_bt_address", selected.address)
                    .putString("target_bt_name", selected.name ?: selected.address)
                    .apply()
                tvConnectionStatus.text = "Durum: Seçili (${selected.name ?: selected.address})"
                Toast.makeText(this, "${selected.name} seçildi!", Toast.LENGTH_SHORT).show()
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

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        val msgFilter = IntentFilter(BluetoothService.ACTION_MESSAGE_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, msgFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, msgFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(messageReceiver)
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }
}
