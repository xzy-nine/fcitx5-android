package org.fcitx.fcitx5.android.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 词库同步的收集规则：云端只保留迁移后的新格式。
 */
class DictCollectorLegacyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(root: File, rel: String, text: String = "x"): File =
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    @Test
    fun legacyPinyinDictIsNotCollected() {
        val root = temp.newFolder("data")
        file(root, "pinyin/user.dict", "binary")
        file(root, "pinyin/customphrase", "text")
        val rels = DictCollector.listSyncable(root).map { it.relativePath }
        assertFalse("旧拼音词库不应同步", rels.any { it.endsWith("pinyin/user.dict") })
        assertTrue("自定义短语仍应同步", rels.any { it.endsWith("pinyin/customphrase") })
    }

    @Test
    fun legacyImportedDictsAreNotCollected() {
        val root = temp.newFolder("data")
        file(root, "pinyin/dictionaries/sample.dict", "binary")
        val rels = DictCollector.listSyncable(root).map { it.relativePath }
        assertTrue(rels.none { it.contains("dictionaries/") })
    }

    @Test
    fun rimeUserDictTextIsCollected() {
        val root = temp.newFolder("data")
        file(root, "rime/user.txt", "词条\tci tiao\t100")
        val rels = DictCollector.listSyncable(root).map { it.relativePath }
        assertEquals(listOf("data/rime/user.txt"), rels)
    }

    @Test
    fun rimeBuildAndUserDbAreNotCollected() {
        val root = temp.newFolder("data")
        file(root, "rime/user.txt", "x")
        file(root, "rime/installation.yaml", "x")
        file(root, "rime/build/pinyin_simp.table.bin", "x")
        file(root, "rime/pinyin_simp.userdb/000003.log", "x")
        val rels = DictCollector.listSyncable(root).map { it.relativePath }
        assertEquals(listOf("data/rime/user.txt"), rels)
    }

    @Test
    fun migrationMarkerIsNotCollected() {
        val root = temp.newFolder("data")
        file(root, "rime/.user-dict-migrated", "pinyin/user.dict|1|2")
        assertTrue(DictCollector.listSyncable(root).isEmpty())
    }

    @Test
    fun legacyDetectionCoversBothPaths() {
        assertTrue(DictCollector.isLegacyDict("pinyin/user.dict"))
        assertTrue(DictCollector.isLegacyDict("pinyin/dictionaries/a.dict"))
        assertFalse(DictCollector.isLegacyDict("rime/user.txt"))
    }
}
