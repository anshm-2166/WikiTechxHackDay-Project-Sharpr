package com.vytros.projects.sharpr.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.LibraryStore
import com.vytros.projects.sharpr.data.OutFormat
import com.vytros.projects.sharpr.data.UpscaleRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class UpscaleRequest(
    val sourceFile: File,
    val name: String,
    val options: UpscaleOptions,
    /** Replace this record instead of adding a new version. */
    val replaceId: String? = null,
    /** User chose to run the guard override. */
    val allowOversize: Boolean = false,
)

interface PipelineProgress {
    fun stage(label: String, fraction: Float)
    fun tiles(done: Int, total: Int)
    fun backend(name: String, fallback: Boolean)
}

class UpscaleException(message: String) : Exception(message)

/**
 * decode -> (OpenCV) denoise -> (ncnn) super-resolve -> (OpenCV) blend/sharpen/grain -> encode.
 * Post-processing runs in horizontal bands so a 24 MP result never needs a second full-size copy.
 */
class UpscalePipeline(private val context: Context, private val library: LibraryStore) {

    init {
        OpenCVInit.ensure()
    }

    suspend fun run(req: UpscaleRequest, settings: AppSettings, progress: PipelineProgress): UpscaleRecord =
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            progress.stage("Preparing", 0f)

            val size = ImageIO.readSize(req.sourceFile) ?: throw UpscaleException("Couldn't read that image.")
            val verdict = Guards.evaluate(size, null, req.options, settings, override = true) // the UI already vetted resolution/detail
            val scale = when (verdict) {
                is GuardVerdict.Ok -> verdict.scale
                is GuardVerdict.Blocked -> throw UpscaleException(verdict.reason)
            }
            val opts = req.options.copy(scale = scale)

            var source: Bitmap? = null
            var model: Bitmap? = null
            var out: Bitmap? = null
            val refs = ArrayList<Mat>()
            try {
                source = ImageIO.decodeFull(req.sourceFile)
                val hasAlpha = source.hasAlpha() && hasTransparency(source)
                currentCoroutineContext().ensureActive()

                // ---- pre-process --------------------------------------------------------------
                val (netInput, ref) = prepareInput(source, opts.denoise)
                refs += ref
                model = netInput

                out = try {
                    Bitmap.createBitmap(source.width * scale, source.height * scale, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    throw UpscaleException("Not enough memory for a ${source.width * scale}×${source.height * scale} result. Lower the scale or the output limit.")
                }

                // ---- inference ----------------------------------------------------------------
                progress.stage("Upscaling", 0.05f)
                var backend = ""
                val listener = object : NativeListener {
                    override fun onProgress(done: Int, total: Int) {
                        progress.tiles(done, total)
                        progress.stage("Upscaling", 0.05f + 0.80f * (done.toFloat() / max(1, total)))
                    }

                    override fun onBackend(name: String, fallback: Boolean) {
                        backend = name
                        progress.backend(name, fallback)
                    }
                }
                val rc = runNative(opts, model, out, settings, listener)
                when (rc) {
                    NativeBridge.RC_OK -> Unit
                    NativeBridge.RC_CANCELLED -> throw CancellationException("Cancelled")
                    NativeBridge.RC_NO_GPU -> throw UpscaleException("No usable Vulkan GPU on this device. Switch the engine to Auto or CPU in Settings.")
                    NativeBridge.RC_MODEL_LOAD -> throw UpscaleException("Couldn't load the ${opts.model.label} model.")
                    else -> throw UpscaleException("Inference failed on both engines (code $rc).")
                }
                currentCoroutineContext().ensureActive()

                // ---- post-process -------------------------------------------------------------
                if (opts.natural > 0 || opts.sharpen > 0 || opts.grain > 0 || hasAlpha) {
                    progress.stage("Enhancing", 0.86f)
                    postProcess(out, ref, if (hasAlpha) source else null, opts, scale) { f ->
                        progress.stage("Enhancing", 0.86f + 0.09f * f)
                    }
                }
                currentCoroutineContext().ensureActive()

                // ---- save ---------------------------------------------------------------------
                progress.stage("Saving", 0.95f)
                val format = if (hasAlpha && settings.format == OutFormat.Jpeg) OutFormat.Png else settings.format
                val id = library.newId()
                val resultFile = File(library.results, "$id.${format.ext}")
                FileOutputStream(resultFile).use { fos ->
                    val (cf, q) = compressFormat(format, settings.quality)
                    if (!out.compress(cf, q, fos)) throw UpscaleException("Couldn't write the result.")
                }
                val thumbFile = File(library.thumbs, "$id.${if (hasAlpha) "png" else "jpg"}")
                writeThumb(resultFile, thumbFile, hasAlpha)

                val record = UpscaleRecord(
                    id = id,
                    name = req.name,
                    createdAt = System.currentTimeMillis(),
                    srcW = source.width, srcH = source.height,
                    outW = out.width, outH = out.height,
                    options = opts,
                    backend = backend.ifEmpty { "CPU" },
                    durationMs = System.currentTimeMillis() - started,
                    originalFile = req.sourceFile.absolutePath,
                    resultFile = resultFile.absolutePath,
                    thumbFile = thumbFile.absolutePath,
                )
                library.add(record)
                req.replaceId?.let { library.remove(it) }
                progress.stage("Done", 1f)
                record
            } catch (e: OutOfMemoryError) {
                throw UpscaleException("Ran out of memory. Try a smaller scale, a smaller image or a lower output limit in Settings.")
            } finally {
                refs.forEach { it.release() }
                if (model !== source) model?.recycle()
                source?.recycle()
                out?.recycle()
            }
        }

