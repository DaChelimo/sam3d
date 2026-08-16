package edu.upenn.sam3d.process

import edu.upenn.sam3d.BuildInfo
import edu.upenn.sam3d.domain.model.AppVersion
import edu.upenn.sam3d.domain.model.UpdateStatus
import edu.upenn.sam3d.domain.usecase.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the GitHub releases API whether a newer SAM3D build exists.
 *
 * ### `/releases/latest`, deliberately
 * That endpoint excludes prereleases, which is exactly what we want: CI keeps a rolling `latest-build`
 * prerelease refreshed on every manual dispatch, and a banner firing on each of those would train the
 * lab to ignore it. Only a real `v*` tag surfaces here.
 *
 * ### Failure is silence
 * A lab machine may be offline, behind a proxy, or sharing an IP that GitHub is rate-limiting (60
 * unauthenticated requests/hour). None of that is the user's problem to solve mid-run, so every error
 * path returns [UpdateStatus.Unknown] and the banner simply never appears. The check also runs once
 * per launch, not on a timer.
 */
class GitHubUpdateChecker(
    private val repo: String = DEFAULT_REPO,
    private val currentVersion: String = BuildInfo.VERSION,
    /** Seam for tests: returns the raw response body, or null on any failure. */
    private val fetch: (String) -> String? = ::httpGet,
) : UpdateSource {

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )

    override suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        val body = fetch("https://api.github.com/repos/$repo/releases/latest") ?: return@withContext UpdateStatus.Unknown
        val release = runCatching { JSON.decodeFromString<Release>(body) }.getOrNull()
            ?: return@withContext UpdateStatus.Unknown
        val tag = release.tagName.trim()
        if (tag.isEmpty()) return@withContext UpdateStatus.Unknown
        if (!AppVersion.isNewer(tag, currentVersion)) return@withContext UpdateStatus.UpToDate
        UpdateStatus.Available(
            version = tag.removePrefix("v").removePrefix("V"),
            url = release.htmlUrl.ifBlank { "https://github.com/$repo/releases/latest" },
        )
    }

    companion object {
        const val DEFAULT_REPO = "DaChelimo/sam3d"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** Short timeouts: this runs at launch and must never delay the UI becoming usable. */
        private fun httpGet(url: String): String? = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "SAM3D-desktop")
            try {
                if (conn.responseCode != 200) return null
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
