package com.zzupocket.app.ui.electricity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.CrashLog
import com.zzupocket.app.data.electricity.SdcxElectricityClient
import com.zzupocket.app.data.electricity.SdcxParser
import com.zzupocket.app.data.prefs.ElectricityPrefsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 宿舍电量查询的状态机：南/北校区 → 楼栋（照明+空调合一）→ 楼层 → 房间 → 一次查两个表计。
 *
 * 设计要点：
 *  · **选一次就记住**：选择立刻落盘，下次进页面按缓存恢复到具体房间并自动刷新一次；
 *  · **照明空调一起查**：站上两套表计是独立楼栋，但用户只选一次房间，client 内部按编号换前缀
 *    分别查询，省掉用户"再来一遍"的操作；
 *  · 每一步带上一页的 `__VIEWSTATE`（WebForms 回发契约）；切换校区会重置全部选择。
 */
class ElectricityViewModel : ViewModel() {

    enum class Campus(val label: String, val key: String) {
        SOUTH("南校区", SdcxElectricityClient.KEY_SOUTH),
        NORTH("北校区", SdcxElectricityClient.KEY_NORTH),
    }

    data class State(
        val campus: Campus = Campus.SOUTH,          // 用户 default 南校区
        val buildings: List<SdcxParser.BuildingGroup> = emptyList(),
        val floors: List<SdcxParser.Option> = emptyList(),
        val rooms: List<SdcxParser.Option> = emptyList(),
        val base: String = "",
        val floorId: String = "",
        val roomId: String = "",
        val readings: List<SdcxElectricityClient.MeterReading> = emptyList(),
        val absentMeters: List<SdcxParser.Meter> = emptyList(),
        val loading: Boolean = false,
        val querying: Boolean = false,
        val error: String = "",
        val queriedAt: Long = 0L,
    ) {
        val canQuery: Boolean get() = base.isNotBlank() && floorId.isNotBlank() && roomId.isNotBlank()

        val selectedGroup: SdcxParser.BuildingGroup?
            get() = buildings.firstOrNull { it.base == base }

        val floorLabel: String get() = floors.firstOrNull { it.id == floorId }?.let { SdcxParser.floorLabel(it.label) }.orEmpty()

        /** 房间只留门牌号（`101`）：步骤块、结果卡、首页卡都用这个，别把表计后缀带出去 */
        val roomLabel: String get() = rooms.firstOrNull { it.id == roomId }?.let { SdcxParser.roomLabel(it.label) }.orEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var homeVs = ""
    private var floorVs = ""

    private val prefs: ElectricityPrefsStore get() = AppGraph.electricityPrefs

    private fun client(campus: Campus): SdcxElectricityClient =
        if (campus == Campus.SOUTH) AppGraph.electricitySouth else AppGraph.electricityNorth

    init {
        viewModelScope.launch { bootstrap() }
    }

    /** 首次进入：读上次的选择，能恢复就一路恢复到房间并刷新一次 */
    private suspend fun bootstrap() {
        val cached = prefs.load()
        val campus = Campus.entries.firstOrNull { it.key == cached.campusKey } ?: Campus.SOUTH
        _state.value = _state.value.copy(campus = campus, loading = true, error = "")
        runCatching {
            val home = client(campus).loadHome()
            homeVs = home.viewState
            _state.value = _state.value.copy(buildings = home.buildings, loading = false)
            if (cached.base.isNotBlank()) restore(cached)
        }.onFailure { e ->
            CrashLog.e("electricity", "load buildings failed", e)
            _state.value = _state.value.copy(
                loading = false,
                error = "楼栋列表加载失败：${e.message ?: "网络错误"}",
            )
        }
    }

    private suspend fun restore(cached: ElectricityPrefsStore.Cached) {
        val s = _state.value
        val group = s.buildings.firstOrNull { it.base == cached.base } ?: return
        val cli = client(s.campus)

        val floors = runCatching { cli.loadFloors(group, homeVs) }.getOrNull() ?: return
        floorVs = floors.viewState
        _state.value = _state.value.copy(
            base = group.base,
            floors = floors.options.filter { it.id.isNotBlank() },
        )
        if (floors.options.none { it.id == cached.floorId }) return

        val rooms = runCatching { cli.loadRooms(group, cached.floorId, floorVs) }.getOrNull() ?: return
        _state.value = _state.value.copy(
            floorId = cached.floorId,
            rooms = rooms.options.filter { it.id.isNotBlank() },
        )
        if (rooms.options.none { it.id == cached.roomId }) return

        _state.value = _state.value.copy(roomId = cached.roomId)
        // 记住的选择能完整回放 → 进页面直接给一次新鲜读数（同时同步首页卡）
        query()
    }

    fun switchCampus(campus: Campus) {
        if (_state.value.campus == campus) return
        homeVs = ""
        floorVs = ""
        _state.value = State(campus = campus, loading = true)
        viewModelScope.launch {
            runCatching {
                val home = client(campus).loadHome()
                homeVs = home.viewState
                home.buildings
            }.onSuccess { groups ->
                _state.value = _state.value.copy(buildings = groups, loading = false)
            }.onFailure { e ->
                CrashLog.e("electricity", "load buildings failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = "楼栋列表加载失败：${e.message ?: "网络错误"}",
                )
            }
        }
    }

    fun selectBuilding(base: String) {
        val group = _state.value.buildings.firstOrNull { it.base == base } ?: return
        floorVs = ""
        _state.value = _state.value.copy(
            base = base, floorId = "", roomId = "",
            floors = emptyList(), rooms = emptyList(),
            readings = emptyList(), absentMeters = emptyList(), error = "",
        )
        persistSelection()
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching {
                val page = client(_state.value.campus).loadFloors(group, homeVs)
                floorVs = page.viewState
                page.options.filter { it.id.isNotBlank() }
            }.onSuccess { opts ->
                _state.value = _state.value.copy(floors = opts, loading = false)
            }.onFailure { e ->
                CrashLog.e("electricity", "load floors failed", e)
                _state.value = _state.value.copy(loading = false, error = "楼层列表加载失败：${e.message ?: "网络错误"}")
            }
        }
    }

