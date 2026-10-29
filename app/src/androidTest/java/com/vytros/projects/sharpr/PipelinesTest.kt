package com.vytros.projects.sharpr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.EngineMode
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.engine.AudioMode
import com.vytros.projects.sharpr.engine.AudioOptions
import com.vytros.projects.sharpr.engine.ImageIO
import com.vytros.projects.sharpr.engine.JobProgress
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.VideoOptions
import com.vytros.projects.sharpr.engine.media.AudioCodecs
import com.vytros.projects.sharpr.engine.media.MediaProbe
import com.vytros.projects.sharpr.engine.media.PcmInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Runs the real pipelines (native core, MediaCodec, GL) on the device. */
@RunWith(AndroidJUnit4::class)
class PipelinesTest {
    companion object {
        private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SharprApp
        val graph get() = app.graph
        val cpu = AppSettings(engine = EngineMode.Cpu)
        val dir: File get() = File(app.cacheDir, "test-input").apply { mkdirs() }

        @BeforeClass
        @JvmStatic
        fun grant() {
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            if (android.os.Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }

        fun testImage(w: Int, h: Int, name: String = "img.png"): File {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(40, 90, 160))
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.rgb(240, 200, 60)
            c.drawCircle(w * 0.35f, h * 0.5f, h * 0.3f, p)
            p.color = Color.WHITE
            p.strokeWidth = 2f
            for (i in -h until w step 9) c.drawLine(i.toFloat(), 0f, (i + h).toFloat(), h.toFloat(), p)
            return File(dir, name).also { f -> FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }

        fun asset(name: String): File {
            val out = File(dir, name)
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> out.outputStream().use { i.copyTo(it) } }
            return out
        }

        /** Stereo 44.1 kHz: a "voice-like" harmonic tone plus noise and a 50 Hz hum. */
        fun noisyWav(seconds: Int = 3): File {
            val rate = 44100
            val frames = rate * seconds
            val pcm = File(dir, "noisy.pcm")
            val rnd = Random(3)
            val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                val t = i.toDouble() / rate
                val env = 0.5 * (1 - kotlin.math.cos(2 * PI * 2.5 * t))
                var v = 0.0
                for (h in 1..12) v += sin(2 * PI * 160 * h * t) / h
                v = 0.2 * env * v + 0.03 * sin(2 * PI * 50 * t) + 0.02 * (rnd.nextDouble() * 2 - 1)
                val s = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
                buf.putShort(s).putShort(s)
            }
            pcm.writeBytes(buf.array())
            val wav = File(dir, "noisy.wav")
            AudioCodecs.writeWav(pcm, PcmInfo(rate, 2, frames.toLong()), wav)
            return wav
        }
    }

    private val progress = object : JobProgress {
        var lastFraction = 0f
        var backend: String? = null
        override fun stage(label: String, fraction: Float, detail: String?) {
            lastFraction = fraction
        }
        override fun backend(name: String, fallback: Boolean) {
            backend = name
        }
    }

    @Test
    fun everyImageModelRunsOnTheCpu() = runBlocking {
        val src = testImage(70, 46)
        for (m in ModelId.entries) {
            val scale = m.scales.max()
            val r = graph.image.run(src, "t-$m", UpscaleOptions(model = m, scale = scale), cpu, progress)
            assertEquals("$m width", 70 * scale, r.outW)
            assertEquals("$m height", 46 * scale, r.outH)
            assertTrue(File(r.resultFile).length() > 0)
            val decoded = ImageIO.readSize(File(r.resultFile))!!
            assertEquals(70 * scale, decoded.w)
        }
        graph.sessions.release()
    }

    @Test
    fun denoiseVariantsAndFinishingRun() = runBlocking {
        val src = testImage(64, 40, "fin.png")
        val o = UpscaleOptions(model = ModelId.Anime, scale = 4, aiDenoise = true, denoise = 30, natural = 20, sharpen = 30, grain = 10)
        val r = graph.image.run(src, "fin", o, cpu, progress)
        assertEquals(256, r.outW)
        val enhance = graph.image.run(src, "enh", UpscaleOptions(model = ModelId.Fast, scale = 1), cpu.copy(tta = true), progress)
        assertEquals(64, enhance.outW)
        graph.sessions.release()
    }

    @Test
    fun autoEngineAlwaysFinishes() = runBlocking {
        val r = graph.image.run(testImage(48, 32, "auto.png"), "auto", UpscaleOptions(scale = 2), AppSettings(), progress)
        assertEquals(96, r.outW)
        assertNotNull(progress.backend)
        graph.sessions.release()
    }

    @Test
    fun videoUpscalesWithSoundAndReusesHeldFrames() = runBlocking {
        val clip = asset("clip.mp4")
        val r = graph.video.run(clip, "clip", VideoOptions(model = ModelId.Fast, scale = 2), cpu, progress)
        assertEquals(384, r.outW)
        assertEquals(216, r.outH)
        val info = MediaProbe.video(File(r.resultFile))!!
        assertEquals(384, info.width)
        assertEquals(216, info.height)
        assertTrue("audio kept", info.hasAudio)
        assertTrue("duration ${info.durationUs}", abs(info.durationUs - 2_000_000) < 300_000)
        assertTrue("reused frames noted: ${r.note}", r.note.contains("repeated frames reused"))
        assertTrue(File(r.thumbFile).length() > 0)
        graph.sessions.release()
    }

    @Test
    fun videoPreviewWithCleanAudio() = runBlocking {
        val clip = asset("clip.mp4")
        val r = graph.video.run(
            clip, "clip-clean",
            VideoOptions(model = ModelId.Anime, scale = 2, cleanAudio = true, previewSeconds = 1, skipDuplicates = false),
            cpu, progress,
        )
        val info = MediaProbe.video(File(r.resultFile))!!
        assertEquals(384, info.width)
        assertTrue(info.hasAudio)
        assertTrue("preview length ${info.durationUs}", info.durationUs in 800_000..1_300_000)
        graph.sessions.release()
    }

    @Test
    fun audioIsCleanedToM4aAndWav() = runBlocking {
        val wav = noisyWav()
        for (format in listOf("M4a", "Wav")) {
            val r = graph.audio.run(wav, "noisy-$format", AudioOptions(mode = AudioMode.Voice, format = format), progress)
            assertEquals(MediaType.Audio, r.type)
            assertEquals(48000, r.outW)
            assertEquals(2, r.outH)
            assertTrue("hum found: ${r.note}", r.note.contains("50 Hz"))
            val info = MediaProbe.audio(File(r.resultFile))!!
            assertEquals(48000, info.sampleRate)
            assertTrue("duration ${info.durationUs}", abs(info.durationUs - 3_000_000) < 150_000)
            assertTrue(File(r.peaksIn).length() > 0 && File(r.peaksOut).length() > 0)
        }
    }
}
