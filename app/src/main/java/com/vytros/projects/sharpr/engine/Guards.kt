package com.vytros.projects.sharpr.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.vytros.projects.sharpr.data.AppSettings
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sqrt

/** What OpenCV thinks of the picked image. Drives the guard and the "Auto" tuning. */
data class ImageAnalysis(
    val size: ImageIO.Size,
    /** Laplacian variance measured on native-resolution crops (resolution-independent "how sharp is it really"). */
    val detail: Double,
    val noiseSigma: Double,
) {
    val sharpnessLabel: String get() = when {
        detail < SOFT_BELOW -> "Soft"
        detail < CRISP_FROM -> "Normal"
        else -> "Crisp"
    }
    val isCrisp: Boolean get() = detail >= CRISP_FROM
    val noiseLabel: String get() = when {
        noiseSigma < 1.8 -> "Clean"
        noiseSigma < 4.5 -> "Some noise"
        else -> "Noisy"
    }

    /** Pre-denoise strength that suits the measured noise. */
    val suggestedDenoise: Int get() = ((noiseSigma - 1.5) * 12).toInt().coerceIn(0, 70)

    companion object {
        const val SOFT_BELOW = 90.0
        const val CRISP_FROM = 350.0
    }
}

object Analyzer {
    fun analyze(file: File): ImageAnalysis? {
        OpenCVInit.ensure()
        val size = ImageIO.readSize(file) ?: return null
        val sample = ImageIO.decodeSampled(file, 1024) ?: return ImageAnalysis(size, 0.0, 0.0)
        val rgba = Mat()
        val gray = Mat()
        val conv = Mat()
        try {
            Utils.bitmapToMat(sample, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            // Noise: Immerkaer's fast estimator.
            val kernel = Mat(3, 3, CvType.CV_32F).apply {
                put(0, 0, floatArrayOf(1f, -2f, 1f, -2f, 4f, -2f, 1f, -2f, 1f))
            }
            val g32 = Mat()
            gray.convertTo(g32, CvType.CV_32F)
            Imgproc.filter2D(g32, conv, CvType.CV_32F, kernel)
            val abs = Mat()
            Core.absdiff(conv, Scalar(0.0), abs)
            val sum = Core.sumElems(abs).`val`[0]
            val w = gray.cols().toDouble()
            val h = gray.rows().toDouble()
            val sigma = sqrt(0.5 * PI) / (6.0 * (w - 2) * (h - 2)) * sum
            kernel.release(); g32.release(); abs.release()

            val detail = nativeDetail(file, size) ?: laplacianVariance(sample)
            return ImageAnalysis(size, detail, sigma)
        } finally {
            rgba.release(); gray.release(); conv.release()
            sample.recycle()
        }
    }

    /**
     * Sharpness at the file's own resolution. A blurry photo that merely has many pixels scores low here,
     * which is exactly what we want: pixel count alone says nothing about real detail.
     * Takes a 3x3 grid of native crops and uses the 75th percentile, so a sharp subject on a soft
     * background still counts as sharp.
     */
    private fun nativeDetail(file: File, size: ImageIO.Size): Double? {
        val decoder = runCatching { BitmapRegionDecoder.newInstance(file.absolutePath, false) }.getOrNull() ?: return null
        try {
            val w = decoder.width
            val h = decoder.height
            val crop = min(256, min(w, h))
            val scores = ArrayList<Double>()
            for (fy in listOf(0.2, 0.5, 0.8)) for (fx in listOf(0.2, 0.5, 0.8)) {
                val x0 = ((w - crop) * fx).toInt()
                val y0 = ((h - crop) * fy).toInt()
                val bmp = decoder.decodeRegion(Rect(x0, y0, x0 + crop, y0 + crop), BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: continue
                scores += laplacianVariance(bmp)
                bmp.recycle()
            }
            if (scores.isEmpty()) return null
            scores.sort()
            return scores[(scores.size * 3 / 4).coerceAtMost(scores.size - 1)]
        } finally {
            decoder.recycle()
        }
    }

    private fun laplacianVariance(bmp: Bitmap): Double {
        val rgba = Mat()
        val gray = Mat()
        val lap = Mat()
        return try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            // A light blur keeps sensor noise from masquerading as detail.
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.8)
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            val mean = MatOfDouble()
            val std = MatOfDouble()
            Core.meanStdDev(lap, mean, std)
            std.toArray()[0].let { it * it }
        } finally {
            rgba.release(); gray.release(); lap.release()
        }
    }
}

sealed interface GuardVerdict {
    /** [scale] may be lower than requested when the output cap kicks in. */
    data class Ok(val scale: Int, val note: String?) : GuardVerdict
    data class Blocked(val title: String, val reason: String, val canOverride: Boolean) : GuardVerdict
}

object Guards {
    private const val MIN_SIDE = 16
    private const val HIGHRES_MP = 4.0

    /** Largest supported scale (<= requested) whose output stays under the megapixel cap, or 0 if none. */
    private fun fitScale(size: ImageIO.Size, model: ModelId, requested: Int, maxOutMp: Int): Int =
        model.scales.filter { it <= requested && size.megapixels * it * it <= maxOutMp }.maxOrNull() ?: 0

    /**
     * Resolution alone never blocks an image: a soft 2K photo is the best candidate there is.
     * Only images that are both big AND already crisp are held back (they gain almost nothing).
     * [detail] is the native-resolution sharpness score; null means "unknown, don't block on it".
     */
    fun evaluate(size: ImageIO.Size, detail: Double?, options: UpscaleOptions, settings: AppSettings, override: Boolean): GuardVerdict {
        if (size.w < MIN_SIDE || size.h < MIN_SIDE) {
            return GuardVerdict.Blocked("Too small", "This image is only ${size.w}×${size.h}px, so there's not enough to work with.", false)
        }
        val highRes = size.longest >= settings.maxInputSide || size.megapixels >= HIGHRES_MP
        val crisp = detail != null && detail >= ImageAnalysis.CRISP_FROM
        if (highRes && crisp && !override) {
            return GuardVerdict.Blocked(
                "Already high resolution and sharp",
                "At ${size.w}×${size.h} (${"%.1f".format(size.megapixels)} MP) this image is detailed enough already. " +
                    "upscaling it would burn time and memory for little gain.",
                canOverride = !settings.strictGuard,
            )
        }
        val scale = fitScale(size, options.model, options.scale, settings.maxOutputMp)
        if (scale == 0) {
            return GuardVerdict.Blocked(
                "Output would be too large",
                "Even the 1× enhance mode exceeds your ${settings.maxOutputMp} MP output limit. Raise it in Settings → Guards.",
                false,
            )
        }
        val notes = ArrayList<String>()
        if (highRes && !crisp) {
            notes += if (detail != null && detail < ImageAnalysis.SOFT_BELOW) {
                "Large but soft. Sharpr will recover detail rather than just enlarge it."
            } else {
                "Large, but not razor-sharp. Sharpr can still add real detail."
            }
        }
        if (scale < options.scale) {
            notes += if (scale == 1) {
                "Too big to enlarge within your ${settings.maxOutputMp} MP limit, so Sharpr will enhance it at the same size."
            } else {
                "Scale lowered to ${scale}× to stay under your ${settings.maxOutputMp} MP output limit."
            }
        }
        return GuardVerdict.Ok(scale, notes.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
}
