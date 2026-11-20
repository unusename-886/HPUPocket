package com.zzupocket.app.ui.electricity

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.WeChatBridge
import com.zzupocket.app.data.electricity.SdcxParser
import com.zzupocket.app.ui.components.DescriptionText
import com.zzupocket.app.ui.components.GlassCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「更多 → 校园服务 → 宿舍用电」：免登录原生查询宿舍电量。
 *
 * 交互按用户要求：**楼栋 → 楼层 → 房间** 三级依次选择（打开时先看楼栋，选完自动进下一级），
 * 选项排成三列网格（对齐微后勤页面里"楼栋平铺"的观感，但格子可点、可搜）。
 * 选定房间后照明与空调一起查、并排显示；选择会记住，下次进来自动恢复到上次的房间并刷新一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricityScreen(onBack: () -> Unit) {
    val vm: ElectricityViewModel = viewModel()
    val s by vm.state.collectAsState()
    var picking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("宿舍用电") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (s.canQuery) {
                        IconButton(onClick = { vm.query() }, enabled = !s.querying) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新电量")
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
            SelectionCard(
                state = s,
                onSwitchCampus = { vm.switchCampus(it) },
                onPick = { picking = true },
                onRefresh = { vm.query() },
            )

            MeterResultCard(state = s)

            if (s.error.isNotBlank()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        s.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            MoreServicesCard()

            Spacer(Modifier.height(8.dp))
        }
    }

    if (picking) {
        RoomPickerDialog(
            state = s,
            onDismiss = { picking = false },
            onPickBuilding = { vm.selectBuilding(it) },
            onPickFloor = { vm.selectFloor(it) },
            onPickRoom = {
                picking = false
                vm.selectRoom(it)
            },
        )
    }
}

/* ------------------------------------------------------------------ 选择区 */

