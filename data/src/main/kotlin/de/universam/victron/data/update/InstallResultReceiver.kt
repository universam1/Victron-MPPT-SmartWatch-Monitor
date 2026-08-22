package de.universam.victron.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import de.universam.victron.data.VictronData
import java.io.File

/**
 * Where a committed install session reports back.
 *
 * Three outcomes matter:
 * * [PackageInstaller.STATUS_PENDING_USER_ACTION] — the platform wants a confirmation dialog
 *   (every device below API 31, and any device that declines an unattended self-update). The
 *   intent it hands us *is* that dialog, so we start it.
 * * [PackageInstaller.STATUS_SUCCESS] — the APK is installed; drop the staged file so the cache
 *   does not keep a copy of every version ever shipped.
 * * anything else — surface the platform's message in the UI instead of failing silently.
 */
public class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val updates = VictronData.updates(context)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    updates.onInstallFailed("Missing confirmation intent")
                    return
                }
                // Started from a receiver, so it needs its own task.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { updates.onInstallFailed(it.message ?: "Cannot show installer") }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installed")
                apkPath?.let { File(it).delete() }
                updates.onInstallSucceeded()
            }

            else -> {
                Log.w(TAG, "Install failed with status $status: $message")
                updates.onInstallFailed(message ?: "Install failed ($status)")
            }
        }
    }

    public companion object {
        private const val TAG = "InstallResult"
        internal const val ACTION_INSTALL_STATUS: String = "de.universam.victron.INSTALL_STATUS"
        internal const val EXTRA_APK_PATH: String = "apk_path"
    }
}