    private fun runNative(
        opts: UpscaleOptions,
        input: Bitmap,
        output: Bitmap,
        s: AppSettings,
        listener: NativeListener,
    ): Int = NativeBridge.upscale(
        context.assets, opts.model.assetBase(opts.scale), opts.model.nativeScale(opts.scale), opts.scale,
        input, output, s.engine.id, s.cpuFallback, s.threads, s.tile, s.fp16, listener,
    )

    fun cancel() = NativeBridge.cancel()

    private fun hasTransparency(bmp: Bitmap): Boolean {
        val m = Mat()
        val a = Mat()
        return try {
            Utils.bitmapToMat(bmp, m)
            Core.extractChannel(m, a, 3)
            Core.minMaxLoc(a).minVal < 255.0
        } finally {
            m.release(); a.release()
        }
    }

    /**
     * Returns the bitmap fed to the network plus a BGR reference Mat of the same pixels
     * (used for the "natural" blend). The caller owns the Mat; the bitmap is [source] itself unless denoised.
     */
    private fun prepareInput(source: Bitmap, denoise: Int): Pair<Bitmap, Mat> {
        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        if (denoise <= 0) return source to bgr

        val strength = denoise / 100f
        val clean = Mat()
        if (source.width.toLong() * source.height <= 2_500_000L) {
            Photo.fastNlMeansDenoisingColored(bgr, clean, 2f + 10f * strength, 2f + 8f * strength, 7, 21)
        } else {
            val sigma = 10.0 + 60.0 * strength
            Imgproc.bilateralFilter(bgr, clean, 7, sigma, sigma)
        }
        bgr.release()

        val rgbaOut = Mat()
        Imgproc.cvtColor(clean, rgbaOut, Imgproc.COLOR_BGR2RGBA)
        val bmp = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgbaOut, bmp)
        rgbaOut.release()
        return bmp to clean
    }

    private fun postProcess(
        out: Bitmap,
        ref: Mat,
        alphaSource: Bitmap?,
        o: UpscaleOptions,
        scale: Int,
        onProgress: (Float) -> Unit,
    ) {
        val w = out.width
        val h = out.height
        val band = 256
        val margin = 8
        val blend = o.natural / 100.0
        val sharpAmt = o.sharpen / 100.0 * 1.4
        val grainSigma = o.grain / 100.0 * 7.0

        val alphaSrc = alphaSource?.let {
            val rgba = Mat()
            Utils.bitmapToMat(it, rgba)
            Mat().also { a -> Core.extractChannel(rgba, a, 3); rgba.release() }
        }

        var y = 0
        while (y < h) {
            val y1 = min(h, y + band)
            val top = max(0, y - margin)
            val bottom = min(h, y1 + margin)
            val bh = bottom - top

            val ints = IntArray(w * bh)
            out.getPixels(ints, 0, w, 0, top, w, bh)
            val bgra = Mat(bh, w, CvType.CV_8UC4)
            val bytes = ByteArray(w * bh * 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(ints)
            bgra.put(0, 0, bytes)
            val bgr = Mat()
            Imgproc.cvtColor(bgra, bgr, Imgproc.COLOR_BGRA2BGR)

            // dst(x,y) samples src at ((x+.5)/s-.5, (y+top+.5)/s-.5): the same grid cv::resize uses.
            val m = Mat(2, 3, CvType.CV_64F)
            m.put(0, 0, 1.0 / scale, 0.0, 0.5 / scale - 0.5, 0.0, 1.0 / scale, (top + 0.5) / scale - 0.5)

            if (blend > 0) {
                val up = Mat()
                Imgproc.warpAffine(ref, up, m, Size(w.toDouble(), bh.toDouble()),
                    Imgproc.INTER_LANCZOS4 or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                Core.addWeighted(bgr, 1 - blend, up, blend, 0.0, bgr)
                up.release()
            }
            if (sharpAmt > 0) {
                val soft = Mat()
                Imgproc.GaussianBlur(bgr, soft, Size(0.0, 0.0), 1.1)
                Core.addWeighted(bgr, 1 + sharpAmt, soft, -sharpAmt, 0.0, bgr)
                soft.release()
            }
            if (grainSigma > 0) {
                val f = Mat()
                bgr.convertTo(f, CvType.CV_32FC3)
                val noise = Mat(f.size(), CvType.CV_32FC3)
                Core.randn(noise, 0.0, grainSigma)
                Core.add(f, noise, f)
                f.convertTo(bgr, CvType.CV_8UC3)
                f.release(); noise.release()
            }

            val alphaBand: Mat
            if (alphaSrc != null) {
                alphaBand = Mat()
                Imgproc.warpAffine(alphaSrc, alphaBand, m, Size(w.toDouble(), bh.toDouble()),
                    Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
            } else {
                alphaBand = Mat(bh, w, CvType.CV_8UC1, Scalar(255.0))
            }
            val chans = ArrayList<Mat>()
            Core.split(bgr, chans)
            chans += alphaBand
            Core.merge(chans, bgra)
            bgra.get(0, 0, bytes)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)

            val skip = y - top
            out.setPixels(ints, skip * w, w, 0, y, w, y1 - y)

            chans.forEach { it.release() }
            bgra.release(); bgr.release(); m.release()
            onProgress(y1.toFloat() / h)
            y = y1
        }
        alphaSrc?.release()
    }

    private fun compressFormat(f: OutFormat, quality: Int): Pair<Bitmap.CompressFormat, Int> = when (f) {
        OutFormat.Png -> Bitmap.CompressFormat.PNG to 100
        OutFormat.Jpeg -> Bitmap.CompressFormat.JPEG to quality
        OutFormat.Webp ->
            if (Build.VERSION.SDK_INT >= 30) {
                (if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY) to quality
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP to quality
            }
    }

    private fun writeThumb(resultFile: File, thumbFile: File, alpha: Boolean) {
        val sampled = ImageIO.decodeSampled(resultFile, 640) ?: return
        val longest = max(sampled.width, sampled.height)
        val k = 640f / longest
        val thumb = if (k < 1f) Bitmap.createScaledBitmap(sampled, (sampled.width * k).toInt(), (sampled.height * k).toInt(), true) else sampled
        FileOutputStream(thumbFile).use {
            if (alpha) thumb.compress(Bitmap.CompressFormat.PNG, 100, it) else thumb.compress(Bitmap.CompressFormat.JPEG, 88, it)
        }
        if (thumb !== sampled) thumb.recycle()
        sampled.recycle()
    }
}

