package com.zzupocket.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.Enrollment
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.core.WeekMath
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.prefs.UiPrefsStore
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.ConflictDisplayChoice
import com.zzupocket.app.domain.ScheduleBlock
import com.zzupocket.app.domain.conflictDisplayKey
import com.zzupocket.app.domain.scheduleBlocks
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Plan
import com.zzupocket.app.domain.PlanSpec
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.WeekAlignment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log
import com.zzupocket.app.ui.widget.Widgets
import java.time.LocalDate

data class ScheduleUiState(
    val started: Boolean = false,
    val loading: Boolean = false,
    val loggedIn: Boolean = false,
    /**
     * 后台同步进行中（缓存先行模式下"点刷新"不再整页转圈，必须给一个轻反馈，
     * 否则内容没变化时用户以为没点成功——2026-11-16 用户实测）。
     */
    val syncing: Boolean = false,

    // ── 教学周参数（本科/研究生共用；按学期存）─────────────────────────
    /** 第 1 周的起始日；null = 还没定（界面显示"待设置"） */
    val weekStart: LocalDate? = null,
    /** 每周起始日：周一/周日 */
    val weekStartDay: com.zzupocket.app.core.WeekStartDay = com.zzupocket.app.core.WeekStartDay.MONDAY,
    /** 本学期总周次（用户可改；null = 用数据里的最大周） */
    val weekTotal: Int? = null,
    /** 周次参数是否已确认（教务校历或用户设置 ⇒ true；纯推断 ⇒ false） */
    val weekConfirmed: Boolean = false,
    /** 周次参数来源，界面据此标注 */
    val weekSource: com.zzupocket.app.core.WeekSource = com.zzupocket.app.core.WeekSource.INFERRED,

    // ── 缓存状态（"先显示缓存、再后台刷新"）─────────────────────────────
    /** 当前课表数据的获取时间（毫秒）；null = 还没成功取过 */
    val dataFetchedAt: Long? = null,
    /**
     * 缓存提示：**一行小灰字**，null = 什么都不显示。
     *
     * 规则（用户拍定）：刷新成功且内容一样 → 不提示；取不到 → 才显示"显示的是 HH:MM 的课表"，
     * 而不是红色错误——红色错误会让人以为是自己账号出了问题，历史上因此反复重登。
     */
    val cacheNotice: String? = null,
    /** true = 这条提示是"登录过期"，点它应该去重新登录而不是重试 */
    val cacheNoticeNeedsLogin: Boolean = false,
    val error: String? = null,
    /** 提示（不是错误），比如过滤后没有学期而回退到全部 */
    val notice: String? = null,
    /** 数据库里的全部学期，未经过滤；改入学年时用它重新过滤 */
    val allSemesters: List<Semester> = emptyList(),
    val semesters: List<Semester> = emptyList(),
    val selectedSemester: Semester? = null,
    val weekIndex: Int = 1,
    /**
     * [weekIndex] 是**按哪一天**算出来的。
     *
     * 光存周次是不够的：跨天（尤其跨周）之后必须重新对准当前周，而判断"该不该重算"
     * 只能靠这个日期。用户手动翻周不会改它 —— 同一天内翻的周是他自己的选择，
     * 隔天则应该自动回到当前周（真机问题：今天页第 4 周、课表页停在第 3 周）。
     * null = 还没解析出周次（课表页据此先不挂周次翻页器，见 WeekPager 的注释）。
     */
    val weekResolvedOn: LocalDate? = null,
    /** 整学期全部课；翻周时在内存里过滤，不闪空白也不发请求 */
    val allSessions: List<Session> = emptyList(),
    val periods: List<Period> = emptyList(),
    val courses: List<Course> = emptyList(),
    /** 本学期自己加的日程 */
    val plans: List<Plan> = emptyList(),
    /** 日程编辑框：null 且 [planEditorOpen] 为 true = 新建 */
    val editingPlan: Plan? = null,
    val planEditorOpen: Boolean = false,
    /** 撞课确认：待保存的内容 + 撞到的课名 */
    val pendingPlan: PlanSpec? = null,
    val pendingConflicts: List<String> = emptyList(),
    /** 补录保存/删除成功后的一次性确认（桌面小组件刷新已同步发生，无感失败也有开屏兜底） */
    val planNotice: String? = null,
    val detailCourse: Course? = null,
    /** detailCourse 的显示覆盖（名称 + 各时段教室）；null = 没查到/没有 */
    val detailOverrides: DisplayOverridesView? = null,
    val conflictChoices: List<ConflictDisplayChoice> = emptyList(),
    val detailConflict: ConflictDetails? = null,
    val conflictSaving: Boolean = false,
    val conflictError: String? = null,
    val conflictNotice: String? = null,
) {
    /** 该学期有哪些教学周（升序） */
    val weeks: List<Int> get() = selectedSemester?.weekIndices.orEmpty()
    val weekCount: Int get() = weeks.size

    fun sessionsOf(week: Int): List<Session> = allSessions.filter { it.weekIndex == week }

}

