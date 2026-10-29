package com.vytros.projects.sharpr.work

import android.content.Context
import com.vytros.projects.sharpr.data.Export
import com.vytros.projects.sharpr.data.LibraryStore
import com.vytros.projects.sharpr.data.MediaRecord
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.data.SettingsStore
import com.vytros.projects.sharpr.engine.AudioOptions
import com.vytros.projects.sharpr.engine.AudioPipeline
import com.vytros.projects.sharpr.engine.ImagePipeline
import com.vytros.projects.sharpr.engine.JobProgress
import com.vytros.projects.sharpr.engine.NativeBridge
import com.vytros.projects.sharpr.engine.SessionCache
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.VideoOptions
import com.vytros.projects.sharpr.engine.VideoPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** What to process. [source] is a file Sharpr owns (imported into the library's originals). */
sealed interface JobSpec {
    val source: File
    val name: String
    val type: MediaType

    data class Image(
        override val source: File, override val name: String, val options: UpscaleOptions, val replaceId: String? = null,
    ) : JobSpec {
        override val type get() = MediaType.Image
    }

    data class Video(override val source: File, override val name: String, val options: VideoOptions) : JobSpec {
        override val type get() = MediaType.Video
    }

    data class Audio(override val source: File, override val name: String, val options: AudioOptions) : JobSpec {
        override val type get() = MediaType.Audio
    }
}

enum class JobStatus { Queued, Running, Done, Failed, Cancelled }

data class JobInfo(
    val id: String,
    val spec: JobSpec,
    val status: JobStatus = JobStatus.Queued,
    val stage: String = "Waiting",
    val fraction: Float = 0f,
    val detail: String? = null,
    val backend: String? = null,
    val fellBack: Boolean = false,
    val resultId: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
) {
    val active: Boolean get() = status == JobStatus.Queued || status == JobStatus.Running
    val running: Boolean get() = status == JobStatus.Running

    /** Remaining time from the progress rate so far, once it is meaningful. */
    fun etaMs(now: Long = System.currentTimeMillis()): Long? {
        if (!running || fraction < 0.03f || startedAt == 0L) return null
        val elapsed = now - startedAt
        if (elapsed < 2500) return null
        return (elapsed / fraction * (1 - fraction)).toLong()
    }
}

sealed interface JobEvent {
    data class Finished(val job: JobInfo, val record: MediaRecord) : JobEvent
    data class Failed(val job: JobInfo) : JobEvent
}

/**
 * App-scoped, sequential work queue. One job runs at a time (they all want the whole GPU); the
 * foreground [ProcessingService] keeps the process alive and mirrors progress into a notification.
 */
class JobQueue(
    private val app: Context,
    private val settings: SettingsStore,
    private val library: LibraryStore,
    private val sessions: SessionCache,
    private val image: ImagePipeline,
    private val video: VideoPipeline,
    private val audio: AudioPipeline,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var current: Job? = null
    private var currentId: String? = null
    private var idleRelease: Job? = null

    private val _jobs = MutableStateFlow<List<JobInfo>>(emptyList())
    val jobs: StateFlow<List<JobInfo>> = _jobs

    private val _events = MutableSharedFlow<JobEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<JobEvent> = _events

    init {
        scope.launch { loop() }
    }

    fun enqueue(spec: JobSpec): String {
        val id = UUID.randomUUID().toString()
        _jobs.update { it + JobInfo(id, spec) }
        idleRelease?.cancel()
        wake.trySend(Unit)
        ProcessingService.start(app)
        return id
    }

    fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.status) {
            JobStatus.Queued -> patch(id) { it.copy(status = JobStatus.Cancelled, finishedAt = System.currentTimeMillis(), stage = "Cancelled") }
            JobStatus.Running -> if (currentId == id) {
                NativeBridge.cancel()
                current?.cancel()
            }
            else -> Unit
        }
    }

    fun cancelAll() {
        _jobs.value.filter { it.status == JobStatus.Queued }.forEach { cancel(it.id) }
        currentId?.let { cancel(it) }
    }

    fun remove(id: String) = _jobs.update { list -> list.filterNot { it.id == id && !it.active } }

    fun clearFinished() = _jobs.update { list -> list.filter { it.active } }

    /** Re-queues a failed or cancelled job with the same settings. */
    fun retry(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id && !it.active } ?: return
        remove(id)
        enqueue(job.spec)
    }

    private suspend fun loop() {
        while (true) {
            val next = _jobs.value.firstOrNull { it.status == JobStatus.Queued }
            if (next == null) {
                scheduleRelease()
                wake.receive()
                continue
            }
            val job = scope.launch { execute(next) }
            current = job
            currentId = next.id
            job.join()
            current = null
            currentId = null
        }
    }

    /** Frees GPU memory once the queue has been idle for a while. */
    private fun scheduleRelease() {
        idleRelease?.cancel()
        idleRelease = scope.launch {
            delay(45_000)
            if (_jobs.value.none { it.active }) sessions.release()
        }
    }

    private suspend fun execute(info: JobInfo) {
        val id = info.id
        patch(id) { it.copy(status = JobStatus.Running, stage = "Starting", startedAt = System.currentTimeMillis()) }
        val progress = object : JobProgress {
            override fun stage(label: String, fraction: Float, detail: String?) {
                patch(id) { it.copy(stage = label, fraction = fraction.coerceIn(0f, 1f), detail = detail) }
            }

            override fun backend(name: String, fallback: Boolean) {
                patch(id) { it.copy(backend = name, fellBack = it.fellBack || fallback) }
            }
        }
        val s = settings.state.value
        try {
            val record = when (val spec = info.spec) {
                is JobSpec.Image -> image.run(spec.source, spec.name, spec.options, s, progress)
                is JobSpec.Video -> video.run(spec.source, spec.name, spec.options, s, progress)
                is JobSpec.Audio -> audio.run(spec.source, spec.name, spec.options, progress)
            }
            library.add(record)
            (info.spec as? JobSpec.Image)?.replaceId?.let { library.remove(it) }
            if (s.autoSave) runCatching { Export.save(app, record) }
            val done = patch(id) {
                it.copy(status = JobStatus.Done, stage = "Done", fraction = 1f, detail = null, resultId = record.id, finishedAt = System.currentTimeMillis())
            }
            done?.let {
                _events.tryEmit(JobEvent.Finished(it, record))
                Notifications.finished(app, it, record)
            }
        } catch (e: CancellationException) {
            patch(id) { it.copy(status = JobStatus.Cancelled, stage = "Cancelled", detail = null, finishedAt = System.currentTimeMillis()) }
        } catch (e: OutOfMemoryError) {
            fail(id, "Ran out of memory. Try a smaller scale or a lighter model.")
        } catch (e: Throwable) {
            fail(id, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(id: String, message: String) {
        val failed = patch(id) {
            it.copy(status = JobStatus.Failed, stage = "Failed", error = message, detail = null, finishedAt = System.currentTimeMillis())
        }
        failed?.let {
            _events.tryEmit(JobEvent.Failed(it))
            Notifications.failed(app, it)
        }
    }

    private inline fun patch(id: String, crossinline f: (JobInfo) -> JobInfo): JobInfo? {
        var result: JobInfo? = null
        _jobs.update { list ->
            list.map { if (it.id == id) f(it).also { n -> result = n } else it }
        }
        return result
    }
}
