package com.vytros.projects.sharpr

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.vytros.projects.sharpr.data.ImportedImage
import com.vytros.projects.sharpr.data.LibraryStore
import com.vytros.projects.sharpr.data.SettingsStore
import com.vytros.projects.sharpr.data.ThemeMode
import com.vytros.projects.sharpr.engine.ImageAnalysis
import com.vytros.projects.sharpr.engine.UpscaleManager
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.UpscalePipeline
import kotlinx.coroutines.flow.MutableStateFlow

/** The picked-but-not-yet-upscaled image on the Create tab. Lives here so it survives navigation. */
data class Draft(
    val image: ImportedImage,
    val analysis: ImageAnalysis,
    val options: UpscaleOptions,
    /** User tapped "Upscale anyway" for this image. */
    val override: Boolean = false,
)

/** Hand-rolled service locator: the app is small enough that DI would be noise. */
class Graph(app: Application) {
    val settings = SettingsStore(app)
    val library = LibraryStore(app)
    val pipeline by lazy { UpscalePipeline(app, library) }
    val manager by lazy { UpscaleManager(pipeline, settings) }
    val draft = MutableStateFlow<Draft?>(null)
}

class SharprApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        applyNightMode(graph.settings.state.value.themeMode)
    }

    companion object {
        fun applyNightMode(mode: ThemeMode) = AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                ThemeMode.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.Dark -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }
}
