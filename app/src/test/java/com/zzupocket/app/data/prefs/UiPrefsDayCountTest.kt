package com.zzupocket.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 课表网格列数（[UiPrefs.dayCount]）。
 *
 * 这个派生值决定表头循环、纵向虚线、每列宽度，算错的直接表现就是"表头有八列、
 * 网格只有七列"或者"周八那列永远点不到"。三个开关组合都要钉住。
 *
 * 注意：周八依赖「显示周六周日」——关掉周末时第 8 天没有锚点（会紧跟在周五后面），
 * 所以此时不论周末延长开没开，都回落成 5 列。
 */
class UiPrefsDayCountTest {

    @Test
    fun `默认是七天`() {
        assertEquals(7, UiPrefs().dayCount)
    }

    @Test
    fun `打开周末延长变八天`() {
        assertEquals(8, UiPrefs(weekendExtended = true).dayCount)
    }

    @Test
    fun `关掉周末是五天`() {
        assertEquals(5, UiPrefs(showWeekend = false).dayCount)
    }

    @Test
    fun `关掉周末时周末延长不生效`() {
        // 依赖未满足：用户可能是先开延长、后来又收起周末。此时不能给出 6 或 8 列
        assertEquals(5, UiPrefs(showWeekend = false, weekendExtended = true).dayCount)
    }

    @Test
    fun `周末延长默认关闭`() {
        // 新功能默认关闭：老用户升级后课表列数不该突然变化
        assertEquals(false, UiPrefs().weekendExtended)
    }
}
