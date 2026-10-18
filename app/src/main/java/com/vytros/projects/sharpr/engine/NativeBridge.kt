package com.vytros.projects.sharpr.engine

import android.content.res.AssetManager
import android.graphics.Bitmap

/** Callbacks invoked from the native tile loop (on the calling thread). */
interface NativeListener {
    fun onProgress(done: Int, total: Int)
    fun onBackend(name: String, fallback: Boolean)
}

/** Thin JNI surface over ncnn. All heavy lifting lives in sharpr_native.cpp. */
object NativeBridge {
    const val RC_OK = 0
    const val RC_CANCELLED = 1
    const val RC_BAD_BITMAP = -1
    const val RC_MODEL_LOAD = -2
    const val RC_INFERENCE = -3
    const val RC_NO_GPU = -4

    init {
        System.loadLibrary("sharpr_native")
    }

    /** "Device name|heapBudgetMB", or empty when no Vulkan device is usable. */
    external fun gpuInfo(): String
    external fun cpuCores(): Int
    external fun cancel()

    external fun upscale(
        assets: AssetManager,
        modelBase: String,
        modelScale: Int,
        outScale: Int,
        input: Bitmap,
        output: Bitmap,
        engine: Int,
        allowFallback: Boolean,
        threads: Int,
        tile: Int,
        fp16: Boolean,
        listener: NativeListener,
    ): Int
}
