package com.zzupocket.app

import android.app.Application
import com.zzupocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HPUPocketApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // 最早安装：连 AppGraph 初始化阶段的崩溃也能抓到，便于试运行失败时导出分析
        com.zzupocket.app.core.CrashLog.install(this)
        AppGraph.init(this)
        AppGraph.pomodoroNotifications.ensureChannels()

        // 河南理工版：把持久化的统一认证会话灌回内存 CookieJar，课表请求才能带上
        AppGraph.hpuSessionStore.restore()

        // 番茄钟可能在上一轮进程里就被杀了 —— 启动时把状态接回来。
        // 剩余时间是用墙钟算的，所以"接回来"之后不用做任何补偿。
        appScope.launch {
            runCatching { AppGraph.pomodoroEngine.restore() }
        }
    }
}
