package com.zzupocket.app.data.repo

import com.zzupocket.app.data.prefs.UiPrefsStore
import com.zzupocket.app.domain.HpuPeriods
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.ScheduleBlock
import com.zzupocket.app.domain.scheduleBlocks
import java.time.LocalDate

/**
 * 「某一天有哪些课」的**唯一口径**。
 *
 * 为什么必须只有一个入口：今日页和桌面小组件都在问同一个问题，各写一套迟早不一致
 * （历史上就出现过今日页说"今天没课"、组件却有课）。
 *
 * 注：早期为了兼容郑州大学研究生课表，这里做过"本科 + 研究生合并"。河南理工版
 * 不考虑研究生，合并逻辑已删除，只剩当前本科学期一条链路。
 */
object TodayTimetable {

    /** 某天的全部课程，按节次排序；没有数据就返回空表。 */
    suspend fun sessionsOn(
        repo: ScheduleRepository,
        prefs: UiPrefsStore,
        date: LocalDate,
    ): List<Session> = runCatching {
        val semester = repo.currentSemester() ?: return emptyList()
        val week = repo.resolveCurrentWeek(semester, date)
        repo.sessionsOn(semester.id, date, week)
    }.getOrDefault(emptyList()).distinctBy { it.id }.sortedBy { it.startUnit }

    /** 首页沿用周课表的冲突分段与逐周选择；[sessionsOn] 仍返回完整原始日程。 */
    suspend fun displayBlocks(repo: ScheduleRepository, sessions: List<Session>): List<ScheduleBlock> {
        // 日期匹配到的临时补课可能没有有效周号/节次，仍保留原先的首页显示。
        val (indexed, unindexed) = sessions.partition {
            it.weekIndex > 0 && it.weekday in 1..7 &&
                it.startUnit in 1..HpuPeriods.UNIT_COUNT && it.endUnit in it.startUnit..HpuPeriods.UNIT_COUNT
        }
        val blocks = indexed.groupBy { it.semesterId }.flatMap { (semesterId, day) ->
            val choices = repo.conflictDisplayChoices(semesterId)
            scheduleBlocks(day, choices)
        }
        return blocks + unindexed.map { ScheduleBlock(it.startUnit, it.endUnit, listOf(it)) }
    }

    /**
     * [date] 属于第几教学周。
     *
     * ⚠️ 这个值不只是"显示第几周"：今日页与组件用它判断"是不是在学期内"，
     * `null` 会被当成**假期**。
     */
    suspend fun currentWeekAt(
        repo: ScheduleRepository,
        prefs: UiPrefsStore,
        date: LocalDate,
    ): Int? {
        val semester = runCatching { repo.currentSemester() }.getOrNull() ?: return null
        return runCatching { repo.resolveCurrentWeek(semester, date) }.getOrNull()
    }

    /**
     * 有没有"任何"课表数据。
     *
     * 组件用它区分「还没同步过」和「今天没课」——这两种情况显示不同文案，不能混。
     */
    suspend fun hasAnySchedule(repo: ScheduleRepository, prefs: UiPrefsStore): Boolean =
        runCatching { repo.currentSemester()?.let { repo.hasCourseData(it.id) } }.getOrNull() == true
}
