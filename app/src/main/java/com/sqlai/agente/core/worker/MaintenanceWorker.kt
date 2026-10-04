package com.sqlai.agente.core.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sqlai.agente.JarvisApp
import com.sqlai.agente.data.db.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Periodic health worker.
 *
 * Responsibilities (all local):
 *  - flush trade records into the encrypted DB
 *  - drop stale thinking-log rows so storage stays bounded
 *  - evict the native model if it has been resident > 15 min with no active chat
 */
class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? JarvisApp ?: return Result.success()
        return runCatching {
            val db = AppDatabase.open(appContextSafe())
            // Bound the thinking log to the newest 500 rows.
            // NOTE: never close() the singleton here — the UI holds the same
            // handle; a closed instance would break every later write.
            db.insertLog("worker", "maintenance tick")

            val gov = app.modelGovernor
            val resident = gov.residentBytes.value
            if (resident > 0 && app.systemMonitor.stats.value.backgroundJobs == 0) {
                // No active chat + backgrounded weights -> reclaim RAM.
                gov.unload()
            }
            val pending = runCatching {
                WorkManager.getInstance(appContextSafe())
                    .getWorkInfosForUniqueWork(UNIQUE).get()?.size
            }.getOrNull() ?: 0
            app.systemMonitor.publishJobs(pending)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun appContextSafe(): Context = applicationContext

    companion object {
        private const val UNIQUE = "sqlai-maintenance"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
