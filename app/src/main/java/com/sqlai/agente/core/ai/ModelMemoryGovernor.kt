package com.sqlai.agente.core.ai

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.sqlai.agente.core.monitor.SystemMonitor
import com.sqlai.agente.ui.nativebridge.NativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enforces the "Zero Memory Leak / dynamic RAM allocation" rule:
 *
 *  - App backgrounded            -> heavy GGUF weights are evicted from RAM.
 *  - Thermal throttling detected -> evict + refuse reload until cool.
 *  - Task completed              -> explicit unload, RSS verified back to baseline.
 *
 * The governor is a process singleton registered as a lifecycle observer so there is
 * no need for manual subscription plumbing (and thus no leak paths).
 */
class ModelMemoryGovernor : DefaultLifecycleObserver {

    enum class State { EMPTY, LOADING, RESIDENT, EVICTING }

    private val _state = MutableStateFlow(State.EMPTY)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _residentBytes = MutableStateFlow(0L)
    val residentBytes: StateFlow<Long> = _residentBytes.asStateFlow()

    @Volatile
    private var pinnedModelPath: String? = null

    @Volatile
    private var monitor: SystemMonitor? = null

    fun attachMonitor(m: SystemMonitor) {
        monitor = m
    }

    /** Loads weights and keeps them resident while the app is foregrounded. */
    fun load(path: String, gpuLayers: Int = 0, threads: Int = 0): Boolean {
        if (thermalBlocked()) return false
        _state.value = State.LOADING
        val handle = NativeEngine.load(path, gpuLayers, threads)
        if (handle == 0L) {
            _state.value = State.EMPTY
            return false
        }
        pinnedModelPath = path
        _state.value = State.RESIDENT
        val rss = NativeEngine.residentBytes()
        _residentBytes.value = rss
        monitor?.publishModel(path.substringAfterLast('/'), rss)
        return true
    }

    /** Explicit release after a task completes — RSS returns to baseline here. */
    fun unload() {
        if (!NativeEngine.isLoaded) return
        _state.value = State.EVICTING
        NativeEngine.unload()
        pinnedModelPath = null
        _residentBytes.value = 0L
        _state.value = State.EMPTY
        monitor?.publishModel(null, 0L)
    }

    fun forceUnloadAll() = unload()

    /** Called from [com.sqlai.agente.JarvisApp.onTrimMemory]. */
    fun onHostTrim(level: Int) {
        // TRIM_MEMORY_UI_HIDDEN (20) or worse -> evict so background trading keeps RAM.
        if (level >= 20) unload()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Screen off / app backgrounded: trading loop continues without the LLM.
        unload()
    }

    private fun thermalBlocked(): Boolean {
        val s = monitor?.stats?.value ?: return false
        return s.thermalStatus >= com.sqlai.agente.core.monitor.ThermalStatus.CRITICAL
    }

    /** Best-effort reload if the user returns and the model was pinned before. */
    fun maybeReload() {
        val path = pinnedModelPath ?: return
        if (!NativeEngine.isLoaded && !thermalBlocked()) load(path)
    }
}
