package com.sqlai.agente.ui.nativebridge

/**
 * Kotlin façade over `libsqlai_core.so`.
 *
 * Token streaming uses a single process-global listener (set from the UI layer) so the
 * C++ side never caches stale JNI refs across configuration changes.
 */
object NativeEngine {

    interface TokenListener {
        fun onToken(token: String)
    }

    @Volatile
    private var listener: TokenListener? = null

    var modelHandle: Long = 0
        private set

    val isAvailable: Boolean
        get() = runCatching { nativeHasLlama() }.getOrDefault(false)

    val hasWhisper: Boolean
        get() = runCatching { nativeHasWhisper() }.getOrDefault(false)

    val isLoaded: Boolean get() = modelHandle != 0L

    /** Loads a GGUF model from app-private storage. Returns handle, 0 on failure. */
    fun load(path: String, gpuLayers: Int = 0, threads: Int = 0): Long {
        unload()
        modelHandle = nativeLoadModel(path, gpuLayers, threads)
        return modelHandle
    }

    /** Frees native weights AND drops the handle so RSS returns to baseline. */
    fun unload() {
        if (modelHandle != 0L) {
            nativeUnloadModel(modelHandle)
            modelHandle = 0L
        }
    }

    fun setListener(l: TokenListener?) {
        listener = l
        nativeSetListener(l)
    }

    fun generate(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): String {
        if (modelHandle == 0L) return ""
        return nativeGenerate(modelHandle, prompt, maxTokens, temperature)
    }

    fun cancel() {
        if (modelHandle != 0L) nativeCancel(modelHandle)
    }

    fun residentBytes(): Long {
        if (modelHandle == 0L) return 0L
        return nativeResidentBytes(modelHandle)
    }

    fun transcribe(wavPath: String, modelPath: String): String =
        runCatching { nativeTranscribe(wavPath, modelPath) }.getOrDefault("")

    // ---- JNI ----
    private external fun nativeInit(): Boolean
    private external fun nativeHasLlama(): Boolean
    private external fun nativeHasWhisper(): Boolean
    private external fun nativeLoadModel(path: String, ngl: Int, threads: Int): Long
    private external fun nativeUnloadModel(handle: Long)
    private external fun nativeSetListener(l: TokenListener?)
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temp: Float): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeResidentBytes(handle: Long): Long
    private external fun nativeTranscribe(wavPath: String, modelPath: String): String

    init {
        runCatching { System.loadLibrary("sqlai_core") }
    }
}
