package com.tizenappinstallermobile.tizen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tizenappinstallermobile.MainActivity
import com.tizenappinstallermobile.R

/** Keeps an explicit user operation running when another app, such as the browser, is visible. */
class OperationService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "TV operations", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder.setSmallIcon(R.drawable.pocket_icon).setContentTitle("Tizen Pocket")
            .setContentText("TV operation in progress").setContentIntent(open).setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(4794, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(4794, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "tizen-operations"
        fun start(context: Context) {
            val intent = Intent(context, OperationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) { context.stopService(Intent(context, OperationService::class.java)) }
    }
}
