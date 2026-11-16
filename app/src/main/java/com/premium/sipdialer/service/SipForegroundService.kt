package com.premium.sipdialer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.premium.sipdialer.MainActivity
import com.premium.sipdialer.SipApplication

class SipForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        startForeground(1001, buildPersistentNotification())
        (application as SipApplication).sipEngine.initializeEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as SipApplication).sipEngine.initializeEngine()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildPersistentNotification(): Notification {
        val channelId = "sip_background_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SIP Active Line Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps multiple SIP accounts registered for incoming calls"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Premium SIP Dialer Active")
            .setContentText("Listening for incoming VoIP calls across registered accounts")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, SipForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
