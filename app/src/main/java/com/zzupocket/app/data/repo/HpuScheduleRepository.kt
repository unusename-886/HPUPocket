package com.zzupocket.app.data.repo

import androidx.room.withTransaction
import com.zzupocket.app.data.auth.HpuSessionStore
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.CourseEntity
import com.zzupocket.app.data.db.CourseSessionEntity
import com.zzupocket.app.data.db.SemesterEntity
import com.zzupocket.app.data.db.TimetableSyncEntity
import com.zzupocket.app.data.eas.HpuEamsClient
import com.zzupocket.app.data.eas.HpuScheduleParser
import java.time.LocalDate

/**
 * 河南理工课表仓库。
 *
 * 与郑大 [ScheduleRepository] 平行：郑大走 JSON API + JWT，HPU 走 EAMS HTML 页面 + 会话 Cookie。
 * 两者落到**同一套表**，所以课表网格、首页时间轴、桌面组件、课程总览全部自动复用，无需改动。
 * 用独立 `source = "hpu"` 标记，避免与郑大 "eas" 缓存混淆。
 *
 * 周次展开：HPU 一门课给出 `weeks: Set<教学周>`（可能单双周），这里按周展开成多条 Session
 * （date=null 按周重复），与研究生课表口径一致。
 */
class HpuScheduleRepository(
    private val db: AppDatabase,
    private val eams: HpuEamsClient,
    private val session: HpuSessionStore,
) {
    sealed interface SyncResult {
        data object Success : SyncResult
        data object NotLoggedIn : SyncResult
        data class Failure(val message: String) : SyncResult
    }

    /**
     * 同步当前学期课表。流程：会话校验 → 学期页 → 课表 → 落库。
     * 只有会话有效才动手；绝不在没登录时拿空数据覆盖已有缓存。
     */
    suspend fun sync(): SyncResult {
        com.zzupocket.app.core.CrashLog.i("schedule", "sync start, hasSession=${session.hasSession()}")
        if (!session.hasSession() || !session.isValid()) {
            com.zzupocket.app.core.CrashLog.w("schedule", "sync abort: not logged in / session invalid (webvpn block?)")
            return SyncResult.NotLoggedIn
        }
        return runCatching {
            val page = eams.fetchSemesterPage()
            if (page == null) {
                com.zzupocket.app.core.CrashLog.w("schedule", "sync: semester page null (blocked or parse fail)")
                return@runCatching SyncResult.Failure("没能读取教务课表页，请稍后重试")
            }
            com.zzupocket.app.core.CrashLog.i(
                "schedule",
                "sync: semesterId=${page.semesterId} taskIds=${page.taskIds.size}",
            )
            val parsed = eams.fetchCourseTable(page.semesterId, page.taskIds)
            com.zzupocket.app.core.CrashLog.i("schedule", "sync: parsed sessions=${parsed.size}")
            if (parsed.isEmpty()) {
                return@runCatching SyncResult.Failure("教务返回了空课表（可能还没选课）")
            }
            persist(page.semesterId, parsed)
            com.zzupocket.app.core.CrashLog.i("schedule", "sync: persisted OK")
            SyncResult.Success
        }.getOrElse {
            com.zzupocket.app.core.CrashLog.e("schedule", "sync exception", it)
            SyncResult.Failure(it.message ?: "同步课表失败")
        }
    }

    private suspend fun persist(semesterId: Int, parsed: List<HpuScheduleParser.ParsedSession>) {
        val now = System.currentTimeMillis()
        val (nameZh, schoolYear, startDate, endDate) = inferSemesterMeta()

        val semesterEntity = SemesterEntity(
            id = semesterId,
            code = nameZh,
            nameZh = nameZh,
            schoolYear = schoolYear,
            startDate = startDate,
            endDate = endDate,
            weekStartOnSunday = false,   // HPU 校历周一为一周之始
            weekIndicesCsv = (1..20).joinToString(","),
            isCurrent = true,
            updatedAt = now,
        )

        val sessions = mutableListOf<CourseSessionEntity>()
        parsed.forEach { s ->
            s.weeks.forEach { week ->
                sessions += CourseSessionEntity(
                    id = ScheduleRepository.sessionIdOf(semesterId, s.courseCode, week, s.weekday, s.startUnit),
                    courseKey = ScheduleRepository.courseKeyOf(semesterId, s.courseCode),
                    semesterId = semesterId,
                    source = SOURCE,
                    courseName = s.courseName,
                    weekIndex = week,
                    weekday = s.weekday,
                    startUnit = s.startUnit,
                    endUnit = s.endUnit,
                    date = null,                // 按周重复，无具体日期
                    startTime = null,           // 时刻由 HpuPeriods 提供（见 periodProvider）
                    endTime = null,
                    teacherName = s.teachers.joinToString(","),
                    roomText = s.roomText,
                    campusText = s.campusText,
                    color = null,
                    experiment = false,
                    updatedAt = now,
                )
            }
        }

        val courses = parsed.groupBy { it.courseCode }.map { (code, list) ->
            val first = list.first()
            CourseEntity(
                key = ScheduleRepository.courseKeyOf(semesterId, code),
                semesterId = semesterId,
                code = code,
                nameZh = first.courseName,
                credits = 0.0,
                openDepartment = null,
                teachersCsv = first.teachers.distinct().joinToString(","),
                updatedAt = now,
            )
        }

        db.withTransaction {
            db.semesterDao().upsertAll(listOf(semesterEntity))
            db.semesterDao().clearCurrent()
            db.semesterDao().markCurrent(semesterId)
            db.courseSessionDao().deleteBySemesterAndSource(semesterId, SOURCE)
            db.courseDao().deleteBySemester(semesterId)
            db.courseDao().upsertAll(courses)
            db.courseSessionDao().upsertAll(sessions)
            db.timetableSyncDao().upsert(
                TimetableSyncEntity(
                    source = SOURCE,
                    semesterId = semesterId,
                    fetchedAt = now,
                    contentHash = sessions.map { "${it.weekIndex}|${it.weekday}|${it.startUnit}|${it.courseName}" }
                        .sorted().joinToString("\n").hashCode().toString(),
                    cacheVersion = ScheduleRepository.CACHE_VERSION,
                )
            )
        }
    }

    /**
     * 从当前日期推断学期元信息（名称/学年/起止）。
     * HPU 校历：第一学期 9 月初开学、次年 1 月中结束；第二学期 2 月底开学、7 月初结束。
     * 起始日给的是"正式上课日"近似值，用于 WeekSettings 初值；用户可在课表设置里校正。
     */
    private fun inferSemesterMeta(): Meta {
        val today = LocalDate.now()
        val y = today.year
        return if (today.monthValue in 8..12 || (today.monthValue == 1 && today.dayOfMonth >= 15)) {
            // 第一学期：2026 年秋季
            val startYear = if (today.monthValue == 1) y - 1 else y
            Meta(
                nameZh = "$startYear-${startYear + 1}-1",
                schoolYear = "$startYear-${startYear + 1}",
                startDate = "$startYear-09-01",
                endDate = "${startYear + 1}-01-15",
            )
        } else {
            // 第二学期：次年春季
            val startYear = y - 1
            Meta(
                nameZh = "$startYear-$y-2",
                schoolYear = "$startYear-$y",
                startDate = "$y-03-01",
                endDate = "$y-07-05",
            )
        }
    }

    private data class Meta(
        val nameZh: String,
        val schoolYear: String,
        val startDate: String,
        val endDate: String,
    )

    companion object {
        const val SOURCE = "hpu"
    }
}
