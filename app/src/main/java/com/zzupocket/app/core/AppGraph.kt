package com.zzupocket.app.core

import android.content.Context
import com.zzupocket.app.data.auth.TokenStore
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.prefs.FocusPrefsStore
import com.zzupocket.app.data.prefs.UiPrefsStore
import com.zzupocket.app.data.repo.FocusRepository
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.core.net.HttpClients
import com.zzupocket.app.focus.PomodoroEngine
import com.zzupocket.app.focus.PomodoroNotifications
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 极简手动依赖容器。
 *
 * 河南理工版只保留 HPU 链路（统一认证 Cookie + EAMS 页面抓取）与学校无关的通用能力
 * （课表存储、专注番茄钟、外观设置）。郑大版原有的 JSON API 客户端、校园卡、图书馆、
 * 研究生教务、空教室等模块已按"用不到就删"的原则整体移除。
 */
object AppGraph {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 给需要在非 Composable 环境里拿 Context 的地方用（比如 ViewModel 清理背景图文件） */
    fun appContextOrNull(): Context? = if (::appContext.isInitialized) appContext else null

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true   // 学校改版加字段不应该让 App 崩
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    val uiPrefsStore: UiPrefsStore by lazy { UiPrefsStore(appContext) }

    /** 宿舍用电上次查询结果缓存（首页电量卡读它，不联网） */
    val electricityPrefs: com.zzupocket.app.data.prefs.ElectricityPrefsStore by lazy {
        com.zzupocket.app.data.prefs.ElectricityPrefsStore(appContext)
    }

    /**
     * 河南理工统一认证的会话 Cookie 罐（TGT 跨 uia/webvpn/zhjw 子域共享）。
     */
    val hpuCookieJar: com.zzupocket.app.data.auth.HpuCookieJar by lazy {
        com.zzupocket.app.data.auth.HpuCookieJar()
    }

    /**
     * 河南理工教务页抓取器：复用登录 WebView 的会话（进程级 CookieManager）。
     * 真机日志证实 OkHttp 直连教务会被 WebVPN 弹回登录页，只有 WebView 上下文能拿到课表。
     */
    val hpuEamsWebFetcher: com.zzupocket.app.data.eas.HpuEamsWebFetcher by lazy {
        com.zzupocket.app.data.eas.HpuEamsWebFetcher(appContext)
    }

    /** 河南理工 EAMS 教务（与 hpuCasClient 共用同一 cookie jar，CAS 登录后免账密进教务） */
    val hpuEamsClient: com.zzupocket.app.data.eas.HpuEamsClient by lazy {
        com.zzupocket.app.data.eas.HpuEamsClient(
            client = HttpClients.hpu(hpuCookieJar),
            webFetcher = hpuEamsWebFetcher,
        )
    }

    /** 宿舍电费（免登录站，南/北校区各一套，URL 不同）：自成会话，独立于统一认证 */
    val electricityNorth: com.zzupocket.app.data.electricity.SdcxElectricityClient by lazy {
        com.zzupocket.app.data.electricity.SdcxElectricityClient(SchoolConfig.URL_SDCX)
    }
    val electricitySouth: com.zzupocket.app.data.electricity.SdcxElectricityClient by lazy {
        com.zzupocket.app.data.electricity.SdcxElectricityClient(SchoolConfig.URL_SDCX_SOUTH)
    }
    /** @deprecated 保留兼容：默认北校区；新代码按校区选 electricityNorth/South */
    val sdcxElectricityClient: com.zzupocket.app.data.electricity.SdcxElectricityClient
        get() = electricityNorth

    /** 首页电量卡的「一键刷新」：用记住的选择重放查询（不经过 UI） */
    val electricityRefresher: com.zzupocket.app.data.electricity.ElectricityRefresher by lazy {
        com.zzupocket.app.data.electricity.ElectricityRefresher(
            prefs = electricityPrefs,
            south = electricitySouth,
            north = electricityNorth,
        )
    }

    val hpuSessionStore: com.zzupocket.app.data.auth.HpuSessionStore by lazy {
        com.zzupocket.app.data.auth.HpuSessionStore(
            store = tokenStore,
            cookieJar = hpuCookieJar,
            client = HttpClients.hpu(hpuCookieJar),
        )
    }

    /** 河南理工课表仓库（EAMS HTML → DB） */
    val hpuScheduleRepository: com.zzupocket.app.data.repo.HpuScheduleRepository by lazy {
        com.zzupocket.app.data.repo.HpuScheduleRepository(db, hpuEamsClient, hpuSessionStore)
    }

    /**
     * 空闲教室：与课表同一套教务会话。
     *
     * 先用 OkHttp 快路（校内网络秒回），被 WebVPN 拦就降级到 WebView 页面内 `fetch`。
     */
    val hpuRoomWebFetcher: com.zzupocket.app.data.eas.HpuRoomWebFetcher by lazy {
        com.zzupocket.app.data.eas.HpuRoomWebFetcher(appContext)
    }

    val hpuFreeRoomClient: com.zzupocket.app.data.eas.HpuFreeRoomClient by lazy {
        com.zzupocket.app.data.eas.HpuFreeRoomClient(
            http = HttpClients.hpu(hpuCookieJar),
            fetcher = hpuRoomWebFetcher,
        )
    }

    /**
     * 当前学校口径的登录态 —— UI 显示「已登录/未登录」统一走这里。
     *
     * HPU 登录写的是 [hpuSessionStore]（Cookie），不写 JWT/token，
     * 所以任何直接读 `authRepository.isLoggedIn()` 的旧代码在 HPU 版都会误显示"未登录"。
     */
    fun isLoggedInForSchool(): Boolean = hpuSessionStore.hasSession()

    val db: AppDatabase by lazy { AppDatabase.get(appContext) }

    val scheduleRepository: ScheduleRepository by lazy {
        ScheduleRepository(
            db = db,
            // 河南理工版：作息用内置春/秋两套，实时读偏好（含 AUTO 按当天判断）
            periodProvider = {
                val mode = uiPrefsStore.flow.first().periodMode
                com.zzupocket.app.domain.HpuPeriods.periodsFor(mode)
            },
        )
    }

    val focusRepository: FocusRepository by lazy { FocusRepository(db) }

    val focusPrefsStore: FocusPrefsStore by lazy { FocusPrefsStore(appContext, json) }

    val pomodoroNotifications: PomodoroNotifications by lazy { PomodoroNotifications(appContext) }

    val pomodoroEngine: PomodoroEngine by lazy {
        PomodoroEngine(
            context = appContext,
            prefs = focusPrefsStore,
            repo = focusRepository,
            notifier = pomodoroNotifications,
        )
    }
}
