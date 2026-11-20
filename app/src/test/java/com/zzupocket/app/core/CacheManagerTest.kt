package com.zzupocket.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 缓存统计/清理的纯文件逻辑回归（用临时目录，不依赖 Android Context）。 */
class CacheManagerTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "cachetest-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `统计目录递归累加文件大小`() {
        val root = tempDir()
        File(root, "a.txt").writeText("12345")          // 5 bytes
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("1234567890") // 10 bytes
        assertEquals(15L, CacheManager.dirSize(root))
        root.deleteRecursively()
    }

    @Test
    fun `不存在或空目录统计为零`() {
        val missing = File(tempDir(), "nope")
        assertEquals(0L, CacheManager.dirSize(missing))
    }

    @Test
    fun `清理只删内容保留目录本身`() {
        val root = tempDir()
        File(root, "a.txt").writeText("x")
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("y")
        assertTrue(CacheManager.dirSize(root) > 0)

        CacheManager.deleteContents(root)

        assertTrue(root.exists())
        assertTrue(root.isDirectory)
        assertEquals(0L, CacheManager.dirSize(root))
        root.deleteRecursively()
    }
}
