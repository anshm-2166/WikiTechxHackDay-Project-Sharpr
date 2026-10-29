package com.vytros.projects.sharpr

import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.MediaRecord
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.engine.AudioMode
import com.vytros.projects.sharpr.engine.AudioOptions
import com.vytros.projects.sharpr.engine.GuardVerdict
import com.vytros.projects.sharpr.engine.Guards
import com.vytros.projects.sharpr.engine.ImageAnalysis
import com.vytros.projects.sharpr.engine.ImageIO
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.VideoOptions
import com.vytros.projects.sharpr.work.JobInfo
import com.vytros.projects.sharpr.work.JobSpec
import com.vytros.projects.sharpr.work.JobStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelsTest {
    @Test
    fun everyModelHasConsistentGeometry() {
        for (m in ModelId.entries) {
            for (s in m.scales) for (denoise in listOf(false, true)) {
                val net = m.net(s, denoise)
                assertTrue("$m prepad must cover the crop", net.prepad >= net.crop)
                assertEquals("$m ${s}x must divide the native scale", 0, net.scale % s)
                assertTrue(net.param.startsWith("models/") && net.param.endsWith(".param"))
            }
        }
    }

    @Test
    fun denoiseVariantsOnlyWhereAdvertised() {
        for (m in ModelId.entries.filter { !it.hasDenoiseVariant }) {
            assertEquals(m.net(m.scales.max(), false), m.net(m.scales.max(), true))
        }
        assertTrue(ModelId.Anime.net(4, true).base.contains("denoise"))
        assertTrue(ModelId.Anime.net(4, false).residual)
    }

    @Test
    fun fitScalePicksNearestSupported() {
        assertEquals(2, ModelId.Photo.fitScale(3)) // 2 and 4 tie; the smaller wins
        assertEquals(1, ModelId.Restore.fitScale(4))
        assertEquals(3, ModelId.Fast.fitScale(3))
    }

    @Test
    fun allModelFilesShip() {
        val dir = listOf(File("src/main/assets/models"), File("app/src/main/assets/models")).first { it.isDirectory }
        for (m in ModelId.entries) for (s in m.scales) for (d in listOf(false, true)) {
            val net = m.net(s, d)
            assertTrue("missing ${net.base}.param", File(dir, "${net.base}.param").isFile)
            assertTrue("missing ${net.base}.bin", File(dir, "${net.base}.bin").isFile)
        }
    }
}

class GuardsTest {
    private val settings = AppSettings()

    @Test
    fun tinyImagesAreBlocked() {
        val v = Guards.evaluate(ImageIO.Size(10, 300), 50.0, UpscaleOptions(), settings, override = false)
        assertTrue(v is GuardVerdict.Blocked && !v.canOverride)
    }

    @Test
    fun largeSharpImagesNeedAnOverride() {
        val size = ImageIO.Size(4000, 3000)
        val v = Guards.evaluate(size, ImageAnalysis.CRISP_FROM + 1, UpscaleOptions(scale = 1), settings, override = false)
        assertTrue(v is GuardVerdict.Blocked && v.canOverride)
        val strict = Guards.evaluate(size, ImageAnalysis.CRISP_FROM + 1, UpscaleOptions(scale = 1), settings.copy(strictGuard = true), override = false)
        assertTrue(strict is GuardVerdict.Blocked && !strict.canOverride)
    }

    @Test
    fun largeSoftImagesAreWelcome() {
        val v = Guards.evaluate(ImageIO.Size(2400, 1600), 40.0, UpscaleOptions(scale = 2), settings, override = false)
        assertTrue(v is GuardVerdict.Ok)
    }

    @Test
    fun scaleIsLoweredToFitTheOutputCap() {
        // 1.5 MP source under a 12 MP cap: 4x (24 MP) and 3x (13.5 MP) are too big, 2x (6 MP) fits.
        val v = Guards.evaluate(ImageIO.Size(1500, 1000), 50.0, UpscaleOptions(scale = 4), settings.copy(maxOutputMp = 12), override = false)
        assertTrue(v is GuardVerdict.Ok)
        assertEquals(2, (v as GuardVerdict.Ok).scale)
    }
}

class RecordJsonTest {
    @Test
    fun v1RecordsLoadAsImages() {
        val v1 = JSONObject(
            """{"id":"a","name":"cat","createdAt":1,"srcW":10,"srcH":20,"outW":40,"outH":80,
               "options":{"model":"Photo","scale":4,"denoise":10,"natural":0,"sharpen":5,"grain":0},
               "backend":"GPU","durationMs":1200,"originalFile":"/o","resultFile":"/r","thumbFile":"/t"}""",
        )
        val r = MediaRecord.fromJson(v1)
        assertEquals(MediaType.Image, r.type)
        assertEquals(ModelId.Photo, r.image!!.model)
        assertEquals(10, r.image!!.denoise)
        assertNull(r.video)
    }

    @Test
    fun roundTripsEveryType() {
        val base = MediaRecord("id", MediaType.Video, "clip", 5, 640, 360, 1280, 720, "GPU", 9, "/o", "/r", "/t", mediaMs = 3000,
            video = VideoOptions(model = ModelId.Anime, scale = 2, cleanAudio = true, previewSeconds = 3), note = "n")
        assertEquals(base, MediaRecord.fromJson(base.toJson()))
        val audio = base.copy(type = MediaType.Audio, video = null, audio = AudioOptions(mode = AudioMode.Universal, strength = 33))
        assertEquals(audio, MediaRecord.fromJson(audio.toJson()))
    }
}

class JobInfoTest {
    @Test
    fun etaFollowsProgressRate() {
        val spec = JobSpec.Audio(File("/x"), "x", AudioOptions())
        val job = JobInfo("1", spec, JobStatus.Running, fraction = 0.25f, startedAt = 1_000)
        assertEquals(30_000L, job.etaMs(now = 11_000))
        assertNull(job.copy(fraction = 0.01f).etaMs(now = 11_000))
        assertFalse(job.copy(status = JobStatus.Done).active)
    }
}
