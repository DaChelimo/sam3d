package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.AppVersion
import edu.upenn.sam3d.domain.model.UpdateStatus
import edu.upenn.sam3d.process.GitHubUpdateChecker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckTest {

    // ── version comparison ──────────────────────────────────────────────────

    @Test
    fun `newer versions compare greater across each segment`() {
        assertTrue(AppVersion.isNewer("1.2.3", "1.2.2"))
        assertTrue(AppVersion.isNewer("1.3.0", "1.2.9"))
        assertTrue(AppVersion.isNewer("2.0.0", "1.99.99"))
        assertTrue(AppVersion.isNewer("v1.2.3", "1.2.2"), "the API's tags carry a v prefix")
    }

    @Test
    fun `equal or older versions never trigger the banner`() {
        assertFalse(AppVersion.isNewer("1.2.3", "1.2.3"))
        assertFalse(AppVersion.isNewer("v1.2.3", "v1.2.3"))
        assertFalse(AppVersion.isNewer("1.2.2", "1.2.3"))
        assertFalse(AppVersion.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun `differing segment counts are padded with zeros, not treated as unparseable`() {
        assertTrue(AppVersion.isNewer("1.3", "1.2.9"))
        assertFalse(AppVersion.isNewer("1.2", "1.2.0"), "1.2 and 1.2.0 are the same version")
        assertTrue(AppVersion.isNewer("1.2.1", "1.2"))
    }

    /** A tag we can't read must leave the user alone rather than nag about a maybe-update. */
    @Test
    fun `unparseable versions compare as not-newer`() {
        assertFalse(AppVersion.isNewer("nightly", "1.2.3"))
        assertFalse(AppVersion.isNewer("1.2.3-rc1", "1.2.3"))
        assertFalse(AppVersion.isNewer("", "1.2.3"))
        assertFalse(AppVersion.isNewer("1.2.3", "not-a-version"))
    }

    // ── the checker ─────────────────────────────────────────────────────────

    private fun release(tag: String, url: String = "https://example.test/r") =
        """{"tag_name":"$tag","html_url":"$url","name":"ignored","extra":{"unknown":true}}"""

    private fun checker(current: String, body: String?) =
        GitHubUpdateChecker(repo = "test/repo", currentVersion = current, fetch = { body })

    @Test
    fun `a newer published tag becomes an Available status without its v prefix`() = runBlocking {
        val status = checker("1.2.2", release("v1.2.3")).check()
        assertEquals(UpdateStatus.Available("1.2.3", "https://example.test/r"), status)
    }

    @Test
    fun `the current version reports UpToDate`() = runBlocking {
        assertEquals(UpdateStatus.UpToDate, checker("1.2.3", release("v1.2.3")).check())
    }

    /**
     * Every failure has to collapse to Unknown: a lab machine may be offline or behind a proxy, and
     * an update check is never worth interrupting someone mid-run over.
     */
    @Test
    fun `network failure, malformed json and an empty tag all stay silent`() = runBlocking {
        assertEquals(UpdateStatus.Unknown, checker("1.2.2", null).check(), "network down")
        assertEquals(UpdateStatus.Unknown, checker("1.2.2", "not json at all").check())
        assertEquals(UpdateStatus.Unknown, checker("1.2.2", "{}").check(), "no tag_name field")
        assertEquals(UpdateStatus.Unknown, checker("1.2.2", release("")).check(), "empty tag")
    }

    @Test
    fun `a release with no html_url still points somewhere useful`() = runBlocking {
        val status = checker("1.2.2", """{"tag_name":"v1.2.3","html_url":""}""").check()
        assertEquals(
            UpdateStatus.Available("1.2.3", "https://github.com/test/repo/releases/latest"),
            status,
        )
    }

    /**
     * CI refreshes a rolling `latest-build` prerelease on every manual dispatch. `/releases/latest`
     * excludes prereleases server-side, so those never reach us — but if one ever did, its
     * non-numeric tag must not raise a banner.
     */
    @Test
    fun `the rolling latest-build prerelease tag would not raise a banner`() = runBlocking {
        assertEquals(UpdateStatus.UpToDate, checker("1.2.2", release("latest-build")).check())
    }
}
