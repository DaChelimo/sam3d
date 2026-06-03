package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.UserConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §11.3 config schema: sparse/partial/forward-compatible JSON → defaults; bundled template sanity. */
class UserConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `empty object yields an all-null config (everything falls back)`() {
        val c = json.decodeFromString<UserConfig>("{}")
        assertNull(c.sam3dGcodeDir)
        assertNull(c.dicomFolderPath)
        assertNull(c.outputFolderPath)
        assertNull(c.pythonPath)
        assertNull(c.maxCachedBitmaps)
    }

    @Test
    fun `a full config parses every field`() {
        val c = json.decodeFromString<UserConfig>(
            """{"sam3dGcodeDir":"/a","dicomFolderPath":"/b","outputFolderPath":"/c","pythonPath":"/p","maxCachedBitmaps":99}"""
        )
        assertEquals("/a", c.sam3dGcodeDir)
        assertEquals("/b", c.dicomFolderPath)
        assertEquals("/c", c.outputFolderPath)
        assertEquals("/p", c.pythonPath)
        assertEquals(99, c.maxCachedBitmaps)
    }

    @Test
    fun `unknown keys are ignored (forward compatible)`() {
        val c = json.decodeFromString<UserConfig>("""{"pythonPath":"x","someFutureKey":123}""")
        assertEquals("x", c.pythonPath)
    }

    @Test
    fun `window size and slices fields round-trip (task 1 and 6)`() {
        val c = json.decodeFromString<UserConfig>(
            """{"windowWidth":1600,"windowHeight":900,"slices":8}"""
        )
        assertEquals(1600, c.windowWidth)
        assertEquals(900, c.windowHeight)
        assertEquals(8, c.slices)
    }

    @Test
    fun `save format (pretty) round-trips the whole config without losing keys`() {
        // Mirrors ConfigLoader.save (pretty, encodeDefaults=false) without touching the real config.
        val pretty = Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }
        val original = UserConfig(
            sam3dGcodeDir = "/engine", pythonPath = "/env/bin/python",
            maxCachedBitmaps = 256, slices = 120, windowWidth = 1440, windowHeight = 860,
        )
        val restored = pretty.decodeFromString<UserConfig>(pretty.encodeToString(original))
        assertEquals(original, restored, "path/env keys must survive a window-size save round-trip")
    }

    @Test
    fun `bundled config_default_json parses with documented defaults and no machine paths`() {
        val text = javaClass.getResourceAsStream("/config.default.json")!!
            .bufferedReader().use { it.readText() }
        val c = json.decodeFromString<UserConfig>(text)
        assertEquals("python3", c.pythonPath)
        assertEquals(256, c.maxCachedBitmaps)
        assertNull(c.sam3dGcodeDir, "the bundled template must not hardcode machine paths (§11.3)")
    }
}
