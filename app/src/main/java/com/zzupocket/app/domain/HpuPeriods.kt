package com.zzupocket.app.domain

import java.time.LocalDate

/**
 * 河南理工大学教学作息时间表。
 *
 * 学校一年执行**两套固定作息**（来源：2026-2027 学年校历"教学作息时间"表，
 * 与教务抓包 `courseTableForStd!courseTable.action` 内联时刻一致）：
 *  - **春季**（5 月 1 日 ~ 10 月 1 日）：下午第 6 节 15:00 开始；
 *  - **秋季**（10 月 1 日 ~ 次年 5 月 1 日）：下午第 6 节 14:30 开始，整体早半小时。
 * 上午第 1-5 节两套完全相同；每套 11 节，另有两次大课间（不占课表格子）。
 *
 * 用户可三选一：[PeriodMode.AUTO] 按当前日期自动选、[PeriodMode.SPRING] / [PeriodMode.AUTUMN] 手动固定。
 */
object HpuPeriods {

    /**
     * 课表最大节次数（权威来源）。HPU 作息为 11 节；课表网格、今日时间轴、小组件
     * 的节次循环/过滤一律引用本常量，禁止在 UI 层写死 10——否则第 11 节课被截断。
     */
    const val UNIT_COUNT = 11

    enum class PeriodMode {
        AUTO,      // 按当前日期自动（默认）
        SPRING,    // 强制春季作息
        AUTUMN,    // 强制秋季作息
        ;

        val label: String
            get() = when (this) {
                AUTO -> "自动（按日期）"
                SPRING -> "春季作息"
                AUTUMN -> "秋季作息"
            }
    }

    /** 春季：第五节及以前与秋季相同，第六节起下午后移 30 分钟 */
    private val spring = listOf(
        Period(1, "第1节", 800, 845, "MORNING", 1),
        Period(2, "第2节", 855, 940, "MORNING", 1),
        Period(3, "第3节", 1000, 1045, "MORNING", 2),
        Period(4, "第4节", 1055, 1140, "MORNING", 2),
        Period(5, "第5节", 1150, 1235, "MORNING", 3),
        Period(6, "第6节", 1500, 1545, "AFTERNOON", 4),
        Period(7, "第7节", 1555, 1640, "AFTERNOON", 4),
        Period(8, "第8节", 1700, 1745, "AFTERNOON", 5),
        Period(9, "第9节", 1755, 1840, "EVENING", 6),
        Period(10, "第10节", 1930, 2015, "EVENING", 7),
        Period(11, "第11节", 2025, 2110, "EVENING", 7),
    )

    /** 秋季：下午第 6 节 14:30 开始，整体比春季早 30 分钟 */
    private val autumn = listOf(
        Period(1, "第1节", 800, 845, "MORNING", 1),
        Period(2, "第2节", 855, 940, "MORNING", 1),
        Period(3, "第3节", 1000, 1045, "MORNING", 2),
        Period(4, "第4节", 1055, 1140, "MORNING", 2),
        Period(5, "第5节", 1150, 1235, "MORNING", 3),
        Period(6, "第6节", 1430, 1515, "AFTERNOON", 4),
        Period(7, "第7节", 1525, 1610, "AFTERNOON", 4),
        Period(8, "第8节", 1630, 1715, "AFTERNOON", 5),
        Period(9, "第9节", 1725, 1810, "EVENING", 6),
        Period(10, "第10节", 1900, 1945, "EVENING", 7),
        Period(11, "第11节", 1955, 2040, "EVENING", 7),
    )

    fun springPeriods(): List<Period> = spring
    fun autumnPeriods(): List<Period> = autumn

    /**
     * 自动模式按日期判断当前用哪套。
     * 校历口径：春季"(五·一至十·一)"、秋季"(十·一至五·一)"。
     * 边界：5/1 与 10/1 当天起切换 —— 5/1 ≤ date < 10/1 → 春季，否则秋季。
     */
    fun autoModeFor(date: LocalDate): PeriodMode {
        val md = date.monthValue * 100 + date.dayOfMonth
        return if (md in 501..930) PeriodMode.SPRING else PeriodMode.AUTUMN
    }

    /** 按用户偏好返回生效的作息表；AUTO 时按 [today] 判断 */
    fun periodsFor(mode: PeriodMode, today: LocalDate = LocalDate.now()): List<Period> =
        when (resolvedMode(mode, today)) {
            PeriodMode.SPRING -> spring
            PeriodMode.AUTUMN -> autumn
            PeriodMode.AUTO -> spring   // 理论不可达：resolvedMode 不会返回 AUTO
        }

    /** 解析后的实际模式（把 AUTO 落成 SPRING/AUTUMN），界面显示"当前生效：春季/秋季"用 */
    fun resolvedMode(mode: PeriodMode, today: LocalDate = LocalDate.now()): PeriodMode =
        if (mode == PeriodMode.AUTO) autoModeFor(today) else mode

    /** 当前生效套的名称（给用户看的短文案） */
    fun effectiveLabel(mode: PeriodMode, today: LocalDate = LocalDate.now()): String =
        when (resolvedMode(mode, today)) {
            PeriodMode.SPRING -> "春季作息（5·1–10·1，下午 15:00 起）"
            else -> "秋季作息（10·1–5·1，下午 14:30 起）"
        }
}