/** 详情页看到的显示覆盖（issue #6 保底）：名称覆盖 + 各时段教室覆盖。
 *  rooms 键 = "weekday|startUnit"（全部周）或 "weekday|startUnit|1,3,5"（分周，单双周换教室时按教室分组）。 */
data class DisplayOverridesView(
    val name: String? = null,
    val rooms: Map<String, String> = emptyMap(),
) {
    val any: Boolean get() = name != null || rooms.isNotEmpty()
}

data class ConflictDetails(
    val block: ScheduleBlock,
    val matchingBlocks: List<ScheduleBlock>,
) {
    val weeks: List<Int> get() = matchingBlocks.map { it.weekIndex }.distinct().sorted()
    val editable: Boolean
        get() = true
}

class ScheduleViewModel : ViewModel() {

    private val repo: ScheduleRepository = AppGraph.scheduleRepository
    private val uiPrefs: UiPrefsStore = AppGraph.uiPrefsStore

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init {
        // 0.31.6 及以前那枚"自动推断"的周次锚点会带着 +1 的错误，进 App 时先清理一次
        viewModelScope.launch { runCatching { uiPrefs.migrateLegacyWeekAnchor() } }
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch { observeEnrollmentYear() }
        // 作息时间切换后立刻用新节次时刻重绘，不用退出重进课表
        viewModelScope.launch { observePeriodMode() }
    }

    // ── HPU 分支：登录态与远程同步 ──────────────────────────────────────

    /** 当前学校版本是否"已登录"（河南理工看统一认证会话 Cookie） */
    private fun currentLoggedIn(): Boolean = AppGraph.hpuSessionStore.hasSession()

    /** 远程同步课表（EAMS HTML → 解析 → 落库）。返回错误文案，null = 成功 */
    private suspend fun syncRemote(): String? =
        when (val r = AppGraph.hpuScheduleRepository.sync()) {
            is com.zzupocket.app.data.repo.HpuScheduleRepository.SyncResult.Success -> null
            is com.zzupocket.app.data.repo.HpuScheduleRepository.SyncResult.NotLoggedIn -> "未登录，请先在统一认证登录"
            is com.zzupocket.app.data.repo.HpuScheduleRepository.SyncResult.Failure -> r.message
        }

    /** 拉课表。sync() 自己抓当前学期，不需要外部传学期 id。 */
    private suspend fun syncCourseTable() {
        when (val r = AppGraph.hpuScheduleRepository.sync()) {
            is com.zzupocket.app.data.repo.HpuScheduleRepository.SyncResult.Failure ->
                _state.update { it.copy(error = r.message) }
            else -> Unit
        }
    }

    /** 课表同步的兜底超时：EAMS 抓取 + 落库的预算 */
    private val SYNC_TIMEOUT_MS = 30_000L

