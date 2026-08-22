package de.universam.victron.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** What the updater is doing, mirrored into the UI of both apps. */
public sealed interface UpdateState {
    public data object Idle : UpdateState
    public data object Checking : UpdateState

    /** Checked, and this build is the newest release. */
    public data class UpToDate(val versionName: String) : UpdateState

    /** A newer release exists but has not been downloaded yet. */
    public data class Available(val update: AvailableUpdate) : UpdateState

    /** [fraction] is -1 while the size is unknown. */
    public data class Downloading(val update: AvailableUpdate, val fraction: Float) : UpdateState

    /** Downloaded and verified, waiting to be installed. */
    public data class Ready(val update: AvailableUpdate) : UpdateState

    /** Handed to the platform installer; a system dialog may be on screen. */
    public data class Installing(val update: AvailableUpdate) : UpdateState

    public data class Failed(val reason: String) : UpdateState
}

/**
 * Keeps the app up to date from its own GitHub releases, because it is not in any store.
 *
 * The flow is deliberately split in two halves:
 * * **staging** — check, download, verify. Runs in the background ([UpdateWorker]) and needs no
 *   interaction, so a test device has the APK ready before anybody looks at it.
 * * **installing** — hand the staged APK to [ApkInstaller]. On API 31+ this can complete
 *   unattended for a same-signature self update; otherwise the platform shows its dialog.
 *
 * The staged APK in `cacheDir/updates` is the only bookkeeping: its file name carries the version
 * ([ReleaseCatalog.versionCodeOfAsset]), so a cleared cache cannot leave a phantom
 * "update ready" behind in DataStore, and a stale file is recognised as stale.
 */
public class UpdateManager internal constructor(
    private val context: Context,
    private val source: GitHubUpdateSource = GitHubUpdateSource(),
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    public val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** One check/download at a time — the worker and a button press must not race. */
    private val gate = Mutex()

    /** `-wear-` or `-phone-`: which of the release's two APKs this device can install. */
    public val variant: UpdateVariant =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            UpdateVariant.Wear
        } else {
            UpdateVariant.Phone
        }

    public val installedVersionName: String
        get() = packageInfo()?.versionName ?: "?"

    /** `longVersionCode` needs API 28; the lowest `minSdk` in this project is 29. */
    public val installedVersionCode: Long
        get() = packageInfo()?.longVersionCode ?: 0L

    /**
     * Asks GitHub for the newest release, downloads and verifies it when it is newer than what is
     * installed, and leaves it staged. Safe to call from a worker: it never touches the installer.
     *
     * @return the state it settled on, so the caller can log a single line.
     */
    public suspend fun checkAndStage(): UpdateState = gate.withLock {
        val installed = installedVersionCode
        _state.value = UpdateState.Checking

        val update = try {
            ReleaseCatalog.newestUpdate(source.releases(), variant, installed)
        } catch (error: Exception) {
            Log.w(TAG, "Update check failed", error)
            // A staged APK from an earlier run is still installable with no network at all.
            val staged = stagedUpdate()
            _state.value = if (staged != null) {
                UpdateState.Ready(staged.update)
            } else {
                UpdateState.Failed(error.message ?: "Update check failed")
            }
            return@withLock _state.value
        }

        val staged = stagedUpdate()
        _state.value = when {
            // Nothing newer published; a leftover download may still be waiting to be installed.
            update == null -> staged?.let { UpdateState.Ready(it.update) }
                ?: UpdateState.UpToDate(installedVersionName)

            // Already downloaded — and not superseded by an even newer release since.
            staged != null && staged.update.versionCode >= update.versionCode ->
                UpdateState.Ready(staged.update)

            else -> {
                _state.value = UpdateState.Available(update)
                stage(update)
            }
        }
        _state.value
    }

    /**
     * Downloads [update] into the update cache and verifies its SHA-256 against the release's
     * `SHA256SUMS.txt`.
     *
     * A mismatch deletes the file and fails: it means the download was corrupted (a forged APK
     * cannot pass the platform's signature check anyway). A release *without* a checksum list
     * still installs — the signature check is the security boundary, the checksum only catches a
     * broken transfer earlier and with a clearer message.
     */
    private suspend fun stage(update: AvailableUpdate): UpdateState {
        val expected = try {
            checksumFor(update)
        } catch (error: Exception) {
            Log.i(TAG, "No checksum list for ${update.versionName}: ${error.message}")
            null
        }

        val target = File(updateDir(), update.assetName)
        var lastPercent = -1
        return try {
            val actual = source.download(update.asset.downloadUrl, target) { bytes, total ->
                // One emission per whole percent: the label cannot show more, and every emission
                // recomposes a screen that is usually open while this runs.
                val fraction = if (total > 0) bytes.toFloat() / total else -1f
                val percent = (fraction * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    _state.value = UpdateState.Downloading(update, fraction)
                }
            }
            if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                target.delete()
                UpdateState.Failed("Checksum mismatch for ${update.assetName}")
            } else {
                prune(keep = target)
                UpdateState.Ready(update)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Download failed for ${update.assetName}", error)
            target.delete()
            UpdateState.Failed(error.message ?: "Download failed")
        }
    }

    private suspend fun checksumFor(update: AvailableUpdate): String? {
        val sums = update.asset.downloadUrl.substringBeforeLast('/') + "/" + ReleaseCatalog.SUMS_ASSET
        return ReleaseCatalog.parseSha256Sums(source.text(sums))[update.assetName]
    }

    /** A verified APK newer than the installed build, if a previous run left one behind. */
    public fun stagedUpdate(): StagedUpdate? {
        val installed = installedVersionCode
        return updateDir().listFiles { file -> file.isFile && file.name.endsWith(".apk") }
            ?.mapNotNull { file ->
                val code = ReleaseCatalog.versionCodeOfAsset(file.name) ?: return@mapNotNull null
                if (code <= installed || file.length() == 0L) return@mapNotNull null
                StagedUpdate(
                    file = file,
                    update = AvailableUpdate(
                        versionName = ReleaseCatalog.versionOfAsset(file.name) ?: "?",
                        versionCode = code,
                        asset = ReleaseAsset(file.name, "", file.length()),
                    ),
                )
            }
            ?.maxByOrNull { it.update.versionCode }
    }

    /**
     * Installs whatever is staged. Returns false when nothing is staged — which is the normal case
     * and not an error, so the state is left alone.
     */
    public fun installStaged(): Boolean {
        val staged = stagedUpdate() ?: return false
        _state.value = UpdateState.Installing(staged.update)
        if (!ApkInstaller.install(context, staged.file)) {
            _state.value = UpdateState.Failed("Could not start the installer")
            return false
        }
        return true
    }

    /** Check, download and install in one go — what the "update now" button does. */
    public suspend fun updateNow(): Boolean {
        val state = checkAndStage()
        return if (state is UpdateState.Ready) installStaged() else false
    }

    internal fun onInstallSucceeded() {
        _state.value = UpdateState.UpToDate(installedVersionName)
    }

    internal fun onInstallFailed(reason: String) {
        _state.value = UpdateState.Failed(reason)
    }

    /** Old downloads are dead weight on a watch; keep only the newest one. */
    private fun prune(keep: File?) {
        updateDir().listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    private fun updateDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun packageInfo() = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    /** A downloaded, verified APK together with the release it came from. */
    public data class StagedUpdate(val file: File, val update: AvailableUpdate)

    private companion object {
        private const val TAG = "UpdateManager"
    }
}
