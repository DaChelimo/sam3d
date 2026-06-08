package edu.upenn.sam3d.domain.model

import kotlinx.serialization.Serializable

/**
 * Per-user configuration (§11.3), read at runtime from <userDataDir>/SAM3D/config.json (falling
 * back to the bundled config.default.json, then to AppConfig constants). Replaces the hardcoded
 * paths that briefly lived in AppConfig.DevDefaults. All fields nullable so a partial/sparse
 * config.json leaves the rest at their defaults.
 */
@Serializable
data class UserConfig(
    val sam3dGcodeDir: String? = null,
    val dicomFolderPath: String? = null,
    val outputFolderPath: String? = null,
    val pythonPath: String? = null,
    val maxCachedBitmaps: Int? = null,
    /** Quality preset name ("DRAFT"/"PRODUCTION") chosen on Setup; drives slices + downsampling. */
    val quality: String? = null,
    /** sam3d.py `-s` slice count; lets dev flip 8↔120 without editing source (§ task 6). */
    val slices: Int? = null,
    /** Persisted main-window size (§ task 1). Null until the user first resizes/closes. */
    val windowWidth: Int? = null,
    val windowHeight: Int? = null,
)
