package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.UserConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * Loads [UserConfig] (§11.3). Resolution order: <userDataDir>/SAM3D/config.json → bundled
 * config.default.json (classpath) → empty. Read-only and exception-safe, so a missing/corrupt file
 * just falls through to defaults rather than crashing startup.
 */
object ConfigLoader {
    private val json = Json { ignoreUnknownKeys = true }

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
