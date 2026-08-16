package edu.upenn.sam3d.domain.model

/**
 * Whether a newer published build exists. [Unknown] is the resting state and also what every failure
 * collapses to — no network at the lab bench, GitHub rate-limiting the IP, a malformed tag — because
 * the update check is a convenience and must never present itself as a problem the user has to solve.
 */
sealed class UpdateStatus {
    object Unknown : UpdateStatus()
    object UpToDate : UpdateStatus()

    /** @param version the newer release's version, without the `v` prefix. @param url its release page. */
    data class Available(val version: String, val url: String) : UpdateStatus()
}

/**
 * Dotted-numeric version comparison, tolerant of a leading `v` and of differing segment counts.
 *
 * Deliberately not a full semver implementation: every version this project has ever published is
 * `MAJOR.MINOR.PATCH`, and the failure mode of over-engineering here is worse than the gap. Anything
 * it cannot parse compares as "not newer", so a surprising tag makes the banner stay hidden rather
 * than nag about an update that may not exist.
 */
object AppVersion {

    /** True when [candidate] is strictly newer than [current]. False if either is unparseable. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** `"v1.2.3"` → `[1, 2, 3]`; null when any segment isn't a plain number. */
    fun parse(version: String): List<Int>? {
        val trimmed = version.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('.')
        return parts.map { it.toIntOrNull() ?: return null }
    }
}