    fun selectFloor(id: String) {
        val s = _state.value
        val group = s.selectedGroup ?: return
        _state.value = s.copy(
            floorId = id, roomId = "", rooms = emptyList(),
            readings = emptyList(), absentMeters = emptyList(), error = "",
        )
        persistSelection()
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching {
                client(_state.value.campus).loadRooms(group, id, floorVs)
                    .options.filter { it.id.isNotBlank() }
            }.onSuccess { opts ->
                _state.value = _state.value.copy(rooms = opts, loading = false)
            }.onFailure { e ->
                CrashLog.e("electricity", "load rooms failed", e)
                _state.value = _state.value.copy(loading = false, error = "房间列表加载失败：${e.message ?: "网络错误"}")
            }
        }
    }

    fun selectRoom(id: String) {
        _state.value = _state.value.copy(roomId = id, readings = emptyList(), absentMeters = emptyList(), error = "")
        persistSelection()
        query()
    }

    /** 刷新：重放当前选择的查询（页面按钮 / 首页卡片都用它） */
    fun query() {
        val s = _state.value
        val group = s.selectedGroup ?: return
        if (!s.canQuery || s.querying) return
        viewModelScope.launch {
            // 保留上一次读数，刷新过程中数字不闪空；查到结果直接覆盖
            _state.value = _state.value.copy(querying = true, error = "")
            runCatching {
                if (homeVs.isBlank()) {
                    // 从上次的楼层/房间直接刷新，但首页 VIEWSTATE 还没拿到（极端情况：进程被回收）
                    homeVs = client(s.campus).loadHome().viewState
                }
                client(s.campus).queryRoom(group, s.floorId, s.roomId, homeVs)
            }.onSuccess { result ->
                if (result.hasData) {
                    _state.value = _state.value.copy(
                        readings = result.readings,
                        absentMeters = result.absent,
                        querying = false,
                        queriedAt = System.currentTimeMillis(),
                    )
                    prefs.saveReading(result, locationText(_state.value))
                } else {
                    CrashLog.w("electricity", "balance not found in result page")
                    _state.value = _state.value.copy(
                        querying = false,
                        absentMeters = result.absent,
                        error = "没查到电量，请确认房间号是否正确",
                    )
                }
            }.onFailure { e ->
                CrashLog.e("electricity", "query balance failed", e)
                _state.value = _state.value.copy(querying = false, error = "查询失败：${e.message ?: "网络错误"}")
            }
        }
    }

    /** 这批读数的位置快照，交给首页卡展示（`南校区 · 松园1号楼 · 1层 · 101`） */
    private fun locationText(s: State): String =
        listOf(s.campus.label, s.base, s.floorLabel, s.roomLabel)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")

    private fun persistSelection() {
        val s = _state.value
        if (s.base.isBlank()) return
        prefs.saveSelection(
            campusKey = s.campus.key,
            campusLabel = s.campus.label,
            base = s.base,
            floorId = s.floorId,
            floorLabel = s.floorLabel,
            roomId = s.roomId,
            roomLabel = s.roomLabel,
        )
    }
}
