package com.vytros.projects.sharpr.engine

import android.content.res.AssetManager
import android.graphics.Bitmap
import com.vytros.projects.sharpr.data.AppSettings
import java.nio.ByteBuffer

/** Callbacks invoked from native code, on the calling thread. */
interface NativeListener {
    fun onProgress(done: Int, total: Int)
    fun onBackend(name: String, fallback: Boolean)
}

/** Thin JNI surface over the native core (app/src/main/cpp). */
object NativeBridge {
    const val RC_OK = 0
    const val RC_CANCELLED = 1
    const val RC_BAD_INPUT = -1
    const val RC_MODEL_LOAD = -2
    const val RC_INFERENCE = -3
    const val RC_NO_GPU = -4
    const val RC_IO = -5

    init {
        System.loadLibrary("sharpr_native")
    }

    /** "Device name|heapBudgetMB", or empty when no Vulkan device is usable. */
    external fun gpuInfo(): String
    external fun cpuCores(): Int
    external fun cancel()
    external fun resetCancel()
    external fun lastError(): Int

    /** Returns a handle, or 0 (see [lastError]). */
    external fun createSession(
        assets: AssetManager, param: String, bin: String, inBlob: String, outBlob: String,
        scale: Int, crop: Int, prepad: Int, align: Int, tilePct: Int, residual: Boolean,
        engine: Int, allowFallback: Boolean, threads: Int, tile: Int, precision: Int, tta: Boolean,
    ): Long

    external fun sessionDevice(handle: Long): String
    external fun sessionFellBack(handle: Long): Boolean
    external fun releaseSession(handle: Long)
    external fun processBitmap(handle: Long, input: Bitmap, output: Bitmap, outScale: Int, listener: NativeListener?): Int
    external fun processBuffer(
        handle: Long, input: ByteBuffer, width: Int, height: Int, output: ByteBuffer, outScale: Int, listener: NativeListener?,
    ): Int

    /** Raw s16le PCM file to raw s16le PCM file at 48 kHz. See [AudioResult]. */
    external fun processAudio(
        inPath: String, outPath: String, inRate: Int, channels: Int, mode: Int, strength: Float, hum: Int,
        highpass: Boolean, clarity: Float, normalize: Boolean, targetLufs: Float, listener: NativeListener?,
    ): DoubleArray

    fun describe(rc: Int): String = when (rc) {
        RC_CANCELLED -> "Cancelled."
        RC_BAD_INPUT -> "The input couldn't be read."
        RC_MODEL_LOAD -> "Couldn't load the model."
        RC_INFERENCE -> "The network failed on both engines."
        RC_NO_GPU -> "No usable Vulkan GPU on this device. Switch the engine to Auto or CPU in Settings."
        RC_IO -> "Couldn't read or write a temporary file."
        else -> "Native error $rc."
    }
}

class EngineException(message: String) : Exception(message)

/** A loaded network on a backend. Close it (or let [SessionCache] own it). */
class SrSession internal constructor(val handle: Long, val key: String) : AutoCloseable {
    val device: String get() = NativeBridge.sessionDevice(handle)
    val fellBack: Boolean get() = NativeBridge.sessionFellBack(handle)
    override fun close() = NativeBridge.releaseSession(handle)
}

/**
 * Keeps the last network loaded: a batch of images, or an image after a video with the same model,
 * skips the (sometimes seconds long) model load and Vulkan pipeline compilation.
 */
class SessionCache(private val assets: AssetManager) {
    private var current: SrSession? = null

    @Synchronized
    fun acquire(net: NetSpec, s: AppSettings, tta: Boolean = s.tta): SrSession {
        val key = listOf(net, s.engine, s.cpuFallback, s.threads, s.tile, s.precision, tta).joinToString("|")
        current?.let { if (it.key == key) return it }
        release()
        val handle = NativeBridge.createSession(
            assets, net.param, net.bin, net.inBlob, net.outBlob, net.scale, net.crop, net.prepad, net.align,
            net.tilePct, net.residual, s.engine.id, s.cpuFallback, s.threads, s.tile, s.precision.id, tta,
        )
        if (handle == 0L) throw EngineException(NativeBridge.describe(NativeBridge.lastError()))
        return SrSession(handle, key).also { current = it }
    }

    @Synchronized
    fun release() {
        current?.close()
        current = null
    }
}
