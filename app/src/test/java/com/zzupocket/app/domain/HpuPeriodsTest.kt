package com.zzupocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** HPU 春/秋两套作息与自动切换的回归测试（来源：2026-2027 校历 + 教务抓包）。 */
class HpuPeriodsTest {

    @Test
    fun `两套各11节且编号连续`() {
        val spring = HpuPeriods.springPeriods()
        val autumn = HpuPeriods.autumnPeriods()
        assertEquals(11, spring.size)
        assertEquals(11, autumn.size)
        assertEquals((1..11).toList(), spring.map { it.indexNo })
        assertEquals((1..11).toList(), autumn.map { it.indexNo })
    }

    @Test
    fun `上午前5节两套完全相同`() {
        val spring = HpuPeriods.springPeriods().take(5)
        val autumn = HpuPeriods.autumnPeriods().take(5)
        assertEquals(spring, autumn)
    }

    @Test
    fun `春季下午第六节15点 秋季14点半`() {
        val spring = HpuPeriods.springPeriods()
        val autumn = HpuPeriods.autumnPeriods()
        assertEquals("15:00", spring[5].startText)
        assertEquals("14:30", autumn[5].startText)
        // 第六节起整体早 30 分钟
        assertEquals("15:45", spring[5].endText)
        assertEquals("15:15", autumn[5].endText)
    }

    @Test
    fun `春季末节2025到2110 秋季1955到2040`() {
        assertEquals("20:25-21:10", HpuPeriods.springPeriods()[10].let { "${it.startText}-${it.endText}" })
        assertEquals("19:55-20:40", HpuPeriods.autumnPeriods()[10].let { "${it.startText}-${it.endText}" })
    }

    @Test
    fun `自动切换边界_5月1切春季_10月1切秋季`() {
        assertEquals(HpuPeriods.PeriodMode.SPRING, HpuPeriods.autoModeFor(LocalDate.of(2026, 5, 1)))
        assertEquals(HpuPeriods.PeriodMode.SPRING, HpuPeriods.autoModeFor(LocalDate.of(2026, 9, 30)))
        assertEquals(HpuPeriods.PeriodMode.AUTUMN, HpuPeriods.autoModeFor(LocalDate.of(2026, 10, 1)))
        assertEquals(HpuPeriods.PeriodMode.AUTUMN, HpuPeriods.autoModeFor(LocalDate.of(2027, 4, 30)))
    }

    @Test
    fun `手动模式不受日期影响`() {
        val date = LocalDate.of(2026, 5, 15) // 本该是春季
        assertEquals(HpuPeriods.PeriodMode.AUTUMN, HpuPeriods.resolvedMode(HpuPeriods.PeriodMode.AUTUMN, date))
        assertEquals(
            HpuPeriods.autumnPeriods(),
            HpuPeriods.periodsFor(HpuPeriods.PeriodMode.AUTUMN, date),
        )
    }

    @Test
    fun `自动模式按日期解析成具体作息`() {
        val springDay = LocalDate.of(2026, 6, 1)
        val autumnDay = LocalDate.of(2026, 11, 19)
        assertEquals("15:00", HpuPeriods.periodsFor(HpuPeriods.PeriodMode.AUTO, springDay)[5].startText)
        assertEquals("14:30", HpuPeriods.periodsFor(HpuPeriods.PeriodMode.AUTO, autumnDay)[5].startText)
    }

    @Test
    fun `大课间不占课表节次_仍为11节`() {
        // 校历里"大课间 09:40-10:00 / 16:40-17:00"不是上课节次，不应出现在 periods 里
        assertTrue(HpuPeriods.springPeriods().none { it.nameZh.contains("课间") })
    }
}
