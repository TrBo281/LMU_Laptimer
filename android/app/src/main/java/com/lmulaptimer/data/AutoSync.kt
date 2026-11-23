package com.lmulaptimer.data

import android.Manifest
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lmulaptimer.MainActivity
import com.lmulaptimer.R
import java.util.concurrent.TimeUnit

class AutoSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = Repo(applicationContext)
        if (!repo.autoSync || repo.baseUrl.isBlank()) return Result.success()
        val result = repo.sync()
        return when {
            result.error != null -> Result.retry()
            result.sessions > 0 || result.laps > 0 -> {
                AutoSync.notify(applicationContext, result.sessions, result.laps)
                Result.success()
            }
            else -> Result.success()
        }
    }
}

object AutoSync {
    private const val UNIQUE_PERIODIC = "lmu_auto_sync"
    private const val UNIQUE_NOW = "lmu_auto_sync_now"
    private const val CHANNEL_ID = "lmu_auto_sync"
    private const val NOTIFICATION_ID = 8756

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NOW,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NOW)
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun notify(context: Context, sessions: Int, laps: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Auto-Sync",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Importiert Sessions, sobald die Bridge erreichbar ist."
                }
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            sessions == 1 && laps == 1 -> "1 Session, 1 Runde importiert"
            sessions == 1 -> "1 Session, $laps Runden importiert"
            sessions > 1 -> "$sessions Sessions, $laps Runden importiert"
            laps == 1 -> "1 Runde aktualisiert"
            else -> "$laps Runden aktualisiert"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_laptimer)
            .setContentTitle("LMU Laptimer")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
