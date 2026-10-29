package com.vytros.projects.sharpr.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class DeviceInfo(val gpu: String?, val gpuBudgetMb: Int, val cores: Int) {
    val summary: String
        get() = if (gpu != null) "$gpu · Vulkan" else "CPU · $cores performance cores"

    val detail: String
        get() = if (gpu != null) {
            String.format(Locale.US, "%s\n%.1f GB GPU memory budget · %d performance cores", gpu, gpuBudgetMb / 1024.0, cores)
        } else {
            "No Vulkan GPU available. Sharpr runs on the CPU ($cores performance cores)."
        }
}

object Device {
    @Volatile
    private var cached: DeviceInfo? = null

    /** First call creates the Vulkan instance, which can take a moment; later calls are free. */
    suspend fun info(): DeviceInfo = cached ?: withContext(Dispatchers.Default) {
        val info = runCatching {
            val raw = NativeBridge.gpuInfo()
            val cores = NativeBridge.cpuCores()
            if (raw.isEmpty()) DeviceInfo(null, 0, cores) else {
                val parts = raw.split("|")
                DeviceInfo(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0, cores)
            }
        }.getOrElse { DeviceInfo(null, 0, Runtime.getRuntime().availableProcessors()) }
        cached = info
        info
    }

    val known: DeviceInfo? get() = cached
}
