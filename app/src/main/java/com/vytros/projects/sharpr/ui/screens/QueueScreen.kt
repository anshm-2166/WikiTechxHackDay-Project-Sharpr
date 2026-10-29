package com.vytros.projects.sharpr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.Screen
import com.vytros.projects.sharpr.ui.Tab
import com.vytros.projects.sharpr.ui.components.CardShape
import com.vytros.projects.sharpr.ui.components.EmptyState
import com.vytros.projects.sharpr.ui.components.GradientButton
import com.vytros.projects.sharpr.ui.components.Ic
import com.vytros.projects.sharpr.ui.components.IconCircle
import com.vytros.projects.sharpr.ui.components.InfoChip
import com.vytros.projects.sharpr.ui.components.LinearMeter
import com.vytros.projects.sharpr.ui.components.ProgressRing
import com.vytros.projects.sharpr.ui.components.SectionLabel
import com.vytros.projects.sharpr.ui.components.SoftButton
import com.vytros.projects.sharpr.ui.components.mediaIcon
import com.vytros.projects.sharpr.ui.components.pressable
import com.vytros.projects.sharpr.ui.theme.MonoStyle
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.work.JobInfo
import com.vytros.projects.sharpr.work.JobStatus
import com.vytros.projects.sharpr.work.Notifications
import kotlinx.coroutines.delay

@Composable
fun QueueScreen(actions: Actions) {
    val c = Sharpr.colors
    val queue = actions.graph.queue
    val jobs by queue.jobs.collectAsStateWithLifecycle()
    val running = jobs.filter { it.running }
    val waiting = jobs.filter { it.status == JobStatus.Queued }
    val finished = jobs.filter { !it.active }.sortedByDescending { it.finishedAt }

    // ETA text ticks even between progress updates.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                "Queue",
                when {
                    running.isNotEmpty() -> "Working · ${waiting.size} waiting"
                    jobs.isEmpty() -> "Everything you start lands here"
                    else -> "All done"
                },
                inset = 6.dp,
            ) {
                if (finished.isNotEmpty()) SoftButton("Clear", onClick = { queue.clearFinished() })
            }
        }
        if (jobs.isEmpty()) {
            item {
                EmptyState(
                    R.drawable.ic_queue, "Nothing in the queue",
                    "Images, videos and audio you start are processed here one at a time, even with the app closed.",
                    Modifier.padding(top = 40.dp),
                ) { GradientButton("Start something", onClick = { actions.state.goTab(Tab.Studio) }, icon = R.drawable.ic_sparkle) }
            }
        }
        items(running, key = { it.id }) { RunningCard(it, now, onCancel = { queue.cancel(it.id) }) }
        if (waiting.isNotEmpty()) item { SectionLabel("Up next", Modifier.padding(top = 8.dp)) }
        items(waiting, key = { it.id }) { job ->
            JobRow(job) { IconCircle(R.drawable.ic_close, { queue.cancel(job.id) }, size = 38.dp, description = "Cancel") }
        }
        if (finished.isNotEmpty()) item { SectionLabel("Finished", Modifier.padding(top = 8.dp)) }
        items(finished, key = { it.id }) { job ->
            JobRow(job, onClick = job.resultId?.let { id -> { actions.state.push(Screen.Detail(id)) } }) {
                when (job.status) {
                    JobStatus.Done -> SoftButton("Open", onClick = { job.resultId?.let { actions.state.push(Screen.Detail(it)) } }, tint = c.accent)
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconCircle(R.drawable.ic_refresh, { queue.retry(job.id) }, size = 38.dp, description = "Retry", tint = c.accent)
                        IconCircle(R.drawable.ic_close, { queue.remove(job.id) }, size = 38.dp, description = "Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningCard(job: JobInfo, now: Long, onCancel: () -> Unit) {
    val c = Sharpr.colors
    val tint = c.media(job.spec.type)
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(c.surface).border(1.dp, c.outline, CardShape).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(job.fraction, Modifier.size(86.dp), stroke = 8.dp)
                Text("${(job.fraction * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall, color = c.text)
            }
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ic(mediaIcon(job.spec.type), tint = tint, size = 16.dp)
                    Text("  " + Notifications.verb(job.spec.type), style = MaterialTheme.typography.labelMedium, color = tint)
                }
                Text(job.spec.name, style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Text(
                    job.stage + (job.etaMs(now)?.let { " · ${Fmt.duration(it)} left" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                )
            }
        }
        job.detail?.let { Text(it, style = MonoStyle, color = c.muted, modifier = Modifier.padding(top = 14.dp)) }
        Spacer(Modifier.height(12.dp))
        LinearMeter(job.fraction)
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                job.backend?.let { InfoChip(it, icon = R.drawable.ic_chip, tint = c.accent, background = c.accent.copy(alpha = 0.10f)) }
                if (job.fellBack) Text("The GPU stumbled, so Sharpr moved this to the CPU.", style = MaterialTheme.typography.bodySmall, color = c.warn, modifier = Modifier.padding(top = 6.dp))
            }
            SoftButton("Cancel", onClick = onCancel, icon = R.drawable.ic_stop, tint = c.danger)
        }
    }
}

@Composable
private fun JobRow(job: JobInfo, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    val c = Sharpr.colors
    val shape = RoundedCornerShape(22.dp)
    val base = Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.outline, shape)
    Row(
        (if (onClick != null) base.pressable(onClick = onClick) else base).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaIconTile(job.spec.type, size = 42)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(job.spec.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (text, color) = when (job.status) {
                JobStatus.Queued -> "Waiting" to c.muted
                JobStatus.Done -> "Done in ${Fmt.duration(job.finishedAt - job.startedAt)}" to c.success
                JobStatus.Failed -> (job.error ?: "Failed") to c.danger
                JobStatus.Cancelled -> "Cancelled" to c.muted
                JobStatus.Running -> job.stage to c.muted
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        trailing()
    }
}
