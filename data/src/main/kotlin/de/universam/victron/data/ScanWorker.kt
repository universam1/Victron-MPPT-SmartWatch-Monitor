package de.universam.victron.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Runs one bounded BLE scan off the UI. Used for the "tile was opened" refresh and for the
 * optional periodic background refresh.
 *
 * WorkManager (rather than a foreground service) on purpose: expedited work may be started from
 * the background, which a tile service cannot do with a foreground service on Android 12+.
 */
public class ScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = VictronData.repository(applicationContext)
        val unavailable = repository.canScan()
        if (unavailable != null) {
            Log.i(TAG, "Skipping scan: $unavailable")
            return Result.success()
        }

        val configured = repository.config.first()
        val seconds = inputData.getInt(KEY_SECONDS, configured.scanWindowSeconds)
        val aggressiveness = runCatching {
            ScanAggressiveness.valueOf(
                inputData.getString(KEY_AGGRESSIVENESS) ?: ScanAggressiveness.LowLatency.name,
            )
        }.getOrDefault(ScanAggressiveness.LowLatency)

        val received = runCatching {
            repository.scanOnce(TimeUnit.SECONDS.toMillis(seconds.toLong()), aggressiveness)
        }.onFailure { Log.w(TAG, "Scan failed", it) }.getOrDefault(0)

        Log.d(TAG, "Scan window of ${seconds}s produced $received advertisements")
        VictronData.onScanFinished?.invoke(applicationContext)
        return Result.success()
    }

    public companion object {
        private const val TAG = "ScanWorker"
        internal const val KEY_SECONDS = "seconds"
        internal const val KEY_AGGRESSIVENESS = "aggressiveness"

        internal const val ONE_SHOT_WORK = "victron-scan-now"
        internal const val PERIODIC_WORK = "victron-scan-periodic"
    }
}

/** Entry points for triggering scans from a tile, a complication or a settings toggle. */
public object ScanScheduler {

    /** Fire-and-forget refresh, e.g. when the user just looked at the tile. */
    public fun requestScanNow(
        context: Context,
        seconds: Int? = null,
        aggressiveness: ScanAggressiveness = ScanAggressiveness.LowLatency,
    ) {
        val inputData = if (seconds != null) {
            workDataOf(
                ScanWorker.KEY_SECONDS to seconds,
                ScanWorker.KEY_AGGRESSIVENESS to aggressiveness.name,
            )
        } else {
            workDataOf(ScanWorker.KEY_AGGRESSIVENESS to aggressiveness.name)
        }

        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ScanWorker.ONE_SHOT_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Enables or cancels the periodic refresh. 15 minutes is WorkManager's minimum period; the
     * platform may still delay it while the watch is idle, which is why every surface shows how
     * old its values are.
     */
    public fun setPeriodicScanEnabled(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(ScanWorker.PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(ScanWorker.KEY_AGGRESSIVENESS to ScanAggressiveness.Balanced.name))
            .setConstraints(Constraints.NONE)
            .build()
        workManager.enqueueUniquePeriodicWork(
            ScanWorker.PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
