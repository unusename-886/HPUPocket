package com.zzupocket.app.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.CrashLog
import com.zzupocket.app.data.eas.FreeRoomParser
import com.zzupocket.app.data.eas.HpuClassroomDict
import com.zzupocket.app.data.eas.HpuFreeRoomClient
import com.zzupocket.app.domain.HpuPeriods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 空闲教室的状态机。
 *
 * 交互口径：**先选日期和时段，再看"这个时段哪些教室空着"**。
 * 查询是"一次拿到该时段全部可用教室"，所以结果按教学楼分组展示，而不是按时间轴排。
 *
 * 默认值都取"最省操作"的那个：南校区（用户在读）、今天、全天。
 */
class FreeRoomViewModel : ViewModel() {

    /** 时段预设：学校作息是 11 节单节 45 分钟，学生找自习室通常按"半天"思考 */
    enum class Slot(val label: String, val begin: Int, val end: Int) {
        ALL("全天", 1, 11),
        AM("上午", 1, 5),
        PM("下午", 6, 8),
        EVE("晚上", 9, 11),
    }

    data class State(
        val campusId: String = "1",
        val buildingId: String = "",
        val date: LocalDate = LocalDate.now(),
        val slot: Slot = Slot.ALL,
        val rooms: List<FreeRoomParser.Room> = emptyList(),
        val headers: List<String> = emptyList(),
        val loading: Boolean = false,
        val needLogin: Boolean = false,
        val error: String = "",
        val queriedAt: Long = 0L,
        val via: HpuFreeRoomClient.Via? = null,
        val hasQueried: Boolean = false,
    ) {
        val campus: HpuClassroomDict.Campus
            get() = HpuClassroomDict.campuses.firstOrNull { it.id == campusId }
                ?: HpuClassroomDict.campuses.first()

        val buildings: List<HpuClassroomDict.Building>
            get() = HpuClassroomDict.buildingsOf(campusId)

        val buildingLabel: String
            get() = buildings.firstOrNull { it.id == buildingId }?.shortName ?: "全部楼栋"

        /** 按教学楼分组：结果多时"哪些楼有位置"比"有哪些教室"更重要 */
        val grouped: List<Pair<String, List<FreeRoomParser.Room>>>
            get() = rooms.groupBy { it.building.ifBlank { "未标注楼栋" } }
                .toList()
                .sortedWith(compareByDescending<Pair<String, List<FreeRoomParser.Room>>> { it.second.size }.thenBy { it.first })

        /** 当前时段对应的作息时间文本，如「08:00–12:35」 */
        val timeText: String
            get() {
                val periods = HpuPeriods.periodsFor(HpuPeriods.PeriodMode.AUTO, date)
                val from = periods.firstOrNull { it.indexNo == slot.begin }
                val to = periods.firstOrNull { it.indexNo == slot.end }
                return if (from != null && to != null) "${from.startText}–${to.endText}" else ""
            }

        val dateLabel: String
            get() = date.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)) + " " + weekdayZh(date)    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setCampus(id: String) {
        if (_state.value.campusId == id) return
        // 换校区必须清空楼栋：楼栋 id 是全校唯一，但列表不同，留着会出现"北校区列表里选了南校区楼栋"
        _state.value = _state.value.copy(campusId = id, buildingId = "", rooms = emptyList(), hasQueried = false, error = "")
    }

    fun setBuilding(id: String) {
        _state.value = _state.value.copy(buildingId = id)
    }

    fun setDate(date: LocalDate) {
        _state.value = _state.value.copy(date = date, rooms = emptyList(), hasQueried = false, error = "")
    }

    fun setSlot(slot: Slot) {
        _state.value = _state.value.copy(slot = slot, rooms = emptyList(), hasQueried = false, error = "")
    }

    fun query() {
        val s = _state.value
        if (s.loading) return
        _state.value = s.copy(loading = true, error = "", needLogin = false)
        viewModelScope.launch {
            val query = HpuFreeRoomClient.Query(
                date = s.date,
                beginSection = s.slot.begin,
                endSection = s.slot.end,
                campusId = s.campusId,
                buildingId = s.buildingId,
            )
            CrashLog.i(
                "freeroom",
                "vm query date=${s.date} ${s.slot.label}(${s.slot.begin}-${s.slot.end}) " +
                    "campus=${s.campus.label} building=${s.buildingLabel}",
            )
            try {
                when (val result = AppGraph.hpuFreeRoomClient.query(query)) {
                    is HpuFreeRoomClient.Result.Ok -> {
                        CrashLog.i("freeroom", "vm ok ${result.table.rooms.size} rooms via ${result.via}")
                        _state.value = _state.value.copy(
                            loading = false,
                            rooms = result.table.rooms,
                            headers = result.table.headers,
                            via = result.via,
                            queriedAt = System.currentTimeMillis(),
                            hasQueried = true,
                        )
                    }

                    HpuFreeRoomClient.Result.NeedLogin -> {
                        CrashLog.w("freeroom", "vm need login")
                        _state.value = _state.value.copy(
                            loading = false,
                            needLogin = true,
                            hasQueried = true,
                        )
                    }

                    is HpuFreeRoomClient.Result.Failed -> {
                        CrashLog.w("freeroom", "vm failed: ${result.message}")
                        _state.value = _state.value.copy(
                            loading = false,
                            error = result.message,
                            hasQueried = true,
                        )
                    }
                }
            } catch (e: Exception) {
                CrashLog.e("freeroom", "query failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "查询失败",
                    hasQueried = true,
                )
            }
        }
    }

}

/** 周几的中文短名（`State.dateLabel` 用；放类外，嵌套类才能直接引用） */
private fun weekdayZh(date: LocalDate): String =
    when (date.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
