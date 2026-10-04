package com.sqlai.agente.core.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sqlai.agente.JarvisApp
import com.sqlai.agente.MainActivity
import com.sqlai.agente.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the autonomous loop.
 *
 * Keeps the trading decision tick + local model orchestration alive while the screen
 * is off, without being killed by the LMK. All heavy work is on [Dispatchers.IO];
 * the service itself holds no state (no leak surface).
 */
class OrchestratorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification("Orchestrator idle")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        observeState()
    }

    private fun observeState() {
        val app = application as JarvisApp
        scope.launch {
            app.tradingEngine.logs.collect { line ->
                // Re-post a compact status notification; no external logging.
                if (line.contains("OPEN") || line.contains("CLOSE")) {
                    notifyUpdate(line.take(120))
                }
            }
        }
        scope.launch {
            app.systemMonitor.stats.collect { s ->
                notifyUpdate(
                    "CPU ${s.cpuPercent}% · RAM ${s.ramPercent}% · " +
                        "bots ${s.activeBots} · ${s.loadedModel ?: "no model"}"
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        (application as JarvisApp).tradingEngine.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, OrchestratorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_ORCHESTRATOR)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("SQL AI AGENTE")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun notifyUpdate(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "com.sqlai.agente.STOP"
        private const val NOTIFICATION_ID = 4210
    }
}
