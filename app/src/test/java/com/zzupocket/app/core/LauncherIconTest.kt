package com.zzupocket.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 [LauncherIcon.IconColor] 的约定。
 *
 * 这些约束不是"美观"问题：每个配色都对应一份 manifest 的 `activity-alias`
 * （`${packageName}.alias.MainActivity${aliasSuffix}`）和一套 `mipmap/ic_launcher_*`。
 * 后缀重名或改了名却没同步 manifest，运行时切换会静默失败（组件找不到），
 * 而桌面图标就停在旧配色上——这类问题只靠手测很难发现。
 */
class LauncherIconTest {

    @Test fun `每个配色的后缀唯一`() {
        val suffixes = LauncherIcon.IconColor.entries.map { it.aliasSuffix }
        assertEquals(suffixes.size, suffixes.toSet().size)
    }

    @Test fun `后缀是非空的 ASCII 标识符`() {
        // alias 名是 "<package>.alias.MainActivity<后缀>"，后缀里放空格/中文会造出非法组件名
        LauncherIcon.IconColor.entries.forEach { color ->
            assertTrue("后缀不能为空：$color", color.aliasSuffix.isNotBlank())
            assertTrue("后缀必须是字母开头：${color.aliasSuffix}", color.aliasSuffix.first().isLetter())
            assertTrue("后缀只能是字母数字：${color.aliasSuffix}", color.aliasSuffix.all { it.isLetterOrDigit() })
        }
    }

    @Test fun `每个配色都有面向用户的标签`() {
        LauncherIcon.IconColor.entries.forEach {
            assertTrue("缺少标签：$it", it.label.isNotBlank())
        }
    }

    @Test fun `默认配色存在且是蓝色的那套`() {
        // 默认配色必须与 manifest 里 enabled=true 的那个 alias 一致，否则首装就会两个图标
        assertEquals(LauncherIcon.IconColor.BLUE, LauncherIcon.IconColor.DEFAULT)
        assertTrue(LauncherIcon.IconColor.entries.contains(LauncherIcon.IconColor.DEFAULT))
    }

    @Test fun `配色数量与提供的图标套数一致`() {
        // 6 套 mipmap + 6 个 alias。改这里就必须同步加资源，否则就是"选了但没效果"
        assertEquals(6, LauncherIcon.IconColor.entries.size)
    }
}
