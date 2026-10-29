package com.vytros.projects.sharpr

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vytros.projects.sharpr.data.Accent
import com.vytros.projects.sharpr.data.ImportedMedia
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.data.ThemeMode
import com.vytros.projects.sharpr.engine.Analyzer
import com.vytros.projects.sharpr.engine.AudioOptions
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.VideoOptions
import com.vytros.projects.sharpr.engine.media.MediaProbe
import com.vytros.projects.sharpr.ui.AudioDraft
import com.vytros.projects.sharpr.ui.ImageDraft
import com.vytros.projects.sharpr.ui.Screen
import com.vytros.projects.sharpr.ui.Tab
import com.vytros.projects.sharpr.ui.VideoDraft
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Walks every screen in dark and light and saves PNGs to the app's external files dir
 * (CI pulls them for the README and for review). Also proves each screen composes.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph get() = PipelinesTest.graph
    private val outDir: File by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { deleteRecursively(); mkdirs() }
    }

    private fun shot(name: String, settle: Long = 900) {
        Thread.sleep(settle)
        compose.waitForIdle()
        val bmp = compose.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seed() = runBlocking {
        graph.library.clear()
        val cpu = PipelinesTest.cpu
        val progress = object : com.vytros.projects.sharpr.engine.JobProgress {
            override fun stage(label: String, fraction: Float, detail: String?) = Unit
            override fun backend(name: String, fallback: Boolean) = Unit
        }
        fun own(name: String): File {
            val src = PipelinesTest.asset(name)
            return File(graph.library.originals, "seed-$name").also { src.copyTo(it, overwrite = true) }
        }
        graph.library.add(graph.audio.run(copyToOriginals(PipelinesTest.noisyWav(4)), "Voice memo", AudioOptions(), progress))
        graph.library.add(graph.video.run(own("clip.mp4"), "Test pattern", VideoOptions(scale = 2), cpu, progress))
        graph.library.add(graph.image.run(own("sample.jpg"), "Bug Slayyers", UpscaleOptions(model = ModelId.Fast, scale = 4), cpu, progress))
        graph.sessions.release()
    }

    private fun copyToOriginals(f: File) = File(graph.library.originals, "seed-${f.name}").also { f.copyTo(it, overwrite = true) }

    private fun clickDesc(d: String) = compose.onAllNodesWithContentDescription(d).onFirst().performClick()
    private fun clickText(t: String) = compose.onAllNodesWithText(t).onFirst().performClick()

    @Test
    fun tour() {
        PipelinesTest.grant()
        seed()
        val records = graph.library.records.value
        graph.settings.update { it.copy(onboarded = false, themeMode = ThemeMode.Dark, accent = Accent.Violet, amoled = false) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }

        for (dark in listOf(true, false)) {
            val p = if (dark) "dark" else "light"
            graph.settings.update { it.copy(onboarded = false, themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light) }
            shot("$p-onboarding-1", 1500)
            for (i in 2..5) {
                compose.onAllNodes(isRoot()).onFirst().performTouchInput { swipeLeft() }
                shot("$p-onboarding-$i")
            }
            clickText("Start sharpening")
            shot("$p-studio", 1500)
            clickDesc("Library")
            shot("$p-library", 1500)
            clickDesc("Queue")
            shot("$p-queue")
            clickDesc("Settings")
            shot("$p-settings")
            compose.runOnIdle { activity.state.goTab(Tab.Studio) }

            // Details for each kind of result.
            for (r in records) {
                compose.runOnIdle { activity.state.push(Screen.Detail(r.id)) }
                shot("$p-detail-${r.type.name.lowercase()}", 2500)
                compose.runOnIdle { activity.state.pop() }
            }

            // Editors, fed the same way the pickers would.
            val image = records.first { it.type == MediaType.Image }
            val video = records.first { it.type == MediaType.Video }
            val audio = records.first { it.type == MediaType.Audio }
            val imgDraft = ImageDraft(ImportedMedia(File(image.originalFile), image.name, MediaType.Image), Analyzer.analyze(File(image.originalFile))!!)
            compose.runOnIdle { activity.state.push(Screen.ImageEditor(listOf(imgDraft))) }
            shot("$p-editor-image", 1500)
            compose.runOnIdle { activity.state.replaceTop(Screen.VideoEditor(VideoDraft(ImportedMedia(File(video.originalFile), video.name, MediaType.Video), MediaProbe.video(File(video.originalFile))!!))) }
            shot("$p-editor-video", 1500)
            compose.runOnIdle { activity.state.replaceTop(Screen.AudioEditor(AudioDraft(ImportedMedia(File(audio.originalFile), audio.name, MediaType.Audio), MediaProbe.audio(File(audio.originalFile))!!))) }
            shot("$p-editor-audio", 2000)
            compose.runOnIdle { activity.state.goTab(Tab.Studio) }
        }
        scenario.close()
    }
}
