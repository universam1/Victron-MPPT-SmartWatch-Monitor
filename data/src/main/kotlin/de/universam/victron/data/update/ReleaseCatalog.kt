package de.universam.victron.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything about *which* release is an update, kept free of Android and of I/O so it can be
 * unit tested. The rules here mirror `.github/workflows/release.yml` — if the version derivation
 * there changes, this has to change with it or a device will never see the release as newer.
 */

/** Which of a release's two APKs belongs to this device. */
public enum class UpdateVariant(public val assetInfix: String) {
    /** `victron-monitor-wear-1.2.3.apk` */
    Wear("-wear-"),

    /** `victron-monitor-phone-1.2.3.apk` */
    Phone("-phone-"),
}

@Serializable
public data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

/** The subset of GitHub's release JSON this app cares about. */
@Serializable
public data class Release(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

/** A release that is newer than what is installed, together with the APK to fetch. */
public data class AvailableUpdate(
    val versionName: String,
    val versionCode: Long,
    val asset: ReleaseAsset,
) {
    public val assetName: String get() = asset.name
}

public object ReleaseCatalog {

    /** The checksum list the release workflow uploads next to the APKs. */
    public const val SUMS_ASSET: String = "SHA256SUMS.txt"

    /**
     * `v1.2.3` -> `1.2.3`. A prerelease suffix stays in the *name* (it is what the user reads)
     * but not in the code — see [versionCode].
     */
    public fun versionName(tag: String): String = tag.trim().removePrefix("v")

    /**
     * `v1.2.3` -> `10203`, the same arithmetic the release workflow feeds into `versionCode`.
     *
     * A prerelease keeps the code of its final version (`v1.1.0-beta1` == `v1.1.0`), exactly as in
     * CI. That is why prereleases are skipped as update candidates: their code cannot outrank the
     * final release, so offering one would either be a no-op or a downgrade.
     */
    public fun versionCode(tag: String): Long {
        val core = versionName(tag).substringBefore('-')
        val parts = core.split('.')
        fun part(index: Int): Long = parts.getOrNull(index)?.trim()?.toLongOrNull() ?: 0L
        val code = part(0) * 10_000 + part(1) * 100 + part(2)
        return if (code > 0) code else 1L
    }

    /** The APK of this release built for [variant], if it has one. */
    public fun assetFor(release: Release, variant: UpdateVariant): ReleaseAsset? =
        release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.name.contains(variant.assetInfix, ignoreCase = true)
        }

    /**
     * Picks the highest-numbered published release that is newer than [installedVersionCode] and
     * actually carries an APK for [variant].
     *
     * Drafts and prereleases are ignored, and so is an equal version code: reinstalling the same
     * build would only cost the tester a system dialog. The comparison is by code, not by list
     * order, because releases can be published out of order.
     */
    public fun newestUpdate(
        releases: List<Release>,
        variant: UpdateVariant,
        installedVersionCode: Long,
    ): AvailableUpdate? = releases.asSequence()
        .filterNot { it.draft || it.prerelease }
        .mapNotNull { release ->
            val asset = assetFor(release, variant) ?: return@mapNotNull null
            AvailableUpdate(
                versionName = versionName(release.tag),
                versionCode = versionCode(release.tag),
                asset = asset,
            )
        }
        .filter { it.versionCode > installedVersionCode }
        .maxByOrNull { it.versionCode }

    /**
     * Parses `sha256sum` output: one `<64 hex>  <file name>` line per file. Anything that is not
     * such a line is skipped rather than failing the whole list — the release notes generator may
     * grow a header one day.
     */
    public fun parseSha256Sums(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val tokens = line.trim().split(WHITESPACE, limit = 2)
            if (tokens.size != 2) return@mapNotNull null
            val hex = tokens[0].lowercase()
            if (!hex.matches(SHA256_HEX)) return@mapNotNull null
            // `sha256sum --binary` prefixes the name with '*'.
            hex to tokens[1].trim().removePrefix("*")
        }
        .associate { (hex, name) -> name to hex }

    /**
     * The version an already-downloaded APK carries, read back from its file name
     * (`victron-monitor-phone-1.2.3.apk` -> 10203). The staged file is its own bookkeeping, so a
     * cleared cache cannot leave a stale "update ready" flag behind in DataStore.
     */
    public fun versionOfAsset(assetName: String): String? =
        ASSET_VERSION.find(assetName)?.groupValues?.get(1)

    /** [versionOfAsset] as a comparable code, or null when the name carries no version. */
    public fun versionCodeOfAsset(assetName: String): Long? =
        versionOfAsset(assetName)?.let { versionCode(it) }

    private val WHITESPACE = Regex("\\s+")
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /** `victron-monitor-wear-1.2.3.apk` -> `1.2.3`, prerelease suffix included. */
    private val ASSET_VERSION = Regex("-(\\d+(?:\\.\\d+)*(?:-[0-9A-Za-z.]+)?)\\.apk$", RegexOption.IGNORE_CASE)
}
