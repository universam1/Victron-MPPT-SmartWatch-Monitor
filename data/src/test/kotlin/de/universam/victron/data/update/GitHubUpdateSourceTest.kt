package de.universam.victron.data.update

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Exercises the HTTP plumbing against a fake connection: the redirect hop to the release CDN and
 * the streaming digest are the parts that would otherwise only ever be tested on a real device.
 */
class GitHubUpdateSourceTest {

    /** Minimal [HttpURLConnection] that replays a canned status, headers and body. */
    private class FakeConnection(
        url: String,
        private val status: Int,
        private val body: ByteArray = ByteArray(0),
        private val location: String? = null,
    ) : HttpURLConnection(URI(url).toURL()) {
        val seenHeaders = mutableMapOf<String, String>()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getHeaderField(name: String): String? =
            if (name == "Location") location else null

        override fun setRequestProperty(key: String, value: String) {
            seenHeaders[key] = value
        }
    }

    private fun source(
        vararg responses: Pair<String, FakeConnection>,
    ): Pair<GitHubUpdateSource, MutableList<String>> {
        val requested = mutableListOf<String>()
        val map = responses.toMap()
        val source = GitHubUpdateSource(
            repositorySlug = "owner/repo",
            openConnection = { url ->
                requested += url
                map[url] ?: error("Unexpected request to $url")
            },
        )
        return source to requested
    }

    @Test
    fun `releases are requested from the repository slug`() = runTest {
        val json = """[{"tag_name":"v1.2.3","assets":[]}]"""
        val url = "https://api.github.com/repos/owner/repo/releases?per_page=10"
        val (source, requested) = source(url to FakeConnection(url, 200, json.toByteArray()))

        val releases = source.releases()

        assertEquals(listOf(url), requested)
        assertEquals("v1.2.3", releases.single().tag)
    }

    // Asset URLs redirect from github.com to the release CDN, and HttpURLConnection will not
    // follow that on its own once the protocol or host changes.
    @Test
    fun `a redirect is followed`() = runTest {
        val first = "https://github.com/owner/repo/releases/download/v1/app.apk"
        val cdn = "https://objects.githubusercontent.com/app.apk"
        val payload = "an apk".toByteArray()
        val (source, requested) = source(
            first to FakeConnection(first, 302, location = cdn),
            cdn to FakeConnection(cdn, 200, payload),
        )

        val target = Files.createTempDirectory("update").resolve("app.apk").toFile()
        val digest = source.download(first, target)

        assertEquals(listOf(first, cdn), requested)
        assertEquals(payload.size.toLong(), target.length())
        assertEquals(sha256(payload), digest)
    }

    @Test
    fun `the digest is computed over what was written`() = runTest {
        // Larger than the 64 KiB copy buffer, so the multi-chunk path is the one under test.
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val url = "https://example.invalid/big.apk"
        val (source, _) = source(url to FakeConnection(url, 200, payload))

        val target = Files.createTempDirectory("update").resolve("big.apk").toFile()
        var lastBytes = 0L
        val digest = source.download(url, target) { bytes, total ->
            lastBytes = bytes
            assertEquals(payload.size.toLong(), total)
        }

        assertEquals(sha256(payload), digest)
        assertEquals(payload.size.toLong(), lastBytes)
        assertTrue(target.readBytes().contentEquals(payload))
    }

    @Test
    fun `an error status fails instead of writing a broken file`() = runTest {
        val url = "https://api.github.com/repos/owner/repo/releases?per_page=10"
        val (source, _) = source(url to FakeConnection(url, 404))

        val error = assertThrows<IOException> { source.releases() }
        assertTrue(error.message?.contains("404") == true)
    }

    @Test
    fun `github gets a user agent`() = runTest {
        val url = "https://api.github.com/repos/owner/repo/releases?per_page=10"
        val connection = FakeConnection(url, 200, "[]".toByteArray())
        val (source, _) = source(url to connection)

        source.releases()

        assertEquals("victron-mppt-monitor", connection.seenHeaders["User-Agent"])
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
