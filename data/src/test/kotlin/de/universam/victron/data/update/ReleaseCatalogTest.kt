package de.universam.victron.data.update

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseCatalogTest {

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<String> = listOf(
            "victron-monitor-wear-${tag.removePrefix("v")}.apk",
            "victron-monitor-phone-${tag.removePrefix("v")}.apk",
            ReleaseCatalog.SUMS_ASSET,
        ),
    ) = Release(
        tag = tag,
        draft = draft,
        prerelease = prerelease,
        assets = assets.map { ReleaseAsset(it, "https://example.invalid/$it", 1024) },
    )

    // The release workflow computes major*10000 + minor*100 + patch. A device that derived the
    // code differently would either never update or offer a downgrade.
    @Test
    fun `version code matches the release workflow`() {
        assertEquals(10203, ReleaseCatalog.versionCode("v1.2.3"))
        assertEquals(10000, ReleaseCatalog.versionCode("v1.0.0"))
        assertEquals(120000, ReleaseCatalog.versionCode("v12.0.0"))
        assertEquals(1, ReleaseCatalog.versionCode("v0.0.0"))
        // Missing components count as zero, exactly like the shell's ${minor:-0}.
        assertEquals(20000, ReleaseCatalog.versionCode("v2"))
        assertEquals(20100, ReleaseCatalog.versionCode("v2.1"))
    }

    @Test
    fun `prerelease keeps the code of its final version`() {
        assertEquals(
            ReleaseCatalog.versionCode("v1.1.0"),
            ReleaseCatalog.versionCode("v1.1.0-beta1"),
        )
        assertEquals("1.1.0-beta1", ReleaseCatalog.versionName("v1.1.0-beta1"))
    }

    @Test
    fun `the wear asset is never offered to a phone`() {
        val r = release("v1.2.3")
        assertEquals(
            "victron-monitor-phone-1.2.3.apk",
            ReleaseCatalog.assetFor(r, UpdateVariant.Phone)?.name,
        )
        assertEquals(
            "victron-monitor-wear-1.2.3.apk",
            ReleaseCatalog.assetFor(r, UpdateVariant.Wear)?.name,
        )
    }

    @Test
    fun `a release without an apk for this variant is not a candidate`() {
        val phoneOnly = release("v1.3.0", assets = listOf("victron-monitor-phone-1.3.0.apk"))
        assertNull(ReleaseCatalog.assetFor(phoneOnly, UpdateVariant.Wear))
        assertNull(
            ReleaseCatalog.newestUpdate(listOf(phoneOnly), UpdateVariant.Wear, installedVersionCode = 10000),
        )
    }

    @Test
    fun `the highest version wins regardless of list order`() {
        val releases = listOf(release("v1.2.0"), release("v1.10.0"), release("v1.3.0"))
        val update = ReleaseCatalog.newestUpdate(releases, UpdateVariant.Phone, 10000)
        assertEquals("1.10.0", update?.versionName)
        assertEquals(11000, update?.versionCode)
    }

    @Test
    fun `the installed version and older ones are not updates`() {
        val releases = listOf(release("v1.2.3"), release("v1.2.0"))
        assertNull(ReleaseCatalog.newestUpdate(releases, UpdateVariant.Phone, 10203))
        assertNull(ReleaseCatalog.newestUpdate(releases, UpdateVariant.Phone, 20000))
        assertNotNull(ReleaseCatalog.newestUpdate(releases, UpdateVariant.Phone, 10202))
    }

    @Test
    fun `drafts and prereleases are skipped`() {
        val releases = listOf(
            release("v2.0.0", draft = true),
            release("v1.9.0", prerelease = true),
            release("v1.5.0"),
        )
        assertEquals("1.5.0", ReleaseCatalog.newestUpdate(releases, UpdateVariant.Phone, 10000)?.versionName)
    }

    @Test
    fun `checksums are parsed from sha256sum output`() {
        val hex = "a".repeat(64)
        val other = "b".repeat(64)
        val sums = ReleaseCatalog.parseSha256Sums(
            """
            $hex  victron-monitor-wear-1.2.3.apk
            ${other.uppercase()} *victron-monitor-phone-1.2.3.apk
            not a checksum line
            """.trimIndent(),
        )
        assertEquals(hex, sums["victron-monitor-wear-1.2.3.apk"])
        assertEquals(other, sums["victron-monitor-phone-1.2.3.apk"])
        assertEquals(2, sums.size)
    }

    @Test
    fun `a staged apk carries its version in the file name`() {
        assertEquals(10203, ReleaseCatalog.versionCodeOfAsset("victron-monitor-phone-1.2.3.apk"))
        assertEquals("1.2.3", ReleaseCatalog.versionOfAsset("victron-monitor-wear-1.2.3.apk"))
        // A prerelease name must not decay into "beta1" -> 1.
        assertEquals(10100, ReleaseCatalog.versionCodeOfAsset("victron-monitor-wear-1.1.0-beta1.apk"))
        assertNull(ReleaseCatalog.versionCodeOfAsset("SHA256SUMS.txt"))
        assertNull(ReleaseCatalog.versionCodeOfAsset("victron-monitor-wear.apk"))
    }

    // GitHub sends far more fields than these; ignoreUnknownKeys is what keeps that from throwing.
    @Test
    fun `github release json decodes`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<List<Release>>(
            """
            [{
              "url": "https://api.github.com/repos/o/r/releases/1",
              "tag_name": "v1.4.0",
              "name": "v1.4.0",
              "draft": false,
              "prerelease": false,
              "body": "## Install\n...",
              "author": { "login": "someone" },
              "assets": [
                {
                  "name": "victron-monitor-wear-1.4.0.apk",
                  "size": 4567890,
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://github.com/o/r/releases/download/v1.4.0/victron-monitor-wear-1.4.0.apk"
                }
              ]
            }]
            """.trimIndent(),
        )
        val asset = ReleaseCatalog.assetFor(decoded.single(), UpdateVariant.Wear)
        assertEquals("victron-monitor-wear-1.4.0.apk", asset?.name)
        assertEquals(4567890, asset?.size)
        assertTrue(asset?.downloadUrl?.startsWith("https://github.com/") == true)
    }
}
