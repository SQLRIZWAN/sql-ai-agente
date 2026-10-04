package com.sqlai.agente.core.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

data class SystemStats(
    val cpuPercent: Int = 0,
    val usedRamBytes: Long = 0L,
    val totalRamBytes: Long = 0L,
    val appPssBytes: Long = 0L,
    val threadCount: Int = 0,
    val thermalStatus: Int = ThermalStatus.NONE,
    val loadedModel: String? = null,
    val modelRssBytes: Long = 0L,
    val activeBots: Int = 0,
    val backgroundJobs: Int = 0,
) {
    val ramPercent: Int
        get() = if (totalRamBytes <= 0) 0
        else ((usedRamBytes.toDouble() / totalRamBytes) * 100).toInt().coerceIn(0, 100)
}

/**
 * Samples CPU / RAM / thermal state at 1 Hz off the main thread.
 * Native sampling happens in `sysinfo.cpp`; Java side only publishes flows.
 */
class SystemMonitor(context: Context) {

    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sampleJob: Job? = null

    private val _stats = MutableStateFlow(SystemStats())
    val stats: StateFlow<SystemStats> = _stats.asStateFlow()

    private external fun nativeCpuPercent(): Int
    private external fun nativeTotalMem(): Long
    private external fun nativeAvailMem(): Long
    private external fun nativePssBytes(): Long
    private external fun nativeThreadCount(): Int

    init {
        runCatching { System.loadLibrary("sqlai_core") }
        startSampling()
    }

    private fun startSampling() {
        sampleJob = scope.launch {
            // First call seeds the CPU delta window.
            nativeCpuPercent()
            while (isActive) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)

                // PowerManager thermal status: 0=NONE .. 6=SHUTDOWN (API 29+, minSdk 29).
                val thermalStatus = runCatching {
                    pm.currentThermalStatus
                }.getOrDefault(0)
                val thermal = thermalStatus >= 4 // THERMAL_STATUS_CRITICAL

                _stats.value = SystemStats(
                    cpuPercent = nativeCpuPercent(),
                    usedRamBytes = memInfo.totalMem - memInfo.availMem,
                    totalRamBytes = memInfo.totalMem,
                    appPssBytes = nativePssBytes(),
                    threadCount = nativeThreadCount(),
                    thermalStatus = if (thermal) ThermalStatus.CRITICAL else ThermalStatus.NONE,
                    loadedModel = _stats.value.loadedModel,
                    modelRssBytes = _stats.value.modelRssBytes,
                    activeBots = _stats.value.activeBots,
                    backgroundJobs = _stats.value.backgroundJobs,
                )
                delay(1_000)
            }
        }
    }

    fun publishModel(name: String?, rss: Long) {
        _stats.value = _stats.value.copy(loadedModel = name, modelRssBytes = rss)
    }

    fun publishBots(active: Int) {
        _stats.value = _stats.value.copy(activeBots = active)
    }

    fun publishJobs(count: Int) {
        _stats.value = _stats.value.copy(backgroundJobs = count)
    }

    fun shutdown() {
        // Halt the sampler so the process doesn't leak a coroutine on teardown.
        sampleJob?.cancel()
        sampleJob = null
    }
}

object ThermalStatus {
    const val NONE = 0
    const val SEVERE = 1
    const val CRITICAL = 2
}
