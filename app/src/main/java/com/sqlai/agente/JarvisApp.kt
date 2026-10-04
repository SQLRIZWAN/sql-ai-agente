package com.sqlai.agente

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.sqlai.agente.core.ai.ModelMemoryGovernor
import com.sqlai.agente.core.ai.ProviderRegistry
import com.sqlai.agente.core.linux.LinuxSubsystem
import com.sqlai.agente.core.monitor.SystemMonitor
import com.sqlai.agente.core.trading.TradingEngine
import com.sqlai.agente.core.vault.Vault
import com.sqlai.agente.data.db.AppDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application entry point for SQL AI AGENTE.
 *
 * Design rules enforced here:
 *  - No telemetry / analytics / crash-upload SDKs are ever initialised.
 *  - All long-lived singletons are process-scoped and torn down via [onTrimMemory]
 *    so heavy native model buffers are released before the system reclaims us.
 *  - Sensitive state only ever touches [Vault] (Android Keystore + SQLCipher).
 */
class JarvisApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.open(this) }
    val vault: Vault by lazy { Vault(this) }
    val providerRegistry: ProviderRegistry by lazy { ProviderRegistry(vault) }
    val modelGovernor: ModelMemoryGovernor by lazy { ModelMemoryGovernor() }
    val tradingEngine: TradingEngine by lazy { TradingEngine(vault) }
    val linuxSubsystem: LinuxSubsystem by lazy { LinuxSubsystem(this) }
    val systemMonitor: SystemMonitor by lazy { SystemMonitor(this) }

    override fun onCreate() {
        // MUST be first: captures any startup crash (Activity, Compose, DB,
        // native) to filesDir/last_crash.txt so the next launch can show it.
        installCrashReporter()
        super.onCreate()
        instance = this
        configureStrictMode()
        createNotificationChannels()
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(modelGovernor)
        }.onFailure {
            Log.w(TAG, "ProcessLifecycleOwner unavailable; governor not attached", it)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Release native GGUF buffers + decoded audio frames before LMK kills us.
        runCatching { modelGovernor.onHostTrim(level) }
        runCatching { linuxSubsystem.onHostTrim(level) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { modelGovernor.forceUnloadAll() }
    }

    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = buildString {
                    appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine(
                        "device=${Build.MANUFACTURER} ${Build.MODEL} " +
                            "sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()}"
                    )
                    appendLine("thread=${thread.name}")
                    appendLine(throwable.stackTraceToString())
                }
                File(filesDir, CRASH_FILE).writeText(report)
                Log.e(TAG, "uncaught crash captured", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun configureStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectNetwork().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build()
            )
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ORCHESTRATOR,
                    "Autonomous Orchestrator",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Foreground loop for local AI + trading tasks" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TRADING,
                    "Trading Engine",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Order lifecycle and risk alerts" }
            )
        }
    }

    companion object {
        const val CHANNEL_ORCHESTRATOR = "orchestrator"
        const val CHANNEL_TRADING = "trading"
        const val CRASH_FILE = "last_crash.txt"
        private const val TAG = "SqlAi"

        @Volatile
        lateinit var instance: JarvisApp
            private set
    }
}
