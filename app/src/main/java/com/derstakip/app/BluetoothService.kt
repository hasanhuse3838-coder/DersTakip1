package com.derstakip.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.Executors

class BluetoothService : Service() {

    companion object {
        const val CHANNEL_SERVICE = "derstakip_service_channel"
        const val CHANNEL_ALERT = "derstakip_alert_channel"
        const val ACTION_MESSAGE_RECEIVED = "com.derstakip.app.MESSAGE_RECEIVED"
        const val EXTRA_MESSAGE = "extra_message"
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        const val SERVICE_NAME = "DersTakipBT"

        @SuppressLint("MissingPermission")
        fun sendMessage(device: BluetoothDevice, message: String, onResult: (Boolean) -> Unit) {
            Executors.newSingleThreadExecutor().execute {
                try {
                    val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                    socket.connect()
                    val writer = PrintWriter(socket.outputStream, true)
                    writer.println(message)
                    writer.flush()
                    Thread.sleep(200)
                    socket.close()
                    onResult(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                    onResult(false)
                }
            }
        }
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundServiceNotification()
        startServer()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Arka Plan Dinleme",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bluetooth mesajlarını bekler"
                setSound(null, null)
            }
            manager.createNotificationChannel(serviceChannel)

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                "Ders Bildirimleri",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Karşı taraftan ders mesajı geldiğinde sesli uyarı verir"
                enableVibration(true)
                setSound(soundUri, null)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun startForegroundServiceNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("DersTakip Aktif")
            .setContentText("Bluetooth mesajları bekleniyor...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    @SuppressLint("MissingPermission")
    private fun startServer() {
        isRunning = true
        Executors.newSingleThreadExecutor().execute {
            try {
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter: BluetoothAdapter? = btManager.adapter
                if (adapter == null || !adapter.isEnabled) return@execute

                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val remoteDevice = socket.remoteDevice
                    if (remoteDevice != null) {
                        getSharedPreferences("DersTakipPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("target_bt_address", remoteDevice.address)
                            .putString("target_bt_name", remoteDevice.name ?: remoteDevice.address)
                            .apply()
                    }
                    val reader = BufferedReader(InputStreamReader(socket.inputStream))
                    val message = reader.readLine()
                    socket.close()

                    if (!message.isNullOrBlank()) {
                        showAlertNotification(message)
                        val broadcast = Intent(ACTION_MESSAGE_RECEIVED).apply {
                            putExtra(EXTRA_MESSAGE, message)
                            setPackage(packageName)
                        }
                        sendBroadcast(broadcast)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) e.printStackTrace()
            }
        }
    }

    private fun showAlertNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("Yeni Ders Bildirimi")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
