package com.uzaktanbildirim.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BackgroundPermissionMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = DeviceStore(applicationContext)
        val isGranted = isBackgroundPermissionGranted(applicationContext)
        store.backgroundPermissionGranted = isGranted
        store.backgroundPermissionLastCheckedAt = System.currentTimeMillis()

        if (!isGranted) {
            notifyPermissionMissing()
        }

        return Result.success()
    }

    private fun notifyPermissionMissing() {
        val manager = applicationContext.getSystemService<NotificationManager>() ?: return
        ensureChannel(manager)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_background_settings", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            31_337,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_brand_logo_v2_mono)
            .setContentTitle("Check background permission")
            .setContentText("If battery optimization is enabled, notifications, the live connection, and clipboard updates from the PC may become less reliable.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Permission Checks",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "background-permission-monitor"
        private const val CHANNEL_ID = "background_permission_monitor"
        private const val NOTIFICATION_ID = 3101

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundPermissionMonitorWorker>(3, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun isBackgroundPermissionGranted(context: Context): Boolean {
            val powerManager = context.getSystemService<PowerManager>() ?: return false
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}
