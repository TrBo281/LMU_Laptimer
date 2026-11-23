package com.lmulaptimer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lmulaptimer.data.Repo

/** Home-screen streak based on local sessions; no network or account needed. */
class TrainingStreakWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        Thread {
            try {
                val repo = Repo(context.applicationContext)
                val streak = repo.trainingStreak()
                val subtitle = if (streak == 0) {
                    "Heute noch keine Session – Zeit für eine Runde."
                } else {
                    "Trainingstage in Folge"
                }
                val views = RemoteViews(context.packageName, R.layout.widget_training_streak).apply {
                    setTextViewText(R.id.widget_streak, streak.toString())
                    setTextViewText(R.id.widget_streak_subtitle, subtitle)
                    setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
                appWidgetManager.updateAppWidget(appWidgetIds, views)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TrainingStreakWidget::class.java))
            if (ids.isNotEmpty()) {
                Intent(context, TrainingStreakWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }.let(context::sendBroadcast)
            }
        }
    }
}
