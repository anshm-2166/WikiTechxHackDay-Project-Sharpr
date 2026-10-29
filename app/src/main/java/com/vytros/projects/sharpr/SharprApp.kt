package com.vytros.projects.sharpr

import android.app.Application
import com.vytros.projects.sharpr.data.LibraryStore
import com.vytros.projects.sharpr.data.SettingsStore
import com.vytros.projects.sharpr.engine.AudioPipeline
import com.vytros.projects.sharpr.engine.ImagePipeline
import com.vytros.projects.sharpr.engine.SessionCache
import com.vytros.projects.sharpr.engine.VideoPipeline
import com.vytros.projects.sharpr.work.JobQueue
import com.vytros.projects.sharpr.work.Notifications

/** Hand-rolled service locator: the app is small enough that DI would be noise. */
class Graph(app: Application) {
    val settings = SettingsStore(app)
    val library = LibraryStore(app)
    val sessions by lazy { SessionCache(app.assets) }
    val audio by lazy { AudioPipeline(library) }
    val image by lazy { ImagePipeline(app, library, sessions) }
    val video by lazy { VideoPipeline(library, sessions, audio) }
    val queue by lazy { JobQueue(app, settings, library, sessions, image, video, audio) }
}

class SharprApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Notifications.ensureChannels(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Drop the loaded network when the system is short on memory and nothing is running.
        if (level >= TRIM_MEMORY_RUNNING_LOW && graph.queue.jobs.value.none { it.active }) graph.sessions.release()
    }
}
