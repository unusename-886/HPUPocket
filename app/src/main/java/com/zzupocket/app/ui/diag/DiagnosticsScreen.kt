package com.zzupocket.app.ui.diag

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.CrashLog
import com.zzupocket.app.core.SchoolConfig
import com.zzupocket.app.ui.components.GlassCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FULL_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

data class DiagState(
    /** 河南理工统一认证会话是否存在 */
    val loggedIn: Boolean = false,
    val loginAt: Long = 0L,
    /** 「检查会话」的结果：null = 还没查；true/false = 服务端是否仍认这个会话 */
    val alive: Boolean? = null,
    val checking: Boolean = false,
    /** 桌面上每个组件实例的画布上报值/尺寸通道/字体实测行高；空串 = 还没收集到 */
    val widgetReport: String = "",
    /** 小组件几何卡是否展开（默认收起：平时不打扰，反馈小组件问题时再点开截图） */
    val widgetOpen: Boolean = false,
)

class DiagnosticsViewModel : ViewModel() {

    private val _state = MutableStateFlow(DiagState())
    val state: StateFlow<DiagState> = _state.asStateFlow()

    init {
        refreshSession()
    }

    private fun refreshSession() {
        _state.update {
            it.copy(
                loggedIn = AppGraph.hpuSessionStore.hasSession(),
                loginAt = AppGraph.hpuSessionStore.loginAt(),
            )
        }
    }

    /** 向学校探一次会话是否还有效（纯诊断，失败不改登录态） */
    fun checkSession() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val alive = runCatching { AppGraph.hpuSessionStore.isValid() }.getOrDefault(false)
            // 网络探测只在这里跑：它会重新走一遍 CAS 兑票，放在同步流程里会把 WebView 的会话换掉
            runCatching { AppGraph.hpuSessionStore.probeNetwork() }
            _state.update { it.copy(checking = false, alive = alive) }
            refreshSession()
        }
    }

    /** 重新采集小组件几何（刚在桌面添加/调整组件之后点一下，数据就是新的） */
    fun refreshWidgets() {
        _state.update {
            it.copy(
                widgetReport = runCatching {
                    AppGraph.appContextOrNull()?.let { ctx ->
                        com.zzupocket.app.ui.widget.WidgetDiagnostics.collect(ctx)
                    } ?: "applicationContext 未初始化"
                }.getOrElse { e -> e.message ?: e.toString() },
            )
        }
    }

    fun toggleWidgets() = _state.update { it.copy(widgetOpen = !it.widgetOpen) }

    fun logout(onDone: () -> Unit) {
        runCatching { AppGraph.hpuSessionStore.logout() }
        refreshSession()
        viewModelScope.launch {
            runCatching {
                val id = AppGraph.scheduleRepository.semesters().firstOrNull()?.id
                if (id != null) {
                    AppGraph.scheduleRepository.clearTimetable(
                        source = com.zzupocket.app.data.repo.HpuScheduleRepository.SOURCE,
                        semesterId = id,
                    )
                }
            }
        }
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    vm: DiagnosticsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 进页面读一次崩溃摘要（文件 IO，不随重组反复读）
    val crashSummary = remember { CrashLog.crashSummary() }
    var showLog by remember { mutableStateOf(false) }
    val logText = remember { CrashLog.exportText() }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录状态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            StatusRow(
                label = "统一身份认证（${SchoolConfig.SCHOOL_SHORT}）",
                ok = state.loggedIn,
                detail = if (state.loggedIn) {
                    "已登录（${FULL_FMT.format(Date(state.loginAt))}），课表数据由此会话同步"
                } else {
                    "未登录。请到「更多 → 账号」完成统一身份认证"
                },
            )

            if (state.loggedIn) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("会话自检", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "向教务发一次只读请求，确认学校那边是否还认这个会话（不会修改任何数据）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { vm.checkSession() },
                            enabled = !state.checking,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.checking) "检查中…" else "检查会话是否有效")
                        }
                        state.alive?.let { alive ->
                            Text(
                                text = if (alive) "会话有效，可以正常同步课表" else "会话已失效，请重新登录",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (alive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }

            // ── 试运行日志导出：崩溃时把这里的文件发回来即可分析 ──
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("运行日志", style = MaterialTheme.typography.titleSmall)
                    if (crashSummary != null) {
                        Text(
                            "⚠️ 检测到上次崩溃：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        SelectionContainer {
                            Text(
                                crashSummary,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    } else {
                        Text(
                            "暂无崩溃记录。若试运行中出现闪退/异常，这里会记录堆栈。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { showLog = !showLog },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (showLog) "收起日志" else "查看日志全文（可复制）")
                    }
                    if (showLog) {
                        SelectionContainer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                logText,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(logText)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("复制日志到剪贴板（粘贴发给开发者）")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val sent = CrashLog.shareIntent()?.let { intent ->
                                runCatching { context.startActivity(intent); true }.getOrDefault(false)
                            } ?: false
                            if (!sent) {
                                android.widget.Toast.makeText(
                                    context,
                                    "本机没有可接收分享的应用，请用上面「查看日志全文」复制",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("导出日志文件（分享给开发者）")
                    }
                    Text(
                        "含崩溃堆栈与关键事件；已自动打码令牌/手机号。若分享无应用接收，请用「复制日志」。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // ── 桌面组件几何：排障专用，默认收起 ──
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("桌面组件", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.toggleWidgets(); vm.refreshWidgets() }) {
                            Text(if (state.widgetOpen) "收起" else "展开")
                        }
                    }
                    if (state.widgetOpen) {
                        SelectionContainer {
                            Text(
                                state.widgetReport.ifBlank { "还没采集到组件数据（先在桌面添加一个组件）" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    } else {
                        Text(
                            "组件显示不对时展开这里，把内容截图发给开发者。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { vm.logout(onLoggedOut) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (ok) "正常" else "未就绪",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
