package com.zzupocket.app.data.repo

import androidx.room.withTransaction
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.CourseEntity
import com.zzupocket.app.data.db.CourseSessionEntity
import com.zzupocket.app.data.db.DisplayOverrideEntity
import com.zzupocket.app.data.db.KvCacheEntity
import com.zzupocket.app.data.db.PeriodEntity
import com.zzupocket.app.data.db.SemesterEntity
import com.zzupocket.app.data.db.TimetableSyncEntity
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.ConflictDisplayChoice
import com.zzupocket.app.domain.ScheduleBlock
import com.zzupocket.app.domain.conflictDisplayKey
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Plan
import com.zzupocket.app.domain.PlanSpec
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.WeekAlignment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * 课表仓库。
 *
 * 设计要点：**拉一次整学期，之后全部本地算**。切周、课程详情、课程总览都不再发请求，
 * 所以课表页很快而且**离线可用**。
 *
 * 教学周号由服务端下发（每条上课记录自带 `weekIndex`），"今天的课"优先按**真实日期**匹配
 * ——这样学校调休/调课时不需要任何特殊处理。
 */
class ScheduleRepository(
    private val db: AppDatabase,
    /**
     * 作息时间提供者。河南理工版注入内置的双作息，
     * 这样 [periods] / [observePeriods] 的所有调用点（课表 VM、桌面组件）自动用上，
     * 且不联网、不查 DB。见 AppGraph.scheduleRepository。
     */
    private val periodProvider: (suspend () -> List<Period>)? = null,
) {

    // ── 读取 ─────────────────────────────────────────────────────────────




    /** 番茄钟标签候选：课名 */
    fun observeCourseNames(): Flow<List<String>> = db.courseDao().observeAllNames()

    suspend fun periods(): List<Period> =
        periodProvider?.invoke() ?: db.periodDao().all().map { it.toDomain() }

    suspend fun semesters(): List<Semester> = db.semesterDao().all().map { it.toDomain() }


    suspend fun currentSemester(): Semester? =
        db.semesterDao().current()?.toDomain() ?: db.semesterDao().all().firstOrNull()?.toDomain()

    suspend fun courses(semesterId: Int): List<Course> = loadCourses(semesterId)

    private suspend fun loadCourses(semesterId: Int): List<Course> =
        buildCourses(
            db.courseDao().bySemester(semesterId),
            db.courseSessionDao().bySemester(semesterId).map { it.toDomain() }.withOverrides(semesterId),
        ).withNameOverrides(semesterId)

    private fun buildCourses(
        courses: List<CourseEntity>,
        sessions: List<Session>,
    ): List<Course> {
        val byCode = sessions.groupBy { it.courseCode }
        return courses.map { c ->
            Course(
                key = c.key,
                semesterId = c.semesterId,
                code = c.code,
                nameZh = c.nameZh,
                credits = c.credits,
                openDepartment = c.openDepartment,
                teachers = c.teachersCsv.split(",").filter { it.isNotBlank() },
                sessions = byCode[c.code].orEmpty(),
            )
        }
    }

    // ── 本地计算 ─────────────────────────────────────────────────────────

    /**
     * 由学期起始日推算某一天属于第几教学周；落在学期外（假期）返回 null。
     *
     * 算法在 [WeekAlignment.indexOf]（纯函数、有单测）：对齐到周首再算，
     * 这样即使 startDate 是周中某天也不会错一周。
     */
    fun resolveCurrentWeek(semester: Semester, today: LocalDate = LocalDate.now()): Int? =
        WeekAlignment.indexOf(
            startDate = semester.startDate,
            weekStartOnSunday = semester.weekStartOnSunday,
            weekIndices = semester.weekIndices,
            date = today,
        )


    /**
     * 某一天的课。
     *
     * **优先按真实日期匹配**（教务每条记录都带 date），这样学校调休/调课自动正确；
     * 只有少数记录没有日期时才回退到「星期 + 教学周」。
     */
    suspend fun sessionsOn(
        semesterId: Int,
        date: LocalDate,
        currentWeek: Int? = null,
    ): List<Session> {
        val all = db.courseSessionDao().bySemester(semesterId).map { it.toDomain() }
            .withOverrides(semesterId)
        val iso = date.toString()
        val byDate = all.filter { it.date == iso }
        val wd = date.dayOfWeek.value // ISO: 周一=1 … 周日=7，与教务的 weekday 一致
        val withoutDate = if (currentWeek == null) {
            emptyList()
        } else {
            all.filter { it.date == null && it.weekday == wd && it.weekIndex == currentWeek }
        }
        return (byDate + withoutDate).distinctBy { it.id }.sortedBy { it.startUnit }
    }


    /**
     * 整学期全部课。
     * 界面一次性拿全，翻周时在内存里过滤 —— 这样滑周不会闪空白，也不发请求。
     */
    suspend fun sessionsOfSemester(semesterId: Int): List<Session> =
        db.courseSessionDao().bySemester(semesterId).map { it.toDomain() }.withOverrides(semesterId)


    suspend fun hasCourseData(semesterId: Int): Boolean =
        db.courseSessionDao().countBySemester(semesterId) > 0


    // ── 自定义日程 ──────────────────────────────────────────────────────

    /**
     * 一个自定义日程 = 若干周的同一条记录（每周一行），和教务课程一个存法。
     * 这样网格、今天、翻周全都不用改；教务刷新时也只删 source = 'eas' 的行。
     */
    suspend fun customPlans(semesterId: Int): List<Plan> =
        db.courseSessionDao().bySemesterAndSource(semesterId, SOURCE_CUSTOM)
            .groupBy { it.courseKey }
            .map { (courseKey, rows) ->
                val head = rows.minByOrNull { it.weekIndex } ?: rows.first()
                Plan(
                    // 表格里没有单列 courseCode，日程标识就存在 courseKey 里
                    code = courseKey.removePrefix("$semesterId:"),
                    semesterId = semesterId,
                    name = head.courseName,
                    weekday = head.weekday,
                    startUnit = head.startUnit,
                    endUnit = head.endUnit,
                    weeks = rows.map { it.weekIndex }.distinct().sorted(),
                    roomText = head.roomText,
                    colorKey = head.color,
                    teacherName = head.teacherName,
                )
            }
            .sortedWith(compareBy({ it.weekday }, { it.startUnit }))

    /** 新建或改写一个日程；返回它的 code（新建时是刚生成的） */
    suspend fun savePlan(spec: PlanSpec): String {
        val wanted = com.zzupocket.app.domain.PlanKind.codePrefix(spec.kind)
        // 类型记在 code 前缀上：编辑时如果换了类型（日程↔课程），前缀也要跟着换，
        // 否则读回来的 kind 还是旧的（2026-10-25 真机验证时踩到）
        val code = spec.code
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith(wanted)) it else wanted + it.drop(1) }
            ?: newPlanCode(spec.kind)
        val dao = db.courseSessionDao()
        // 先删旧行：改了周次/时段之后不能留下孤儿记录
        dao.deleteByCourseKey(courseKeyOf(spec.semesterId, code))

        val periods = periods().associateBy { it.indexNo }
        val startTime = periods[spec.startUnit]?.startText
        val endTime = periods[spec.endUnit]?.endText
        val now = System.currentTimeMillis()
        val rows = spec.weeks.distinct().sorted().map { week ->
            CourseSessionEntity(
                id = sessionIdOf(spec.semesterId, code, week, spec.weekday, spec.startUnit),
                courseKey = courseKeyOf(spec.semesterId, code),
                semesterId = spec.semesterId,
                source = SOURCE_CUSTOM,
                courseName = spec.name.trim(),
                weekIndex = week,
                weekday = spec.weekday,
                startUnit = spec.startUnit,
                endUnit = spec.endUnit,
                // 自定义日程不绑定真实日期：它按"教学周 + 星期"重复，
                // 学校调休时不会跟着跑（那本来也不是这门"课"的事）。
                date = null,
                startTime = startTime,
                endTime = endTime,
                teacherName = spec.teacherName?.trim()?.takeIf { it.isNotEmpty() },
                roomText = spec.roomText?.trim()?.takeIf { it.isNotEmpty() },
                campusText = null,
                color = spec.colorKey,
                updatedAt = now,
            )
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
        return code
    }

    suspend fun deletePlan(semesterId: Int, code: String) =
        db.courseSessionDao().deleteByCourseKey(courseKeyOf(semesterId, code))

    /**
     * 这个日程和哪些教务课程撞了（同一天、周次有交集、节次有重叠）。
     * 只提醒不阻止 —— 有些"撞课"其实是自己想要的重叠安排。
     */
    suspend fun planConflicts(semesterId: Int, spec: PlanSpec): List<Session> {
        val weeks = spec.weeks.toSet()
        if (weeks.isEmpty()) return emptyList()
        // ⚠️ 只排除自定义日程：教务侧来源随学校不同（郑大 "eas"、河南理工 "hpu"），
        // 写死成某一个来源会让撞课提醒在该来源下永远查不到课（静默空跑）。
        return db.courseSessionDao().bySemester(semesterId)
            .filter { it.source != SOURCE_CUSTOM }
            .map { it.toDomain() }
            .filter {
                it.weekday == spec.weekday &&
                    it.weekIndex in weeks &&
                    it.startUnit <= spec.endUnit &&
                    spec.startUnit <= it.endUnit
            }
            .distinctBy { it.courseName to it.startUnit }
            .sortedBy { it.startUnit }
    }

    /** 已保存的学号 —— 用来推断入学年（学号前 4 位） */
    suspend fun settingsAccount(): String? = db.settingsDao().get()?.account

    // ── 课表缓存：写入与同步状态 ──────────────────────────────────────

    /**
     * 把某个来源的课表整体写入（先按来源删、再插；**不会碰 `custom` 日程**）。
     *
     * @return true = 内容与上次不同（界面上课表确实变了）；false = 只是刷新了一遍，内容一模一样
     *   —— 后者按用户要求**界面一个字都不该动**，但"获取时间"仍会更新。
     */
    suspend fun saveTimetable(
        source: String,
        semesterId: Int,
        sessions: List<Session>,
        courses: List<Course>,
        fetchedAt: Long = System.currentTimeMillis(),
        scopeKey: String? = null,
    ): Boolean {
        val hash = timetableHash(sessions)
        val existing = db.timetableSyncDao().get(source, semesterId)
        val changed = existing?.contentHash != hash
        val courseEntities = courses.map { c ->
            CourseEntity(
                key = courseKeyOf(semesterId, c.code),
                semesterId = semesterId,
                code = c.code,
                nameZh = c.nameZh,
                credits = c.credits,
                openDepartment = c.openDepartment,
                teachersCsv = c.teachers.joinToString(","),
                updatedAt = fetchedAt,
            )
        }
        val sessionEntities = sessions.map { s ->
            CourseSessionEntity(
                id = s.id,
                courseKey = courseKeyOf(semesterId, s.courseCode),
                semesterId = semesterId,
                source = source,
                courseName = s.courseName,
                weekIndex = s.weekIndex,
                weekday = s.weekday,
                startUnit = s.startUnit,
                endUnit = s.endUnit,
                date = s.date,
                startTime = s.startTime,
                endTime = s.endTime,
                    teacherName = s.teacherName,
                    roomText = s.roomText,
                    campusText = s.campusText,
                    color = s.color,
                    experiment = s.experiment,
                    updatedAt = fetchedAt,
                )
        }
        db.withTransaction {
            db.courseSessionDao().deleteBySemesterAndSource(semesterId, source)
            db.courseDao().deleteBySemester(semesterId)
            db.courseDao().upsertAll(courseEntities)
            db.courseSessionDao().upsertAll(sessionEntities)
            if (scopeKey != null) {
                // 研究生共用合成 semesterId，缓存实际 term value，避免显示选择跨学期串用。
                db.kvCacheDao().put(KvCacheEntity(timetableScopeKey(source, semesterId), scopeKey, fetchedAt, 0L))
            }
            db.timetableSyncDao().upsert(
                TimetableSyncEntity(
                    source = source,
                    semesterId = semesterId,
                    fetchedAt = fetchedAt,
                    lastError = null,
                    lastErrorAt = null,
                    contentHash = hash,
                    cacheVersion = CACHE_VERSION,
                ),
            )
        }
        return changed
    }

    /**
     * 最近一次成功抓取的时间（毫秒）；null = 从没成功过，或缓存结构版本已过时。
     *
     * 版本过时返回 null 的效果是"当作没有缓存、重新拉"，比拿旧结构渲染出错更稳。
     */
    suspend fun lastFetchedAt(source: String, semesterId: Int): Long? {
        val row = db.timetableSyncDao().get(source, semesterId) ?: return null
        if (row.cacheVersion != CACHE_VERSION) return null
        return row.fetchedAt.takeIf { it > 0L }
    }

    /** 缓存结构版本；改字段就 +1，旧缓存会被当作不存在 */
    private fun timetableHash(sessions: List<Session>): String = sessions
        .map {
            "${it.weekIndex}|${it.weekday}|${it.startUnit}|${it.endUnit}|" +
                "${it.courseName}|${it.roomText.orEmpty()}|${it.teacherName.orEmpty()}"
        }
        .sorted()
        .joinToString("\n")
        .hashCode()
        .toString()



    /** 读某个来源的缓存（返回领域模型；没有缓存就是空表） */
    suspend fun cachedSessions(source: String, semesterId: Int): List<Session> =
        db.courseSessionDao().bySemesterAndSource(semesterId, source)
            .map { it.toDomain() }.withOverrides(semesterId)


    /** 与课表缓存同生命周期的学期标识；元数据不使用通用缓存的 TTL。 */
    suspend fun timetableScope(source: String, semesterId: Int): String? =
        db.kvCacheDao().get(timetableScopeKey(source, semesterId))?.payload

    private fun timetableScopeKey(source: String, semesterId: Int) = "timetable-scope:$source:$semesterId"

    /**
     * 清掉某个来源的缓存（退出登录时用）。
     *
     * 课表是按账号抓的，退出后不该留在设备上；`custom` 日程不在此列（那是用户自己的数据）。
     */
    suspend fun clearTimetable(source: String, semesterId: Int) {
        db.withTransaction {
            db.courseSessionDao().deleteBySemesterAndSource(semesterId, source)
            db.courseDao().deleteBySemester(semesterId)
            db.timetableSyncDao().clearBySource(source)
            db.kvCacheDao().remove(timetableScopeKey(source, semesterId))
            // 显示覆盖跟课表同生命周期：按账号抓的数据退出后不该留在设备上
            if (source != SOURCE_CUSTOM) db.displayOverrideDao().deleteBySemester(semesterId)
        }
    }

    // ── 显示覆盖（issue #6 保底）：用户自定义教务课程的显示 ──────────────

    /** 某学期全部显示覆盖（详情页编辑用） */
    suspend fun displayOverrides(semesterId: Int): List<DisplayOverrideEntity> =
        db.displayOverrideDao().bySemester(semesterId)

    suspend fun conflictDisplayChoices(semesterId: Int): List<ConflictDisplayChoice> =
        readConflictDisplayChoices(displayOverrides(semesterId))

    /**
     * 冲突选择复用本地显示覆盖表；一周一条，改部分周不会清掉其他周的选择。
     * null = 恢复冲突占位。课程同步只改课表表，不触碰这些记录。
     */
    suspend fun saveConflictDisplay(block: ScheduleBlock, weeks: Set<Int>, sessionKey: String?) {
        require(block.isConflict && weeks.isNotEmpty() && weeks.all { it > 0 })
        require(sessionKey == null || block.sessions.any { it.conflictDisplayKey() == sessionKey })
        db.withTransaction {
            weeks.forEach { week ->
                val id = "${block.semesterId}|$KIND_CONFLICT|${block.conflictKey}|$week"
                if (sessionKey == null) {
                    db.displayOverrideDao().deleteById(id)
                } else {
                    db.displayOverrideDao().upsert(
                        DisplayOverrideEntity(
                            id = id, semesterId = block.semesterId, courseCode = block.conflictKey,
                            kind = KIND_CONFLICT, weekday = block.weekday, startUnit = block.startUnit,
                            weeks = week.toString(), value = sessionKey, updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 保存/清除课程名覆盖。[value] 空白 = 清除（恢复教务原名）。
     * @return 落库后的值（null = 已无覆盖）
     */
    suspend fun saveDisplayNameOverride(semesterId: Int, courseCode: String, value: String?): String? {
        val id = nameOverrideId(semesterId, courseCode)
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) {
            db.displayOverrideDao().deleteById(id)
        } else {
            db.displayOverrideDao().upsert(
                DisplayOverrideEntity(
                    id = id, semesterId = semesterId, courseCode = courseCode,
                    kind = KIND_NAME, weekday = null, startUnit = null,
                    value = v, updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return v.ifEmpty { null }
    }

    /**
     * 保存/清除某时段的教室覆盖（同一门课不同时段可不同教室）。空白 = 清除。
     *
     * [weeks] 为 null = 该时段**全部周**（保存前清场：把该时段既有的分周覆盖一并删掉，
     * 否则旧值会在没被新保存覆盖的周上阴魂不散）；非 null = 只作用这些教学周
     * （单双周换教室的场景，编辑框会按教室分组各存一条）。
     */
    suspend fun saveDisplayRoomOverride(
        semesterId: Int,
        courseCode: String,
        weekday: Int,
        startUnit: Int,
        weeks: List<Int>?,
        value: String?,
    ): String? {
        val v = value?.trim().orEmpty()
        if (weeks == null) {
            // 「全部周」是该时段的唯一真相：先清光分周条目再落这一条
            db.displayOverrideDao().deleteRoomBySlot(semesterId, courseCode, weekday, startUnit)
        } else {
            db.displayOverrideDao().deleteById(roomOverrideId(semesterId, courseCode, weekday, startUnit, null))
        }
        if (v.isNotEmpty()) {
            db.displayOverrideDao().upsert(
                DisplayOverrideEntity(
                    id = roomOverrideId(semesterId, courseCode, weekday, startUnit, weeks),
                    semesterId = semesterId, courseCode = courseCode,
                    kind = KIND_ROOM, weekday = weekday, startUnit = startUnit,
                    weeks = weeks?.joinToString(","),
                    value = v, updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return v.ifEmpty { null }
    }

    /** 清掉一门课的全部覆盖（恢复默认） */
    suspend fun clearDisplayOverrides(semesterId: Int, courseCode: String) =
        db.displayOverrideDao().deleteByCourse(semesterId, courseCode)

    private suspend fun overridesOrNull(semesterId: Int): List<DisplayOverrideEntity>? =
        runCatching { db.displayOverrideDao().bySemester(semesterId) }.getOrNull()

    private suspend fun List<Session>.withOverrides(semesterId: Int): List<Session> {
        val ovs = overridesOrNull(semesterId) ?: return this
        if (ovs.isEmpty()) return this
        return applyDisplayOverrides(this, ovs)
    }

    /** 课程名覆盖作用于 [Course.nameZh]（详情页/总览的标题） */
    private suspend fun List<Course>.withNameOverrides(semesterId: Int): List<Course> {
        val ovs = overridesOrNull(semesterId)?.filter { it.kind == KIND_NAME } ?: return this
        if (ovs.isEmpty()) return this
        val by = ovs.associate { it.courseCode to it.value }
        return map { c -> by[c.code]?.let { c.copy(nameZh = it) } ?: c }
    }

    private fun nameOverrideId(semesterId: Int, courseCode: String) =
        "$semesterId|$courseCode|$KIND_NAME"

    private fun roomOverrideId(
        semesterId: Int,
        courseCode: String,
        weekday: Int,
        startUnit: Int,
        weeks: List<Int>?,
    ) = "$semesterId|$courseCode|$KIND_ROOM|$weekday|$startUnit|" + (weeks?.joinToString(",") ?: "all")


    companion object {
        /** 用户自己加的日程（补录课 / 普通日程）；教务来源见各校仓库的 SOURCE 常量 */
        const val SOURCE_CUSTOM = "custom"

        /** 显示覆盖的类型：课程名（整门课）/ 教室（单时段） */
        const val KIND_NAME = "name"
        const val KIND_ROOM = "room"
        const val KIND_CONFLICT = "conflict"

        internal fun readConflictDisplayChoices(rows: List<DisplayOverrideEntity>): List<ConflictDisplayChoice> =
            rows.filter { it.kind == KIND_CONFLICT }.mapNotNull { row ->
                val week = row.weeks?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                ConflictDisplayChoice(row.semesterId, row.courseCode, week, row.value)
            }

        /**
         * 把显示覆盖应用到 Session 列表（纯函数，单测见 DisplayOverrideTest）。
         *
         * 名称覆盖键 = (semesterId, courseCode)，替换 courseName（网格/今天/组件全生效）；
         * 教室覆盖键 = (semesterId, courseCode, weekday, startUnit) + 周次——
         * `weeks == null` 作用该时段全部周；否则只作用 [DisplayOverrideEntity.weeks]
         * 列的教学周（单双周换教室的课拆成两条互不干扰），**精确周优先于全部周**。
         */
        fun applyDisplayOverrides(
            sessions: List<Session>,
            overrides: List<DisplayOverrideEntity>,
        ): List<Session> {
            if (overrides.isEmpty()) return sessions
            val nameBy = overrides.filter { it.kind == KIND_NAME }
                .associate { (it.semesterId to it.courseCode) to it.value }
            val roomBy = overrides.filter { it.kind == KIND_ROOM && it.weekday != null && it.startUnit != null }
                .groupBy { Triple(it.semesterId, it.courseCode, it.weekday!! to it.startUnit!!) }
            return sessions.map { s ->
                var out = s
                nameBy[s.semesterId to s.courseCode]?.let { out = out.copy(courseName = it) }
                roomBy[Triple(s.semesterId, s.courseCode, s.weekday to s.startUnit)]
                    ?.let { slotOverrides ->
                        val exact = slotOverrides.firstOrNull { parseWeeks(it.weeks)?.contains(s.weekIndex) == true }
                        val anyWeek = slotOverrides.firstOrNull { it.weeks == null }
                        (exact ?: anyWeek)?.let { out = out.copy(roomText = it.value) }
                    }
                out
            }
        }

        /** "1,3,5" → {1,3,5}；null/空/全非法 → null（= 全部周） */
        internal fun parseWeeks(csv: String?): Set<Int>? =
            csv?.takeIf { it.isNotBlank() }
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }?.toSet()

        /**
         * 课表缓存结构版本；改字段就 +1，旧缓存会被当作不存在。
         *
         * v2（2026-10-25）：研究生解析修复（一格多条 `<br/>`）会改变同一接口的解析结果，
         * 而缓存 TTL 是 24 小时——不升版本的话，升级后旧解析结果（缺周次）最长滞留一天。
         * v3（2026-10-25）：本科 course-table 改带 `hasExperiment=true`（issue #7 真修复），
         * 实验排课的 experiment 标志来自新响应——旧缓存里全是 false，必须重拉一次。
         * 升版本的效果：`lastFetchedAt` 视为无缓存 → 立即触发一次后台刷新；
         * 旧缓存仍先显示（内容哈希没变的界面纹丝不动，变了才更新）。
         */
        // v4：同课程同时间的理论/实验使用不同主键，重拉一次恢复旧版本被覆盖的记录。
        // v5：HPU 周次位串下标语义修正（下标即周次，不再 +1）。旧缓存里每门课整体后移一周、
        //     单双周翻转，必须作废重拉，否则界面会一直显示错的周次。
        const val CACHE_VERSION = 5



        fun courseKeyOf(semesterId: Int, courseCode: String) = "$semesterId:$courseCode"

        /**
         * 稳定主键：同一学期 + 同一门课 + 同一周 + 同一天 + 同一节 + **同一日期** = 同一节课。
         *
         * [date] 必须进主键：学校的**补课**可能和正常课落在同一周同一天同一节
         * （比如把周二的课补在某周周二），不含日期就会互相覆盖，只剩一条。
         * 自定义日程没有日期（按周重复），传 null 即可。
         */
        fun sessionIdOf(
            semesterId: Int,
            courseCode: String,
            weekIndex: Int,
            weekday: Int,
            startUnit: Int,
            date: String? = null,
            experiment: Boolean = false,
        ) = "$semesterId:$courseCode:$weekIndex:$weekday:$startUnit:${date.orEmpty()}" +
            if (experiment) ":experiment" else ""

        /** code 前缀记类型：C = 手动补录的课程，P = 普通日程（老数据全是 P） */
        fun newPlanCode(kind: com.zzupocket.app.domain.PlanKind = com.zzupocket.app.domain.PlanKind.EVENT): String =
            com.zzupocket.app.domain.PlanKind.codePrefix(kind) + System.currentTimeMillis().toString(36)
    }
}

// ── 实体 ↔ 领域模型 ─────────────────────────────────────────────────────


private fun SemesterEntity.toDomain() = Semester(
    id = id,
    nameZh = nameZh,
    schoolYear = schoolYear,
    startDate = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    endDate = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    weekStartOnSunday = weekStartOnSunday,
    weekIndices = weekIndicesCsv.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted(),
    isCurrent = isCurrent,
)

private fun CourseSessionEntity.toDomain() = Session(
    id = id,
    semesterId = semesterId,
    source = source,
    courseCode = courseKey.substringAfterLast(':'),
    courseName = courseName,
    weekIndex = weekIndex,
    weekday = weekday,
    startUnit = startUnit,
    endUnit = endUnit,
    date = date,
    startTime = startTime,
    endTime = endTime,
    teacherName = teacherName,
    roomText = roomText,
    campusText = campusText,
    color = color,
    experiment = experiment,
)

private fun PeriodEntity.toDomain() = Period(
    indexNo = indexNo,
    nameZh = nameZh,
    startTime = startTime,
    endTime = endTime,
    dayPart = dayPart,
    segmentIndex = segmentIndex,
)
