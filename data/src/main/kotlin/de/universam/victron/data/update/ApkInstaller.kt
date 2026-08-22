package de.universam.victron.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Hands a downloaded APK to the platform's [PackageInstaller].
 *
 * There is no way around a signature match: the system installs an update only when it is signed
 * with the same key as the installed app, which is why both modules share the signing block. That
 * check — not our SHA-256 — is what makes this safe against a tampered download.
 *
 * On API 31+ a *self* update may run unattended ([PackageInstaller.SessionParams
 * .setRequireUserAction] with `USER_ACTION_NOT_REQUIRED`), which is what makes a test fleet update
 * itself without anybody tapping. When the platform declines, the session reports
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] and [InstallResultReceiver] shows the system
 * dialog instead — the same code path serves both.
 */
internal object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /**
     * Starts an install session for [apk]. Returns false when the session could not even be
     * created; anything after the commit is asynchronous and lands in [InstallResultReceiver].
     */
    fun install(context: Context, apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // A self-update with a matching signature: ask for no dialog at all.
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                }
            }

        var sessionId = -1
        return try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(WRITE_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(statusSender(context, sessionId, apk))
            }
            Log.i(TAG, "Committed install session $sessionId for ${apk.name}")
            true
        } catch (error: Exception) {
            Log.w(TAG, "Install session failed for ${apk.name}", error)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            false
        }
    }

    private fun statusSender(context: Context, sessionId: Int, apk: File): android.content.IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = InstallResultReceiver.ACTION_INSTALL_STATUS
            setPackage(context.packageName)
            putExtra(InstallResultReceiver.EXTRA_APK_PATH, apk.absolutePath)
        }
        // FLAG_MUTABLE: the platform fills the status extras into this intent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private const val WRITE_NAME = "victron-update.apk"
}