    /** `14:20` 这种口径的"获取时间" */
    private fun hhmm(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    /** 本科课表缓存有效期：24 小时（学期内基本不变，调课也罕见） */
    private val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    fun onResume() {
        // 从「课程表设置」改完周次参数回来：先把新参数读进来（纯本地，不联网）
        viewModelScope.launch { reloadWeekSettings() }
        if (!_state.value.started) {
            viewModelScope.launch { bootstrap() }
        } else if (_state.value.loggedIn != currentLoggedIn()) {
            viewModelScope.launch { bootstrap() }
        } else {
            // 切回课表页时不重建数据，但要**对准当前周**：跨天/跨周之后，
            // 停在旧周上会让用户看到上周的课（WeekAlignment 里有完整说明）。
            resyncWeekIfDayChanged()
        }
    }

    /**
     * 隔天就把显示周次重新对准当前周；同一天内用户手动翻过的周保留。
     *
     * 纯本地计算（`resolveCurrentWeek` 不发请求），所以直接在 resume 里同步调用。
     */
    private fun resyncWeekIfDayChanged() {
        val s = _state.value
        val semester = s.selectedSemester ?: return
        val today = LocalDate.now()
        if (!WeekAlignment.needsRealign(s.weekResolvedOn, today)) return
        val week = WeekAlignment.realigned(
            resolvedOn = s.weekResolvedOn,
            today = today,
            storedWeek = s.weekIndex,
            currentWeek = repo.resolveCurrentWeek(semester, today),
            firstWeek = semester.firstWeek,
        )
        _state.update { it.copy(weekIndex = week, weekResolvedOn = today) }
    }

    private suspend fun bootstrap() {
        // ⚠️ loggedIn 必须在**任何网络请求之前**就写进状态，否则会骗用户去反复重登：
        // 以前这里是"先 refreshSemesters()，直到最后才把 loggedIn 写进状态"，于是教务回 401/403
        // 时异常被 catch 掉，loggedIn 就停在默认的 false —— 界面同时显示红色「教务登录态已失效」
        // 与「登录后查看课表 + 登录按钮」（那两处分别由 error 与 !loggedIn 决定渲染），
        // 用户重登一次、界面一模一样，形成死循环（2026-10-14 用户报告）。
        // 本地令牌有效就按"已登录"渲染；教务被拒是另一回事，交给 error 说明。
        val loggedIn = currentLoggedIn()
        _state.update { it.copy(started = true, loading = true, error = null, loggedIn = loggedIn) }
        try {
            var all = repo.semesters()
            if (all.isEmpty() && loggedIn) {
                // 学期列表拉不到不再炸整页（#15 复发：蜂窝下 refreshSemesters 抛
                // "网络不可达"，外层 catch 把整页打成红错+空白 —— 本地明明有整学期缓存）。
                // 失败就空着继续走：loadSemester 自己有"缓存优先 + 灰字"兜底。
                val syncError = syncRemote()
                all = repo.semesters()
                // ⚠️ 但**本地也是空的**（新装/清数据）时不能再吞：selected 会是 null
                // → loading 永远 true → "正在获取课表…"无限转圈（2026-11-12 网关拦截
                // 期间新用户实测）。把真实原因亮出来。
                if (all.isEmpty()) {
                    syncError?.let { msg ->
                        _state.update {
                            it.copy(loading = false, error = msg, loggedIn = loggedIn)
                        }
                        return
                    }
                }
            }
            val (visible, notice) = applyYearFilter(all)
            val selected = visible.firstOrNull { it.isCurrent } ?: visible.firstOrNull()
            _state.update {
                it.copy(
                    loading = selected == null,
                    loggedIn = loggedIn,
                    error = null,
                    notice = notice
                        ?: if (selected == null && loggedIn) "还没同步过学期列表，联网后重进课表页会自动同步" else null,
                    allSemesters = all,
                    semesters = visible,
                    selectedSemester = selected,
                )
            }
            if (selected != null) loadSemester(selected, loggedIn, forceSync = false)
        } catch (e: Throwable) {
            // 走到这里说明本地库读取都失败了（不是网络）：真异常才红条
            _state.update { it.copy(loading = false, error = e.toUserMessage()) }
        }
    }

    /**
     * 只保留入学年及以后的学期。
     *
     * 入学年**只认手动设置**（设置页那个输入框），不再拿学号前 4 位自动推断：
     * 推断错的时候会悄悄藏掉学期，用户完全找不到原因。
     * 没设置就不过滤。
     */
    private suspend fun applyYearFilter(all: List<Semester>): Pair<List<Semester>, String?> {
        val year = uiPrefs.flow.first().enrollmentYearOverride ?: return all to null
        // 手动填了入学年就彻底按它过滤：解析不出年份的学期也一起藏掉，
        // 否则永远会有漏网的老学期让人以为设置没生效。
        val filtered = Enrollment.filterSince(all, year, hideUnknown = true)
        return if (filtered.isEmpty()) {
            all to "按 $year 年及以后过滤后没有学期，已显示全部"
        } else {
            filtered to null
        }
    }

    /** 入学年改动后立刻重算，不用退出重进课表 */
    private suspend fun observeEnrollmentYear() {
        uiPrefs.flow
            .map { it.enrollmentYearOverride }
            .distinctUntilChanged()
            .drop(1) // 首帧由 bootstrap 负责，避免和它抢着改状态
            .collect { reapplyYearFilter() }
    }

    /** 作息模式（自动/春/秋）改动后重读节次时刻并刷新依赖它的时间显示 */
    private suspend fun observePeriodMode() {
        uiPrefs.flow
            .map { it.periodMode }
            .distinctUntilChanged()
            .drop(1) // 首帧由 bootstrap 负责
            .collect {
                val periods = repo.periods()
                _state.update { s -> s.copy(periods = periods) }
                // 让"今天/下一节"等卡片按新时刻重算（复用已有的本地重绘入口）
                runCatching { renderLocal(_state.value.selectedSemester ?: return@collect, null, keepNotice = true) }
            }
    }

    private suspend fun reapplyYearFilter() {
        val all = _state.value.allSemesters
        if (all.isEmpty()) return
        val (visible, notice) = applyYearFilter(all)
        val currentId = _state.value.selectedSemester?.id
        val selected = visible.firstOrNull { it.id == currentId }
            ?: visible.firstOrNull { it.isCurrent }
            ?: visible.firstOrNull()
        _state.update { it.copy(semesters = visible, notice = notice, selectedSemester = selected) }
        if (selected != null && selected.id != currentId) {
            _state.update { it.copy(loading = true) }
            loadSemester(selected, currentLoggedIn(), forceSync = false)
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** 载入某学期的数据；没有缓存、缓存过期或强制刷新时才联网 */
    /**
     * 加载某学期的课表——**缓存先上屏，同步放后台**（stale-while-revalidate）。
     *
     * 为什么这么改：数据一直就在本地库里，旧实现却把网络同步挡在渲染前面——
     * 统一验证网关黑洞期每笔请求要吃满 15~30s 超时，"联网后半天才显示课表"
     * 就是这么来的（2026-11-15 实测）。现在进页毫秒级先画缓存（过期就配一行
     * 灰字），同步在后台静默跑，完成后再刷新画面。
     *
     * 例外：**本地完全没有数据**（新装/清数据）时必须同步才能画，失败直接报错
     * （2026-11-12 修的"无限转圈"保持不变）。
     */
    private suspend fun loadSemester(semester: Semester, loggedIn: Boolean, forceSync: Boolean) {
        val fetchedAt = runCatching {
            repo.lastFetchedAt(com.zzupocket.app.data.repo.HpuScheduleRepository.SOURCE, semester.id)
        }.getOrNull()
        val stale = com.zzupocket.app.core.SyncPolicy.shouldRefresh(
            now = System.currentTimeMillis(),
            lastFetchedAt = fetchedAt,
            ttlMs = CACHE_TTL_MS,
        )
        val hasData = repo.hasCourseData(semester.id)

        // ① 缓存立即上屏（过期就配一行灰字，点击走 refresh()）
        val staleNotice = when {
            !hasData -> null
            fetchedAt == null -> "暂时连不上教务系统 · 点此重试"
            stale -> "显示的是 ${hhmm(fetchedAt)} 的课表 · 点此重试"
            else -> null
        }
        renderLocal(semester, staleNotice, needsLogin = _state.value.cacheNoticeNeedsLogin)

        val needSync = loggedIn && (forceSync || !hasData || stale)
        if (!needSync) return

        // ② 本地无数据：同步必须在渲染前完成（否则画不出来），失败报错
        if (!hasData) {
            try {
                kotlinx.coroutines.withTimeout(SYNC_TIMEOUT_MS) {
                    syncCourseTable()
                }
            } catch (e: Throwable) {
                // 用户离开页面导致的协程取消不是"刷新失败"，原样上抛让协程正常收尾
                if (e is kotlinx.coroutines.CancellationException &&
                    e !is kotlinx.coroutines.TimeoutCancellationException
                ) {
                    throw e
                }
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
                return
            }

            renderLocal(semester, null)
            return
        }

        // ③ 有缓存：后台静默刷新，不阻塞首屏；完成后若还停留在这个学期，静默更新画面
        viewModelScope.launch {
            var failure: Throwable? = null
            _state.update { it.copy(syncing = true) }
            try {
                kotlinx.coroutines.withTimeout(SYNC_TIMEOUT_MS) {
                    syncCourseTable()
                }

            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException &&
                    e !is kotlinx.coroutines.TimeoutCancellationException
                ) {
                    throw e
                }
                failure = e
            } finally {
                _state.update { it.copy(syncing = false) }
            }
            // 用户可能已切走学期（viewModelScope 取消会自动带走这里，这里只防切学期）
            if (_state.value.selectedSemester?.id != semester.id) return@launch
            renderLocal(
                semester,
                // 失败时按真实原因给提示：网关拦截/登录失效不能被写成"连不上教务系统"
                // （2026-11-16 用户实测：网关可达、同步成功，旧提示却清不掉；反向也要避免误报）
                cacheNotice = if (failure != null) noticeForSyncFailure(failure, staleNotice) else null,
                needsLogin = failure is com.zzupocket.app.core.AppError.ReLoginRequired,
            )
        }
    }

    /** 后台同步失败 → 一行准确的灰字提示（null 表示让 renderLocal 清掉提示） */
    private fun noticeForSyncFailure(e: Throwable, staleNotice: String?): String = when (e) {
        is com.zzupocket.app.core.AppError.ZeroTrustAuthRequired ->
            "学校验证网关要求认证 · 点此重试"
        is com.zzupocket.app.core.AppError.ReLoginRequired ->
            "登录已失效 · 重新登录"
        is com.zzupocket.app.core.AppError.Network,
        is kotlinx.coroutines.TimeoutCancellationException ->
            staleNotice ?: "暂时连不上教务系统 · 点此重试"
        else -> "教务系统暂时异常 · 点此重试"
    }

    /**
     * 只读本地库并渲染一帧课表（周期/周次/课程/计划全部来自本地，**无教务请求**）。
     *
     * [cacheNotice] 的语义由 [keepNotice] 决定：
     *  - keepNotice=true  → 保留现有提示（不清掉之前的失败原因）
     *  - keepNotice=false → 用 [cacheNotice] 覆盖；**传 null = 清掉提示**（同步成功后清旧提示靠它）
     * ⚠️ 曾经写成 `cacheNotice ?: it.cacheNotice`：null 被当成"保留"，导致同步成功
     * 也清不掉"连不上教务系统"的旧提示（2026-11-16 用户实测：网关可达、同步一直在成功，
     * 界面却一直挂着失败提示）。
     */
    private suspend fun renderLocal(
        semester: Semester,
        cacheNotice: String?,
        keepNotice: Boolean = false,
        needsLogin: Boolean = false,
    ) {
        val periods = repo.periods()
        val today = LocalDate.now()
        // 周次参数：按学期存；教务给了学期起始日就直接当权威值填进去（不需要用户确认）
        val settings = resolveWeekSettings(
            semesterId = semester.id,
            calendarStart = semester.startDate,
            dataWeeks = semester.weekIndices,
            today = today,
        )
        val week = if (settings.weekOneStart != null) {
            WeekMath
                .weekIndexAt(settings.weekOneStart, today)
                .coerceIn(semester.firstWeek ?: 1, semester.weekIndices.maxOrNull() ?: Int.MAX_VALUE)
        } else {
            WeekAlignment.currentWeek(
                currentWeek = repo.resolveCurrentWeek(semester, today),
                firstWeek = semester.firstWeek,
            )
        }
        val sessions = repo.sessionsOfSemester(semester.id)
        val courses = repo.courses(semester.id)
        val plans = repo.customPlans(semester.id)
        val choices = repo.conflictDisplayChoices(semester.id)
        _state.update {
            it.copy(
                loading = false,
                selectedSemester = semester,
                periods = periods,
                weekIndex = week,
                // 记下"这个周次是按今天算的"，resume 时才知道要不要重算
                weekResolvedOn = today,
                allSessions = sessions,
                courses = courses,
                plans = plans,
                conflictChoices = it.conflictChoices.filterNot { choice -> choice.semesterId == semester.id } + choices,
                weekStart = settings.weekOneStart,
                weekStartDay = settings.startDay,
                weekTotal = settings.totalWeeks,
                weekConfirmed = settings.confirmed,
                weekSource = settings.source,
                dataFetchedAt = runCatching {
                    repo.lastFetchedAt(com.zzupocket.app.data.repo.HpuScheduleRepository.SOURCE, semester.id)
                }.getOrNull(),
                // 传入 null = 保留现有提示（后台刷新成功清掉过期提示正是靠它）
                cacheNotice = if (keepNotice) it.cacheNotice else cacheNotice,
                // 「登录失效」的提示点了要去登录页；其他失败点了才重试（否则点一次还是同样失败）
                cacheNoticeNeedsLogin = if (keepNotice) {
                    it.cacheNoticeNeedsLogin
                } else {
                    cacheNotice != null && needsLogin
                },
            )
        }
    }

    /**
     * 取某学期的周次参数，必要时自动填一次。
     *
     * 填充顺序（用户拍定的三档）：**教务校历 → 已有设置 → 推断**。自动值只填空白项；
     * 教务给的值视为权威（`confirmed = true`），推断出来的值必须标"待确认"。
     */
    private suspend fun resolveWeekSettings(
        semesterId: Int,
        calendarStart: LocalDate?,
        dataWeeks: List<Int>,
        today: LocalDate,
    ): com.zzupocket.app.core.WeekSettings {
        val saved = runCatching { uiPrefs.weekSettings(semesterId) }
            .getOrDefault(com.zzupocket.app.core.WeekSettings())
        if (saved.weekOneStart != null) return saved
        if (calendarStart == null) return saved
        val start = WeekMath.startOfWeek(calendarStart, saved.startDay)
        val filled = saved.copy(
            weekOneStart = start,
            totalWeeks = saved.totalWeeks ?: dataWeeks.maxOrNull(),
            confirmed = true,
            source = com.zzupocket.app.core.WeekSource.CALENDAR,
        )
        runCatching { uiPrefs.saveWeekSettings(semesterId, filled) }
        return filled
    }

    // ── 自定义日程 ──────────────────────────────────────────────────────

    /** 打开编辑框。[plan] 为空 = 新建 */
    fun openPlanEditor(plan: Plan? = null) {
        if (_state.value.selectedSemester == null) {
            _state.update { it.copy(notice = "还没有选中学期，先同步一次课表") }
            return
        }
        _state.update { it.copy(planEditorOpen = true, editingPlan = plan) }
    }

    fun closePlanEditor() = _state.update {
        it.copy(planEditorOpen = false, editingPlan = null, pendingPlan = null, pendingConflicts = emptyList())
    }

    /** 保存前先看有没有和上课时间撞车；撞了就等用户确认，不撞直接存 */
    fun savePlan(spec: PlanSpec) {
        val semesterId = _state.value.selectedSemester?.id ?: return
        viewModelScope.launch {
            val conflicts = runCatching { repo.planConflicts(semesterId, spec) }.getOrDefault(emptyList())
            if (conflicts.isNotEmpty()) {
                _state.update {
                    it.copy(
                        pendingPlan = spec,
                        pendingConflicts = conflicts.map { s ->
                            "${ScheduleText.weekdayName(s.weekday)}第 ${s.startUnit}-${s.endUnit} 节《${s.courseName}》"
                        },
                    )
                }
                return@launch
            }
            writePlan(spec)
        }
    }

    /** 用户确认"仍然保存" */
    fun confirmPendingPlan() {
        val spec = _state.value.pendingPlan ?: return
        viewModelScope.launch { writePlan(spec) }
    }

    fun dismissPendingPlan() = _state.update { it.copy(pendingPlan = null, pendingConflicts = emptyList()) }

    private suspend fun writePlan(spec: PlanSpec) {
        val semester = _state.value.selectedSemester ?: return
        var saved = false
        runCatching { repo.savePlan(spec) }
            .onSuccess { saved = true }
            .onFailure { e -> _state.update { it.copy(error = e.toUserMessage()) } }
        closePlanEditor()
        if (saved) {
            // 桌面小组件读的就是这套 Session 数据（custom 源一并提供），
            // 但它的重画只在「打开 App / 课程边界」发生——补录后立刻去看桌面会停在旧画面，
            // 所以在保存点主动推一遍（无感，失败无声，开屏兜底仍在）。
            AppGraph.appContextOrNull()?.let { ctx ->
                runCatching { Widgets.refreshAll(ctx) }
                    .onFailure { Log.w("ScheduleViewModel", "补录后刷新小组件失败", it) }
            }
            _state.update {
                it.copy(planNotice = "已保存到课表 · 「首页」与桌面小组件已同步")
            }
        }
        loadSemester(semester, currentLoggedIn(), forceSync = false)
    }

    fun deletePlan(code: String) {
        val semester = _state.value.selectedSemester ?: return
        viewModelScope.launch {
            var removed = false
            runCatching { repo.deletePlan(semester.id, code) }
                .onSuccess { removed = true }
                .onFailure { e -> _state.update { it.copy(error = e.toUserMessage()) } }
            closePlanEditor()
            if (removed) {
                AppGraph.appContextOrNull()?.let { ctx ->
                    runCatching { Widgets.refreshAll(ctx) }
                        .onFailure { Log.w("ScheduleViewModel", "删除补录后刷新小组件失败", it) }
                }
                _state.update {
                    it.copy(planNotice = "已从课表移除 · 桌面小组件已同步")
                }
            }
            loadSemester(semester, currentLoggedIn(), forceSync = false)
        }
    }

    /** 补录确认提示的关闭（展示几秒后由界面收起） */
    fun clearPlanNotice() = _state.update { it.copy(planNotice = null) }

    /** 点课程块：自定义日程开编辑框，教务课程开详情 */
    fun onSessionClick(session: Session) {
        if (session.source == ScheduleRepository.SOURCE_CUSTOM) {
            openPlanEditor(_state.value.plans.firstOrNull { it.code == session.courseCode })
        } else {
            val pool = _state.value.courses
            pool.firstOrNull { it.code == session.courseCode }?.let(::openDetail)
        }
    }

    /** 选定显示课程后仍保留冲突入口，其他课程始终可以从详情顶部找到。 */
    fun onBlockClick(block: ScheduleBlock) {
        if (!block.isConflict) {
            onSessionClick(block.displaySession)
            return
        }
        showDetail(courseForSession(block.displaySession), conflictDetails(block, _state.value))
    }

    fun selectConflictCourse(session: Session) {
        val conflict = _state.value.detailConflict ?: return
        if (_state.value.conflictSaving || session !in conflict.block.sessions) return
        showDetail(courseForSession(session), conflict)
    }

    private fun courseForSession(session: Session): Course {
        val s = _state.value
        val pool = s.courses
        if (session.source != ScheduleRepository.SOURCE_CUSTOM) {
            pool.firstOrNull { it.semesterId == session.semesterId && it.code == session.courseCode }?.let { return it }
        }
        val all = s.allSessions
        val rows = all.filter { it.semesterId == session.semesterId && it.source == session.source && it.courseCode == session.courseCode }
        return Course(
            key = "${session.semesterId}:${session.courseCode}", semesterId = session.semesterId,
            code = session.courseCode, nameZh = session.courseName, credits = 0.0, openDepartment = null,
            teachers = rows.mapNotNull { it.teacherName }.distinct(), sessions = rows.ifEmpty { listOf(session) },
        )
    }

    private fun conflictDetails(block: ScheduleBlock, state: ScheduleUiState): ConflictDetails {
        val all = state.allSessions
        val matching = scheduleBlocks(all, state.conflictChoices, scopeKey = block.scopeKey)
            .filter { it.isConflict && it.conflictKey == block.conflictKey }
        return ConflictDetails(
            block = matching.firstOrNull { it.weekIndex == block.weekIndex } ?: block,
            matchingBlocks = matching.ifEmpty { listOf(block) },
        )
    }

    fun saveConflictDisplay(sessionKey: String?, weeks: Set<Int>) {
        val detail = _state.value.detailConflict ?: return
        if (_state.value.conflictSaving || !detail.editable || weeks.isEmpty() || !detail.weeks.containsAll(weeks)) return
        if (sessionKey != null && detail.block.sessions.none { it.conflictDisplayKey() == sessionKey }) return
        _state.update { it.copy(conflictSaving = true, conflictError = null, conflictNotice = null) }
        viewModelScope.launch {
            try {
                repo.saveConflictDisplay(detail.block, weeks, sessionKey)
                val choices = repo.conflictDisplayChoices(detail.block.semesterId)
                _state.update { current ->
                    val next = current.copy(
                        conflictChoices = current.conflictChoices.filterNot { it.semesterId == detail.block.semesterId } + choices,
                    )
                    if (current.detailConflict?.block?.conflictKey == detail.block.conflictKey) {
                        next.copy(
                            detailConflict = conflictDetails(detail.block, next), conflictSaving = false,
                            conflictNotice = "已保存第 ${ScheduleText.compressWeeks(weeks.sorted())}的显示 · 仅保存在本机",
                        )
                    } else next.copy(conflictSaving = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(conflictSaving = false, conflictError = e.toUserMessage()) }
            }
        }
    }

    fun selectSemester(id: Int) {
        val semester = _state.value.semesters.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            // 换学期时把"周次是按哪天算的"一并清掉：新课表还没解析出周次之前，
            // 课表页会先不挂周次翻页器，避免拿旧学期的周次当初始页
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    selectedSemester = semester,
                    weekResolvedOn = null,
                )
            }
            loadSemester(semester, currentLoggedIn(), forceSync = false)
        }
    }

