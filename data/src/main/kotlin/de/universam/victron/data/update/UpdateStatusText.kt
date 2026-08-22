package de.universam.victron.data.update

import android.content.Context
import de.universam.victron.data.R

/**
 * One label per [UpdateState], for both apps.
 *
 * Lives here rather than in each app for the same reason `Formatting` does: the watch and the
 * phone must not describe the same update differently. The strings are in `:data`'s own
 * `res/values`, so there is also only one translation to keep in step.
 */
public fun updateStatusText(context: Context, state: UpdateState): String =
    when (state) {
        UpdateState.Idle -> context.getString(R.string.update_check)
        UpdateState.Checking -> context.getString(R.string.update_checking)
        is UpdateState.UpToDate -> context.getString(R.string.update_current, state.versionName)
        is UpdateState.Available -> context.getString(R.string.update_available, state.update.versionName)
        is UpdateState.Downloading -> if (state.fraction >= 0f) {
            context.getString(
                R.string.update_downloading_percent,
                state.update.versionName,
                (state.fraction * 100).toInt().coerceIn(0, 100),
            )
        } else {
            context.getString(R.string.update_downloading, state.update.versionName)
        }
        is UpdateState.Ready -> context.getString(R.string.update_ready, state.update.versionName)
        is UpdateState.Installing -> context.getString(R.string.update_installing, state.update.versionName)
        is UpdateState.Failed -> context.getString(R.string.update_failed, state.reason)
    }

/** True while the updater is busy and a second tap would do nothing useful. */
public val UpdateState.isBusy: Boolean
    get() = this is UpdateState.Checking || this is UpdateState.Downloading ||
        this is UpdateState.Installing
