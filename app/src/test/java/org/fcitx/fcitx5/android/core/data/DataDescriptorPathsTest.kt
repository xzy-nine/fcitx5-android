package org.fcitx.fcitx5.android.core.data

import android.app.Application
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DataDescriptorPathsTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun historicalWindowsSeparatorsBecomePosixPaths() {
        val raw = """
            {
                "sha256": "old",
                "files": {
                    "usr\\share": "",
                    "usr\\share\\rime-data\\default.yaml": "abc"
                },
                "symlinks": {
                    "usr\\share\\rime-data\\opencc": "usr\\share\\opencc"
                }
            }
        """.trimIndent()
        val descriptor = json.decodeFromString<DataDescriptor>(raw).withNormalizedPaths()
        assertEquals(setOf("usr/share", "usr/share/rime-data/default.yaml"), descriptor.files.keys)
        assertEquals(mapOf("usr/share/rime-data/opencc" to "usr/share/opencc"), descriptor.symlinks)
    }

    @Test
    fun normalizedPathsAreValidAssetPaths() {
        val descriptor = DataDescriptor("old", mapOf("usr\\share" to "")).withNormalizedPaths()
        assertTrue(descriptor.files.keys.all { '\\' !in it })
    }

    @Test
    fun alreadyNormalizedDescriptorIsUnchanged() {
        val original = DataDescriptor(
            "sha",
            mapOf("usr/share" to "", "usr/share/a.yaml" to "abc"),
            mapOf("usr/share/link" to "usr/share/real")
        )
        assertEquals(original, original.withNormalizedPaths())
    }

    @Test
    fun diffAgainstHistoricalDescriptorOnlyDeletesObsoletePaths() {
        val old = DataDescriptor("old", mapOf("usr\\share" to "", "usr\\share\\gone.yaml" to "x"))
            .withNormalizedPaths()
        val hierarchy = DataHierarchy().apply {
            install(DataDescriptor("new", mapOf("usr/share" to "", "usr/share/kept.yaml" to "y")), FileSource.Main)
        }
        val actions = DataHierarchy.diff(old, hierarchy)
        assertTrue(actions.none { it is FileAction.CreateDir && it.path.contains('\\') })
        assertTrue(actions.filterIsInstance<FileAction.DeleteFile>().all { it.path == "usr/share/gone.yaml" })
    }
}