    /** 切换教学周：纯本地，不发请求 */
    fun goToWeek(week: Int) {
        if (week !in _state.value.weeks) return
        _state.update { it.copy(weekIndex = week) }
    }

    /**
     * 设置「当前周次」—— 等价于反推开学日期，并把这次修改记为**用户设置**（已确认）。
     *
     * 教务接口不返回当前第几周时必须靠它裁决（比如周五那周其实是第 8 周，直接改这里最直观）。
     */
    fun setCurrentWeek(week: Int) {
        val s = _state.value
        val upper = s.weekTotal ?: s.weeks.lastOrNull() ?: week
        if (week < 1 || week > maxOf(upper, week)) return
        val today = LocalDate.now()
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(
                weekOneStart = WeekMath.weekOneStart(today, week, s.weekStartDay),
                confirmed = true,
                source = com.zzupocket.app.core.WeekSource.USER,
            ),
            week = week,
            today = today,
        )
    }


    /** 设置「本学期总周次」；只影响显示范围，不删数据 */
    fun setWeekTotal(total: Int?) {
        val s = _state.value
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(totalWeeks = total?.coerceIn(1, 30)),
            week = s.weekIndex,
            today = LocalDate.now(),
        )
    }

    /** 设置「每周起始日」（周一/周日） */
    fun setWeekStartDay(day: com.zzupocket.app.core.WeekStartDay) {
        val s = _state.value
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(
                startDay = day,
                weekOneStart = s.weekStart?.let { WeekMath.startOfWeek(it, day) },
            ),
            week = s.weekIndex,
            today = LocalDate.now(),
        )
    }


    /** 当前正在看的学期在设置里的 id */
    private fun weekSemesterId(): Int? = _state.value.selectedSemester?.id

    private fun ScheduleUiState.weekSettingsSnapshot() = com.zzupocket.app.core.WeekSettings(
        weekOneStart = weekStart,
        startDay = weekStartDay,
        totalWeeks = weekTotal,
        confirmed = weekConfirmed,
        source = weekSource,
    )

    /** 写设置 + 立刻按新基准重算当前周次（不重新联网） */
    private fun applyWeekSettings(
        settings: com.zzupocket.app.core.WeekSettings,
        week: Int,
        today: LocalDate,
    ) {
        val s = _state.value
        val bounded = week.coerceIn(1, settings.totalWeeks ?: maxOf(week, 1))
        _state.update {
            it.copy(
                weekStart = settings.weekOneStart,
                weekStartDay = settings.startDay,
                weekTotal = settings.totalWeeks,
                weekConfirmed = settings.confirmed,
                weekSource = settings.source,
                weekIndex = bounded,
                weekResolvedOn = today,
                notice = null,
            )
        }
        val id = weekSemesterId() ?: return
        viewModelScope.launch { runCatching { uiPrefs.saveWeekSettings(id, settings) } }
    }

    /**
     * 从设置页回来时重新读周次参数（纯本地，不联网）。
     */
    private suspend fun reloadWeekSettings() {
        val id = weekSemesterId() ?: return
        val st = runCatching { uiPrefs.weekSettings(id) }.getOrNull() ?: return
        val start = st.weekOneStart ?: return
        val today = LocalDate.now()
        val week = WeekMath.weekIndexAt(start, today).coerceIn(1, st.totalWeeks ?: Int.MAX_VALUE)
        _state.update {
            it.copy(
                weekStart = start,
                weekStartDay = st.startDay,
                weekTotal = st.totalWeeks,
                weekConfirmed = st.confirmed,
                weekSource = st.source,
                weekIndex = week,
                weekResolvedOn = today,
                notice = if (st.confirmed) null else "周次基准待确认：去「课程表设置」确认一下开学日期",
            )
        }
    }

    fun refresh() {
        val semester = _state.value.selectedSemester
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, syncing = true) }
            try {
                if (!currentLoggedIn()) {
                    _state.update { it.copy(loading = false, error = "未登录，无法刷新课表") }
                    return@launch
                }
                // ① 先把缓存画出来（loadSemester 渲染本地 + 同步放后台）：
                // 旧顺序是学期列表网络请求在前、loadSemester 在后，验证网关黑洞期光第一笔
                // 就吃满 15s 连接超时，期间整页 spinner 毫无变化——点刷新没反应
                // （2026-11-15 用户实测）就是这么来的。
                val cached = repo.semesters()
                val cachedSel = semester
                    ?: cached.firstOrNull { it.isCurrent }
                    ?: cached.firstOrNull()
                if (cachedSel != null) loadSemester(cachedSel, true, forceSync = true)

            // ② 学期列表刷新：结果只影响学期清单，不影响首屏课表（缓存优先，失败静默）
            syncRemote()
            val all = repo.semesters()
            val (visible, notice) = applyYearFilter(all)
            val cur = _state.value.selectedSemester
            val sel = visible.firstOrNull { it.id == cur?.id } ?: visible.firstOrNull()
            _state.update {
                it.copy(
                    allSemesters = all,
                    semesters = visible,
                    notice = notice,
                    selectedSemester = sel,
                )
            }

            // ③ 兜底：全新账号（本地没有任何学期）必须同步学期才能加载；
            //    学期清单刷新后当前学期变了（罕见）也要补载
            when {
                sel == null && visible.isEmpty() ->
                    _state.update {
                        it.copy(loading = false, error = "暂时获取不到学期列表，请稍后重试")
                    }
                sel != null && sel.id != cachedSel?.id ->
                    loadSemester(sel, true, forceSync = false)
            }
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    fun openDetail(course: Course) = showDetail(course, null)

    private fun showDetail(course: Course, conflict: ConflictDetails?) {
        viewModelScope.launch {
            val ovs = runCatching {
                repo.displayOverrides(course.semesterId).filter { it.courseCode == course.code }
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    detailCourse = course,
                    detailConflict = conflict,
                    conflictError = null,
                    conflictNotice = null,
                    detailOverrides = DisplayOverridesView(
                        name = ovs.firstOrNull { it.kind == ScheduleRepository.KIND_NAME }?.value,
                        rooms = ovs
                            .filter { it.kind == ScheduleRepository.KIND_ROOM && it.weekday != null && it.startUnit != null }
                            .associate {
                                "${it.weekday}|${it.startUnit}" +
                                    (it.weeks?.takeIf { w -> w.isNotBlank() }?.let { w -> "|$w" } ?: "") to it.value
                            },
                    ),
                )
            }
        }
    }

    fun closeDetail() = _state.update {
        it.copy(detailCourse = null, detailOverrides = null, detailConflict = null, conflictError = null, conflictNotice = null)
    }

    /**
     * 保存显示覆盖（issue #6 保底）：名称整门课；教室按时段，键 "weekday|startUnit"
     * （全部周）或 "weekday|startUnit|1,3,5"（分周——单双周换教室的课按教室分组各一条）。
     * 空白 = 清除该项。保存后重载学期数据并刷新详情快照，网格/今天/组件随之生效。
     */
    fun applyDisplayEdits(course: Course, name: String, roomEdits: Map<String, String>) {
        viewModelScope.launch {
            runCatching {
                repo.saveDisplayNameOverride(course.semesterId, course.code, name)
                roomEdits.forEach { (key, value) ->
                    val parts = key.split("|")
                    val wd = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
                    val su = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    val weeks = parts.getOrNull(2)
                        ?.takeIf { it.isNotBlank() }
                        ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                        ?.takeIf { it.isNotEmpty() }
                    repo.saveDisplayRoomOverride(course.semesterId, course.code, wd, su, weeks, value)
                }
            }
            reloadAfterDisplayEdit(course)
        }
    }

    /** 恢复一门课的教务原始显示 */
    fun resetDisplayOverrides(course: Course) {
        viewModelScope.launch {
            runCatching { repo.clearDisplayOverrides(course.semesterId, course.code) }
            reloadAfterDisplayEdit(course)
        }
    }

    private suspend fun reloadAfterDisplayEdit(old: Course) {
        val conflict = _state.value.detailConflict
        val semester = _state.value.selectedSemester
        if (semester != null) {
            loadSemester(semester, currentLoggedIn(), forceSync = false)
        }
        val fresh = _state.value.courses.firstOrNull { it.key == old.key } ?: old
        showDetail(fresh, conflict?.let { conflictDetails(it.block, _state.value) })

        // 小组件此刻还挂着改前的旧画面：它的重画只在「打开 App / 课程边界闹钟」两处
        // 定时发生，而用户刚编辑完就去桌面是最常见的路径 —— 必须在保存点主动推一遍。
        // 读的数据链路本身经 sessionsOn → withOverrides 是通的，缺的只是这一步刷新。
        AppGraph.appContextOrNull()?.let { ctx ->
            runCatching { Widgets.refreshAll(ctx) }
                .onFailure { Log.w("ScheduleViewModel", "编辑显示后刷新小组件失败", it) }
        }
    }

}
