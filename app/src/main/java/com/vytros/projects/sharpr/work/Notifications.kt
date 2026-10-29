package com.vytros.projects.sharpr.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vytros.projects.sharpr.MainActivity
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.MediaRecord
import com.vytros.projects.sharpr.data.MediaType
import java.util.Locale

object Notifications {
    const val CHANNEL_PROGRESS = "processing"
    const val CHANNEL_RESULTS = "results"
    const val ID_PROGRESS = 1001
    const val EXTRA_OPEN = "open"
    const val OPEN_QUEUE = "queue"
    const val EXTRA_RECORD = "record"

    /** True while an activity of ours is started; finished-notifications are only for when you look away. */
    @Volatile
    var appVisible = false

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_processing), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_processing_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_results_desc)
            },
        )
    }

    private fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openIntent(context: Context, extra: Pair<String, String>, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(extra.first, extra.second)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun verb(type: MediaType) = when (type) {
        MediaType.Image -> "Sharpening image"
        MediaType.Video -> "Upscaling video"
        MediaType.Audio -> "Cleaning audio"
    }

    fun progress(context: Context, running: JobInfo?, waiting: Int): Notification {
        val cancel = PendingIntent.getService(
            context, 2, Intent(context, ProcessingService::class.java).setAction(ProcessingService.ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent(context, EXTRA_OPEN to OPEN_QUEUE, 1))
        if (running == null) {
            return b.setContentTitle("Sharpr").setContentText("Getting ready…").setProgress(0, 0, true).build()
        }
        val pct = (running.fraction * 100).toInt()
        val eta = running.etaMs()?.let { " · ${duration(it)} left" } ?: ""
        val more = if (waiting > 0) " · $waiting more queued" else ""
        return b.setContentTitle("${verb(running.spec.type)} · $pct%")
            .setContentText("${running.spec.name} — ${running.stage}$eta$more")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(
                "${running.spec.name} — ${running.stage}$eta",
                running.detail,
                running.backend,
                if (waiting > 0) "$waiting more queued" else null,
            ).joinToString("\n")))
            .setProgress(100, pct, running.fraction <= 0f)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    fun finished(context: Context, job: JobInfo, record: MediaRecord) {
        if (appVisible || !canPost(context)) return
        val what = when (record.type) {
            MediaType.Image -> "${record.scale}× image ready"
            MediaType.Video -> "${record.scale}× video ready"
            MediaType.Audio -> "Clean audio ready"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(what)
            .setContentText("${record.name} · took ${duration(record.durationMs)}")
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, EXTRA_RECORD to record.id, record.id.hashCode()))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(job.id.hashCode(), n) }
    }

    fun failed(context: Context, job: JobInfo) {
        if (appVisible || !canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle("Couldn't finish ${job.spec.name}")
            .setContentText(job.error ?: "Something went wrong.")
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, EXTRA_OPEN to OPEN_QUEUE, 3))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(job.id.hashCode(), n) }
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            ms < 1000 -> "${ms}ms"
            s < 60 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}
