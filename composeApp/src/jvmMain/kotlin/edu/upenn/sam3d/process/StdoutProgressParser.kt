package edu.upenn.sam3d.process

import edu.upenn.sam3d.AppConfig
import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.model.PipelineStage

/**
 * Maps sam3d.py stdout lines to [PipelineProgress] (§6.2).
 *
 * Stateful by necessity: the per-stage tqdm progress lines (e.g. " 33%|███▎ | 2/6 […]") carry no
 * stage keyword, so we remember the stage set by the last marker line ([AppConfig.ProgressMarkers])
 * and attribute a bare tqdm percentage to it. One parser instance per pipeline run.
 *
 * Also builds a human-readable [PipelineProgress.detail] ("what's running now") from each line —
 * the tqdm description + current/total — so the UI can show e.g. "Extracting paths: 4314 / 8430".
 */
class StdoutProgressParser(
    private val stageMarkers: List<Pair<String, PipelineStage>> = AppConfig.ProgressMarkers.STAGE_MARKERS,
) {
    // Determinate tqdm with a known total: "33%|███▎      | 2/6 […]" → current=2, total=6.
    private val tqdmRegex = Regex("""(\d+)%\|[^|]*\|\s*(\d+)/(\d+)""")
    // Iterator-style tqdm with no total: "Making prompt slices: 6it […]" → step=6.
    private val iterRegex = Regex("""(\d+)it[\s\[]""")
    // tqdm's own ETA in the "[elapsed<remaining, rate]" suffix — capture the remaining after '<'.
    private val etaRegex = Regex("""<(\d{1,2}:\d{2}(?::\d{2})?)""")

    private var currentStage: PipelineStage? = null

    fun parseLine(line: String): PipelineProgress? {
        val lower = line.lowercase()
        val matched = stageMarkers.firstOrNull { (substring, _) -> lower.contains(substring) }?.second
        if (matched != null) currentStage = matched
        val stage = currentStage

        val tqdm = tqdmRegex.find(line)
        val percentage = if (tqdm != null) {
            val current = tqdm.groupValues[2].toFloatOrNull() ?: 0f
            val total = (tqdm.groupValues[3].toFloatOrNull() ?: 1f).coerceAtLeast(1f)
            (current / total).coerceIn(0f, 1f)
        } else {
            0f
        }

        val detail = buildDetail(line, tqdm, stage?.label, isMarker = matched != null)
        val eta = parseEta(line)

        return when {
            matched != null -> PipelineProgress(matched, percentage, detail = detail, etaSeconds = eta)
            tqdm != null && stage != null -> PipelineProgress(stage, percentage, detail = detail, etaSeconds = eta)
            else -> null
        }
    }

    /** tqdm's remaining-time estimate ("…<01:23, …") → seconds, or null if the line has none. */
    private fun parseEta(line: String): Long? {
        val m = etaRegex.find(line) ?: return null
        val parts = m.groupValues[1].split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]                       // MM:SS
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]     // H:MM:SS
            else -> null
        }
    }

    /** "<activity>: <current> / <total>" for tqdm lines, "<activity>: step N" for iterator tqdm,
     *  else the friendly stage label on a marker line. */
    private fun buildDetail(line: String, tqdm: MatchResult?, label: String?, isMarker: Boolean): String? {
        val desc = descOf(line)
        if (tqdm != null) {
            return "${desc ?: label ?: "Working"}: ${tqdm.groupValues[2]} / ${tqdm.groupValues[3]}"
        }
        val iter = iterRegex.find(line)
        return when {
            iter != null -> "${desc ?: label ?: "Working"}: step ${iter.groupValues[1]}"
            isMarker -> label
            else -> null
        }
    }

    /** The tqdm/print description before the first ':' (e.g. "Extracting paths"), or null if that
     *  prefix is itself part of a progress bar / has no label. */
    private fun descOf(line: String): String? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        val d = line.substring(0, idx).trim()
        return if (d.isNotEmpty() && '%' !in d && '|' !in d) d else null
    }
}
