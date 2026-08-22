package de.universam.victron.data.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.universam.victron.data.VictronData
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Checks for a new release and *stages* it — download and checksum only, no installer.
 *
 * Staging in the background is what makes a test fleet update itself: by the time anybody opens
 * the app the APK is already on the device, so the install is one commit away (and on API 31+ the
 * worker's own [UpdateManager.installStaged] can finish it without a dialog).
 */
public class UpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val enabled = VictronData.repository(applicationContext).config.first().autoUpdateEnabled
        if (!enabled) {
            Log.i(TAG, "Auto update is off, nothing to do")
            return Result.success()
        }

        val updates = VictronData.updates(applicationContext)
        val state = updates.checkAndStage()
        Log.i(TAG, "Update check settled on $state")

        // Only API 31+ can install a self update without a dialog. Below that the platform would
        // ask for confirmation, and a confirmation dialog cannot be started from the background —
        // so the staged APK waits for the next app start instead of failing here.
        if (state is UpdateState.Ready && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            updates.installStaged()
        }

        return if (state is UpdateState.Failed) Result.retry() else Result.success()
    }

    public companion object {
        private const val TAG = "UpdateWorker"
        internal const val PERIODIC_WORK: String = "victron-update-periodic"
    }
}

/** Enqueues the update checks. Kept beside [de.universam.victron.data.ScanScheduler] in spirit. */
public object UpdateScheduler {

    /**
     * Turns the periodic check on or off. Six hours, not fifteen minutes: a release happens a few
     * times a week at best, and every run costs a radio wake plus an APK download when it hits.
     *
     * [NetworkType.CONNECTED] rather than `UNMETERED`, because a watch usually reaches the network
     * through its phone and would otherwise never qualify — the traffic is one JSON document per
     * run and one APK per actual release.
     */
    public fun setPeriodicCheckEnabled(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UpdateWorker.PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            UpdateWorker.PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
