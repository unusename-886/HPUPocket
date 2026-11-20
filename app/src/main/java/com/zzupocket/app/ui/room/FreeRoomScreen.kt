package com.zzupocket.app.ui.room

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.CrashLog
import com.zzupocket.app.data.eas.FreeRoomParser
import com.zzupocket.app.data.eas.HpuClassroomDict
import com.zzupocket.app.ui.components.GlassCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「校园 → 空闲教室」：查某个时段哪些教室空着（数据来自教务 `free!search.action`）。
 *
 * 版式沿用宿舍用电那套「一张条件卡 + 结果卡」：先定日期与节次，再选校区/楼栋，
 * 一次查询拿到全部可用教室，按教学楼分组。搜索框不自动聚焦（用户明确不要弹输入法）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeRoomScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit = {},
) {
    val vm: FreeRoomViewModel = viewModel()
    val s by vm.state.collectAsState()
    var pickingBuilding by remember { mutableStateOf(false) }

    // 进页面就打一条：用户报"没反应"时，日志里有没有这行直接区分"没进页面"还是"进了但查询失败"
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()
        CrashLog.i("freeroom", "screen open version=$version")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("空闲教室") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (s.hasQueried && !s.loading) {
                        IconButton(onClick = { vm.query() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterCard(
                state = s,
                onDate = { vm.setDate(it) },
                onSlot = { vm.setSlot(it) },
                onCampus = { vm.setCampus(it) },
                onPickBuilding = { pickingBuilding = true },
                onQuery = { vm.query() },
            )

            when {
                s.needLogin -> NeedLoginCard(onOpenLogin)
                s.error.isNotBlank() -> MessageCard(s.error, isError = true)
                s.loading -> LoadingCard()
                s.hasQueried -> ResultSection(s)
                else -> HintCard()
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickingBuilding) {
        BuildingPickerDialog(
            buildings = s.buildings,
            selectedId = s.buildingId,
            onDismiss = { pickingBuilding = false },
            onPick = {
                pickingBuilding = false
                vm.setBuilding(it)
            },
        )
    }
}

// ── 条件卡 ──────────────────────────────────────────────────────────────

@Composable
private fun FilterCard(
    state: FreeRoomViewModel.State,
    onDate: (LocalDate) -> Unit,
    onSlot: (FreeRoomViewModel.Slot) -> Unit,
    onCampus: (String) -> Unit,
    onPickBuilding: () -> Unit,
    onQuery: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionLabel("日期")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val today = LocalDate.now()
                repeat(7) { offset ->
                    val day = today.plusDays(offset.toLong())
                    FilterChip(
                        text = dayChipText(day, today),
                        selected = day == state.date,
                        onClick = { onDate(day) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("时段")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeRoomViewModel.Slot.entries.forEach { slot ->
                    FilterChip(
                        text = slot.label,
                        selected = slot == state.slot,
                        onClick = { onSlot(slot) },
                    )
                }
            }
            Text(
                "第 ${state.slot.begin}–${state.slot.end} 节 · ${state.timeText}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(14.dp))
            SectionLabel("校区")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HpuClassroomDict.campuses.forEach { campus ->
                    FilterChip(
                        text = campus.label,
                        selected = campus.id == state.campusId,
                        onClick = { onCampus(campus.id) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("教学楼")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable(onClick = onPickBuilding)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.buildingLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.buildingId.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onQuery,
                enabled = !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("查询空闲教室", fontWeight = FontWeight.Medium)
                }
            }
            Text(
                "教务按课表反算，临时调课/借用教室可能未反映",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

// ── 结果 ────────────────────────────────────────────────────────────────

@Composable
private fun ResultSection(state: FreeRoomViewModel.State) {
    val total = state.rooms.size
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (total == 0) "没有空闲教室" else "共 $total 间",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "· ${state.campus.label} · ${state.dateLabel} · ${state.slot.label}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (total == 0) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("这个时段该范围内没有空教室", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "换个时段或把「教学楼」切回「全部楼栋」再试；也可能是这一节全校都排满了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.slot == FreeRoomViewModel.Slot.ALL && state.buildingId.isBlank()) {
                    // 全校全天 0 间在客观上是反常的，几乎只会是接口参数与教务版本对不上
                    Text(
                        "全校全时段都查不到，多半不是真的没有教室，而是教务接口参数需要更新 —— 请把这条现象反馈给开发者。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.headers.isNotEmpty()) {
                    // 表头原样露出：万一 HPU 的列名与我们的别名表对不上，用户截图这条就能定位
                    Text(
                        "教务返回列：${state.headers.filter { it.isNotBlank() }.joinToString(" / ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        return
    }

    state.grouped.forEach { (building, rooms) ->
        Text(
            "$building · ${rooms.size} 间",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                rooms.forEachIndexed { index, room ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        )
                    }
                    RoomRow(room)
                }
            }
        }
    }
}

@Composable
private fun RoomRow(room: FreeRoomParser.Room) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            room.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            room.seats?.let { "$it 座" },
            room.type.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}

// ── 状态卡 ──────────────────────────────────────────────────────────────

@Composable
private fun HintCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("选好日期和节次，点上面查询", style = MaterialTheme.typography.bodyMedium)
            Text(
                "结果就是「这一节课哪些教室没人用」，可以直接拿去上自习。数据与教务系统一致，不需要截图课表对照。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "正在向教务查询…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun NeedLoginCard(onOpenLogin: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("教务登录已失效", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "空闲教室和课表共用同一个教务会话，重新登录后回来再点查询即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenLogin, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("去登录")
            }
        }
    }
}

// ── 楼栋选择（弹层，搜索框不自动聚焦）────────────────────────────────────

@Composable
private fun BuildingPickerDialog(
    buildings: List<HpuClassroomDict.Building>,
    selectedId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val filtered = remember(keyword, buildings) {
        if (keyword.isBlank()) buildings
        else buildings.filter { it.shortName.contains(keyword, ignoreCase = true) || it.name.contains(keyword, ignoreCase = true) }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GlassCard(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("选择教学楼", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (keyword.isEmpty()) {
                            Text(
                                "搜索教学楼（如 1号、综合）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        BasicTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        PickerRow(
                            text = "全部楼栋",
                            selected = selectedId.isBlank(),
                            onClick = { onPick("") },
                        )
                    }
                    items(filtered) { b ->
                        PickerRow(
                            text = b.shortName,
                            selected = b.id == selectedId,
                            onClick = { onPick(b.id) },
                        )
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "没找到匹配的教学楼",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text("已选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ── 小部件 ──────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .then(
                if (selected) Modifier
                else Modifier.border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(50),
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun dayChipText(day: LocalDate, today: LocalDate): String {
    val prefix = when (day) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> when (day.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
    }
    return "$prefix ${day.format(DateTimeFormatter.ofPattern("M/d", Locale.CHINA))}"
}