/** 校区 + 当前房间 + 「选择/更换房间」入口，一张卡看完全部状态 */
@Composable
private fun SelectionCard(
    state: ElectricityViewModel.State,
    onSwitchCampus: (ElectricityViewModel.Campus) -> Unit,
    onPick: () -> Unit,
    onRefresh: () -> Unit,
) {
    val complete = state.canQuery
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("查询用电", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "免登录 · 照明与空调一起查",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                CampusSwitch(state = state, onSwitch = onSwitchCampus)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = state.base.ifBlank { "还没选房间" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state.base.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = selectionHint(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clickable(onClick = onPick),
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (state.base.isBlank()) "选择房间" else "更换房间",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (complete) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .width(52.dp)
                            .height(46.dp)
                            .clickable(enabled = !state.querying, onClick = onRefresh),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (state.querying) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "刷新电量",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun selectionHint(state: ElectricityViewModel.State): String = when {
    state.base.isBlank() -> "按 楼栋 → 楼层 → 房间 依次选一次，之后自动记住"
    state.floorId.isBlank() -> "已选楼栋，继续选楼层"
    state.roomId.isBlank() -> "已选楼层，继续选房间"
    else -> listOf(state.floorLabel, state.roomLabel)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/** 校区切换：两段式胶囊，选中段填充主色，比 FilterChip 更紧凑也不抢焦点 */
@Composable
private fun CampusSwitch(
    state: ElectricityViewModel.State,
    onSwitch: (ElectricityViewModel.Campus) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(3.dp)) {
            ElectricityViewModel.Campus.entries.forEach { c ->
                val selected = state.campus == c
                val bg by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    label = "campusBg",
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = bg,
                    modifier = Modifier
                        .height(30.dp)
                        .clickable(enabled = !state.loading && !selected) { onSwitch(c) },
                ) {
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            c.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 结果区 */

/** 结果卡：照明 / 空调并排两块，欠费（负电量）也要照实显示并标出来 */
@Composable
private fun MeterResultCard(state: ElectricityViewModel.State) {
    val hasAny = state.readings.isNotEmpty()
    if (!hasAny && !state.querying && state.absentMeters.isEmpty()) return

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("剩余电量", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(state.base, state.floorLabel, state.roomLabel)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { "查询结果" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (state.readings.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.readings.forEach { r ->
                        MeterTile(
                            label = r.meter.label,
                            kwh = r.kwh,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (state.querying) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在查询…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            state.absentMeters.forEach { m ->
                Text(
                    "${m.label}表计下没有这个房间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (state.queriedAt > 0L) {
                Text(
                    "更新于 ${timeText(state.queriedAt)} · 非实时",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun MeterTile(label: String, kwh: Double, modifier: Modifier = Modifier) {
    val negative = kwh < 0
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (negative) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (negative) {
                    Text(
                        "欠费",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    kwhText(kwh),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

/** 统一两位小数；负号原样保留（`-0.01` 就是 `-0.01`，不做 0 值裁剪） */
private fun kwhText(kwh: Double): String = String.format(Locale.US, "%.2f", kwh)

private fun timeText(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/* ------------------------------------------------------------------ 三级选择器 */

private data class Pick(val id: String, val title: String, val subtitle: String = "")

/**
 * 三级选择弹层：**先楼栋 → 再楼层 → 最后房间**。
 *
 * 三个步骤头可以直接点回去改（选错楼栋不必重开）；每级选项排成三列网格。
 * 搜索框只在当前级选项超过 12 个时出现，且**不自动聚焦**——用户明确要求不要一开
 * 面板就弹输入法盖住列表。
 */
@Composable
private fun RoomPickerDialog(
    state: ElectricityViewModel.State,
    onDismiss: () -> Unit,
    onPickBuilding: (String) -> Unit,
    onPickFloor: (String) -> Unit,
    onPickRoom: (String) -> Unit,
) {
    // 0 楼栋 / 1 楼层 / 2 房间
    var step by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    // 换一级就把搜索词清掉，否则会带着上一级的关键字看空列表
    LaunchedEffect(step) { query = "" }

    val buildingPicks = state.buildings.map { Pick(it.base, it.base, it.subtitle) }
    val floorPicks = state.floors.map { Pick(it.id, SdcxParser.floorLabel(it.label)) }
    val roomPicks = state.rooms.map { Pick(it.id, SdcxParser.roomLabel(it.label)) }

    val options = when (step) {
        0 -> buildingPicks
        1 -> floorPicks
        else -> roomPicks
    }
    val selectedId = when (step) {
        0 -> state.base
        1 -> state.floorId
        else -> state.roomId
    }
    val filtered = remember(query, options) {
        if (query.isBlank()) options
        else options.filter { it.title.contains(query, true) || it.id.contains(query, true) }
    }

    val stepReady = listOf(
        state.buildings.isNotEmpty(),
        state.base.isNotBlank(),
        state.floorId.isNotBlank(),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 10.dp, vertical = 16.dp)
                .imePadding(),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("选择房间", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "楼栋 → 楼层 → 房间，选一次就记住",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepTab(
                        index = 1,
                        name = "楼栋",
                        value = state.base,
                        active = step == 0,
                        enabled = stepReady[0],
                        done = state.base.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = { step = 0 },
                    )
                    StepTab(
                        index = 2,
                        name = "楼层",
                        value = state.floorLabel,
                        active = step == 1,
                        enabled = stepReady[1],
                        done = state.floorId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = { step = 1 },
                    )
                    StepTab(
                        index = 3,
                        name = "房间",
                        value = state.roomLabel,
                        active = step == 2,
                        enabled = stepReady[2],
                        done = state.roomId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = { step = 2 },
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (options.size > 12) {
                    SearchBox(query = query, onChange = { query = it })
                    Spacer(Modifier.height(10.dp))
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        state.loading && options.isEmpty() -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                        }

                        filtered.isEmpty() -> Text(
                            emptyText(step = step, state = state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )

                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 8.dp),
                        ) {
                            items(filtered, key = { "${step}-${it.id}" }) { p ->
                                GridTile(
                                    title = p.title,
                                    subtitle = p.subtitle,
                                    selected = p.id == selectedId,
                                    onClick = {
                                        when (step) {
                                            0 -> {
                                                if (p.id != state.base) onPickBuilding(p.id)
                                                step = 1
                                            }
                                            1 -> {
                                                if (p.id != state.floorId) onPickFloor(p.id)
                                                step = 2
                                            }
                                            else -> if (p.id == state.roomId) onDismiss() else onPickRoom(p.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "选定房间后自动查询照明与空调，两笔电量一起显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun emptyText(step: Int, state: ElectricityViewModel.State): String = when {
    state.loading -> "加载中…"
    step == 0 -> "楼栋列表加载失败，请返回重进"
    step == 1 -> "该楼栋没有楼层数据"
    else -> "该层没有房间数据"
}

/** 顶部的一个步骤块：序号 + 级名 + 已选值 */
@Composable
private fun StepTab(
    index: Int,
    name: String,
    value: String,
    active: Boolean,
    enabled: Boolean,
    done: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            done -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        },
        label = "stepBg",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
        modifier = modifier
            .heightIn(min = 58.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done && !active) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                } else {
                    Text(
                        "$index",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                value.ifBlank { "未选" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 轻量搜索框：手写 BasicTextField，不申请焦点，所以点开面板不会弹输入法 */
@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        "搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "清除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).clickable { onChange("") },
                )
            }
        }
    }
}

/** 网格里的一格：选中填充主色淡底 + 描边，未选中是极浅的 surfaceVariant */
@Composable
private fun GridTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        label = "tileBg",
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 更多功能 */

/**
 * 「更多功能」：缴费充值/明细在微信公众号「河南理工大学微后勤」里。
 *
 * Android 没有能直接打开某个公众号的公开接口，所以这里做两件事：把公众号名复制到
 * 剪贴板（微信「搜一搜」粘贴即可），再尝试拉起微信本体。
 */
@Composable
private fun MoreServicesCard() {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var jumped by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("更多功能", style = MaterialTheme.typography.titleSmall)
            DescriptionText("缴费充值、用电明细在微信公众号「${WeChatBridge.OFFICIAL_ACCOUNT}」里，公众号内已验证身份可直接办理。")
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clickable {
                        clipboard.setText(AnnotatedString(WeChatBridge.OFFICIAL_ACCOUNT))
                        jumped = true
                        WeChatBridge.openWeChat(context)
                    },
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "去微信公众号办理",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                if (jumped) "公众号名称已复制，在微信「搜一搜」粘贴即可打开"
                else "点击会复制公众号「${WeChatBridge.OFFICIAL_ACCOUNT}」并打开微信",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
