package com.zzupocket.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.ui.components.GlassCard
import com.zzupocket.app.ui.nav.DOCK_CLEARANCE
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「首页」页（原「今天」）。
 *
 * 结构：**今天的时间轴**（上课 / 已完成的专注）+ 今天的日程（自己在课表里加的）。
 *
 * 早期版本首页还有电费、校园卡、图书馆、空教室四张卡，它们全部依赖郑州大学的接口，
 * 河南理工版既没有对应接口也没有数据源，已整体移除——留着一张永远"未适配"的卡
 * 比没有更糟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenLogin: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenElectricity: () -> Unit = {},
    timelineVm: TodayTimelineViewModel = viewModel(),
) {
    val timeline by timelineVm.state.collectAsStateWithLifecycle()

    // 登录态统一走 AppGraph：河南理工写的是 Cookie 会话，不是 JWT。
    var loggedIn by remember { mutableStateOf(AppGraph.isLoggedInForSchool()) }

    // 从登录页返回、或从后台切回来时重新确认登录态并重算时间轴。
    // 少了这一步，登录成功回到「首页」仍会显示"未登录"——因为 ViewModel 不会重建。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        loggedIn = AppGraph.isLoggedInForSchool()
        timelineVm.reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("首页") },
                actions = {
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = "登录状态")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                // 悬浮 dock 是覆盖层：内容从胶囊底下穿过，这里垫出尾部余量，
                // 让最后一张卡能完整滚出胶囊（见 DOCK_CLEARANCE）。
                .padding(bottom = DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (!loggedIn) {
                LoginPromptCard(onOpenLogin)
            }

            // ① 今天的时间轴：上课 / 已完成的专注（课间空档不画，见 TodayTimelineCard 的注释）
            TodayTimelineCard(state = timeline, onOpenSchedule = onOpenSchedule)

            // ② 今天的日程：自己在课表里加的（没有就不显示这张卡）
            TodayCustomScheduleCard(
                entries = timeline.customEntries,
                onOpenSchedule = onOpenSchedule,
            )

            // ③ 宿舍用电：上次查询结果缓存在本地，秒开不联网；点进去手动刷新/切换房间
            TodayElectricityCard(onOpenElectricity = onOpenElectricity)

            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 首页的宿舍用电卡：常驻显示（借鉴 HAUTPocket「宿舍模块常驻可见」的设计）。
 *
 * 有缓存 → 显示记住的房间 + 照明/空调两项读数 + 更新时间，并标注"非实时"；
 * 没查过 → 显示"点击查询"引导。卡片右上角有刷新按钮：不用进页面，用记住的选择
 * 直接在后台重放一次查询（这是用户要的「刷新」与「同步到首页」）。
 */
@Composable
private fun TodayElectricityCard(onOpenElectricity: () -> Unit) {
    // 从电费页返回时 ON_RESUME 重读缓存，保证刚查的电量立刻反映到首页卡
    var cached by remember { mutableStateOf(AppGraph.electricityPrefs.load()) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!refreshing) cached = AppGraph.electricityPrefs.load()
    }
    val hasData = cached.hasReading
    val time = remember(cached.timeMillis) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(cached.timeMillis))
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenElectricity),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "宿舍用电",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (hasData) {
                    // 位置用读数自带的快照，改过楼栋但没查时也不会和数字错位
                    Text(
                        cached.displayLocation.ifBlank { "上次查询" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        cached.lightKwh?.let { MeterFigure("照明", it) }
                        cached.acKwh?.let { MeterFigure("空调", it) }
                    }
                    // 借鉴 HAUTPocket：明确标注缓存非实时值
                    Text(
                        if (refreshing) "正在刷新…" else "更新于 $time · 非实时",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text("点此查询剩余电量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (cached.canRefresh) {
                IconButton(
                    enabled = !refreshing,
                    onClick = {
                        refreshing = true
                        scope.launch {
                            AppGraph.electricityRefresher.refresh()
                            cached = AppGraph.electricityPrefs.load()
                            refreshing = false
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新电量",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "查询用电",
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun MeterFigure(label: String, kwh: Double) {
    // 欠费房间实测是负电量（-0.01），照实显示并染成错误色，别让用户以为卡在 0
    val negative = kwh < 0
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 4.dp, bottom = 3.dp),
        )
        Text(
            String.format(java.util.Locale.US, "%.2f", kwh),
            style = MaterialTheme.typography.titleLarge,
            color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            if (negative) " 度 · 欠费" else " 度",
            style = MaterialTheme.typography.labelSmall,
            color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

@Composable
private fun LoginPromptCard(onOpenLogin: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("未登录", style = MaterialTheme.typography.titleMedium)
            Text("登录后可以查看并同步课表。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenLogin) {
                Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("登录")
            }
        }
    }
}
