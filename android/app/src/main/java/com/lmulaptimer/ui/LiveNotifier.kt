package com.lmulaptimer.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lmulaptimer.MainActivity
import com.lmulaptimer.data.Live
import com.lmulaptimer.data.formatSeconds
import java.util.Locale

/** Ongoing status notification, useful when the phone is docked. */
object LiveNotifier {
    const val CHANNEL_ID = "live_status"
    private const val NOTIFICATION_ID = 4711
    private const val MIN_UPDATE_MS = 1_000L
    private var lastUpdateAt = 0L

    @Synchronized
    fun update(context: Context, live: Live) {
        // A notification is a system IPC. Updating it at the telemetry rate
        // wastes battery and can block the UI thread, so cap it at 1 Hz.
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateAt < MIN_UPDATE_MS) return
        lastUpdateAt = now
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Live-Status",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Stint, Flagge und Rundenzeit" }
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = buildString {
            append(live.track.ifBlank { "LMU Laptimer" })
            if (live.flag.isNotBlank() && live.flag != "none") append(" · ${live.flag.uppercase()}")
        }
        val lap = if (live.lastLapS > 0) {
            String.format(Locale.US, "Letzte Runde %s", formatSeconds(live.lastLapS))
        } else "Stint ${live.stintNumber}"
        val text = buildString {
            append(lap)
            append(" · P${live.position}/${live.numVehicles}")
            if (live.fuelL > 0) append(String.format(Locale.US, " · %.1f L", live.fuelL))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
