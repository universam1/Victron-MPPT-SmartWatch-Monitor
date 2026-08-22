package de.universam.victron.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Reads releases from the GitHub REST API and downloads their assets.
 *
 * Plain [HttpURLConnection] on purpose: the app pulls two small JSON documents and one APK every
 * few hours, which is not worth an HTTP client dependency (and the watch APK stays small). The
 * repository is public, so no token is involved — an unauthenticated call is rate limited to 60
 * requests per hour and per IP, far above one check every six hours.
 */
public class GitHubUpdateSource(
    private val repositorySlug: String = DEFAULT_REPOSITORY,
    private val openConnection: (String) -> HttpURLConnection = { url ->
        URI(url).toURL().openConnection() as HttpURLConnection
    },
) {

    /**
     * The most recent releases, newest first as GitHub returns them. [ReleaseCatalog.newestUpdate]
     * does not rely on that order — a re-tagged older version would otherwise win.
     */
    public suspend fun releases(limit: Int = RELEASE_PAGE): List<Release> = withContext(Dispatchers.IO) {
        val body = get("https://api.github.com/repos/$repositorySlug/releases?per_page=$limit") {
            it.inputStream.use { stream -> stream.readBytes().decodeToString() }
        }
        json.decodeFromString<List<Release>>(body)
    }

    /** Fetches a small text asset, e.g. `SHA256SUMS.txt`. */
    public suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        get(url) { it.inputStream.use { stream -> stream.readBytes().decodeToString() } }
    }

    /**
     * Downloads [url] into [target] and returns the SHA-256 of what was written.
     *
     * The digest is computed while streaming, so the file is never read twice — on a watch that
     * matters more than the few lines it costs. [onProgress] receives bytes/total, where total is
     * -1 when the server does not say.
     */
    public suspend fun download(
        url: String,
        target: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        get(url) { connection ->
            val total = connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * One GET with explicit redirect handling: [HttpURLConnection] refuses to follow a redirect
     * that changes protocol, and an asset URL hops from github.com to the release CDN.
     */
    private fun <T> get(url: String, body: (HttpURLConnection) -> T): T {
        var remainingRedirects = MAX_REDIRECTS
        var target = url
        while (true) {
            val connection = openConnection(target).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                // GitHub rejects requests without one, and the asset URLs redirect to a CDN.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json, */*")
                instanceFollowRedirects = false
            }
            try {
                val status = connection.responseCode
                if (status in 300..399 && remainingRedirects-- > 0) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location from $target")
                    // resolve() so a relative Location header works too.
                    target = URI(target).resolve(location).toString()
                    continue
                }
                if (status !in 200..299) {
                    throw IOException("HTTP $status for $target")
                }
                return body(connection)
            } finally {
                connection.disconnect()
            }
        }
    }

    public companion object {
        /** Where the release workflow publishes the APKs. Public repo, so no credentials. */
        public const val DEFAULT_REPOSITORY: String = "universam1/Victron-MPPT-SmartWatch-Monitor"

        private const val RELEASE_PAGE = 10
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MILLIS = 20_000
        private const val USER_AGENT = "victron-mppt-monitor"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
