package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.UserConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * Loads & saves [UserConfig] (§11.3). Load resolution order: <userDataDir>/SAM3D/config.json →
 * bundled config.default.json (classpath) → empty. Exception-safe, so a missing/corrupt file just
 * falls through to defaults rather than crashing startup.
 */
object ConfigLoader {
    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }

    fun load(): UserConfig {
        val userFile = OsUtils.userDataDir().resolve("config.json")
        if (Files.exists(userFile)) {
            runCatching { json.decodeFromString<UserConfig>(Files.readString(userFile)) }
                .getOrNull()?.let { return it }
        }
        runCatching {
            ConfigLoader::class.java.getResourceAsStream("/config.default.json")
                ?.bufferedReader()?.use { json.decodeFromString<UserConfig>(it.readText()) }
        }.getOrNull()?.let { return it }
        return UserConfig()
    }

    /**
     * Persist [config] to <userDataDir>/SAM3D/config.json as pretty JSON (§ task 1). Pass the whole
     * config — callers should `load()`, copy the field(s) they're changing, then save — so unrelated
     * keys (paths, python env) are preserved rather than clobbered. Best-effort & exception-safe.
     */
    fun save(config: UserConfig) {
        runCatching {
            val userFile = OsUtils.userDataDir().resolve("config.json")
            Files.createDirectories(userFile.parent)
            Files.writeString(userFile, prettyJson.encodeToString(config))
        }
    }

    /**
     * §11.3: on first launch, copy the bundled template to <userDataDir>/SAM3D/config.json so the
     * user has a file to edit. No-op if it already exists. Call once at startup (main.kt).
     */
    fun ensureUserConfig() {
        val userFile = OsUtils.userDataDir().resolve("config.json")
        if (Files.exists(userFile)) return
        runCatching {
            val template = ConfigLoader::class.java.getResourceAsStream("/config.default.json")
                ?.bufferedReader()?.use { it.readText() } ?: return
            Files.createDirectories(userFile.parent)
            Files.writeString(userFile, template)
        }
    }
}
