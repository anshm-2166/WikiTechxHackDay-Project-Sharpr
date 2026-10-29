package com.vytros.projects.sharpr.work

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vytros.projects.sharpr.SharprApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps Sharpr alive (and the CPU awake) while the queue has work, so long videos keep going with the
 * screen off or the app in the background. Progress and a Cancel action live in its notification.
 */
class ProcessingService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPost = 0L
    private var lastPct = -1

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        goForeground()
        val queue = (application as SharprApp).graph.queue
        lifecycleScope.launch {
            queue.jobs.collectLatest { jobs ->
                val running = jobs.firstOrNull { it.running }
                val waiting = jobs.count { it.status == JobStatus.Queued }
                if (running == null && waiting == 0) {
                    stop()
                    return@collectLatest
                }
                // Notification updates are rate limited by the system; keep them meaningful.
                val pct = ((running?.fraction ?: 0f) * 100).toInt()
                val now = System.currentTimeMillis()
                if (pct != lastPct && now - lastPost > 900 || running?.stage != null && now - lastPost > 3000) {
                    lastPost = now
                    lastPct = pct
                    runCatching {
                        NotificationManagerCompat.from(this@ProcessingService)
                            .notify(Notifications.ID_PROGRESS, Notifications.progress(this@ProcessingService, running, waiting))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground()
        if (intent?.action == ACTION_CANCEL) {
            val queue = (application as SharprApp).graph.queue
            queue.jobs.value.firstOrNull { it.running }?.let { queue.cancel(it.id) }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        val queue = (application as SharprApp).graph.queue
        val jobs = queue.jobs.value
        val n = Notifications.progress(this, jobs.firstOrNull { it.running }, jobs.count { it.status == JobStatus.Queued })
        ServiceCompat.startForeground(this, Notifications.ID_PROGRESS, n, type)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sharpr:processing")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
    }

    /** Android 15+: the system's daily budget for media processing ran out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as SharprApp).graph.queue.cancelAll()
        stop()
    }

    private fun stop() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.vytros.projects.sharpr.CANCEL"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java)) }
        }
    }
}
