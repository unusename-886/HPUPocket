package com.zzupocket.app.data.auth

import android.webkit.CookieManager
import com.zzupocket.app.data.eas.HpuEamsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 河南理工统一认证会话管理。
 *
 * HPU 没有可用 JWT：登录成功后学校在浏览器里落一组 Cookie（CASTGC=TGT-... 等），
 * 靠这组 Cookie 穿过 CAS/WebVPN 访问教务。策略与"研究生教务 WebView 登录"一致：
 *  - 用户密码**始终输在学校自己的网页**上，App 不接触、不保存；
 *  - 只把登录成功后 CookieManager 里落下的会话 Cookie 取回、加密持久化。
 *
 * 会话有效性判定：拿保存的 Cookie 请求 EAMS 入口，若被弹回登录页则失效。
 */
class HpuSessionStore(
    private val store: TokenStore,
    private val cookieJar: HpuCookieJar,
    private val client: okhttp3.OkHttpClient,
) {

    /** 是否已存过 HPU 会话 */
    fun hasSession(): Boolean = !store.hpuCookies.isNullOrBlank()

    /** 上次登录时间（毫秒） */
    fun loginAt(): Long = store.hpuLoginAt

    /**
     * 启动时把持久化的 Cookie 灌回**两套存储**：
     *  - 内存 CookieJar（OkHttp 探测/直连用）；
     *  - WebView 的 CookieManager（页面加载与页面内 fetch 用）。
     *
     * ⚠️ 真机 18:08 日志：OkHttp probe 成功（`bounced=false`），但抓取 WebView 加载教务
     * 却被弹回 `loginExt.action`。根因就是这里只恢复了 CookieJar，CookieManager 仍是旧的/空的，
     * 两套会话不一致。必须同步写回，WebView 才能带着同样有效的会话访问 EAMS。
     */
    fun restore() {
        if (!hasSession()) return
        val cookies = store.hpuCookies.orEmpty()
        cookieJar.seed(SchoolHosts.UIA, cookies)
        syncToWebView(cookies)
    }

    /**
     * 把会话 Cookie 写回 WebView 的 CookieManager。
     *
     * 按 Cookie 名归到最可能的域，避免父域（hpu.edu.cn）与子域（zhjw.hpu.edu.cn）同名 Cookie
     * 互相覆盖/重复发送 —— 那会让服务端取到错误的会话值。
     * 网关票据（HuauAuthName 等）需要各子域都有，因此写入 shared 并在每个域都设置一次。
     */
    private fun syncToWebView(cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val zhjw = mutableListOf<String>()
            val uia = mutableListOf<String>()
            val webvpn = mutableListOf<String>()
            val shared = mutableListOf<String>()
            cookieHeader.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { kv ->
                when (kv.substringBefore('=').trim()) {
                    "JSESSIONID", "GSESSIONID", "srv_id" -> zhjw += kv
                    "CASTGC", "CAS_TICKET", "X_CAPTCHA" -> uia += kv
                    "PHPSESSID" -> webvpn += kv
                    else -> shared += kv
                }
            }
            var written = 0
            var kept = 0
            fun put(url: String, cookies: List<String>) {
                // 已存在同名同值就**不覆盖**：登录成功后 CookieManager 里本来就是最新的一份，
                // 再写一遍会把 Path 从服务端原值（可能 /eams）改成 /，产生同名双份 Cookie，
                // 服务端取到哪一份就不确定了（真机 18:38：写入成功但学期页仍是空壳）。
                val existing = cm.getCookie(url).orEmpty()
                val have = existing.split(';').map { it.trim() }
                    .filter { it.contains('=') }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                cookies.forEach { kv ->
                    val name = kv.substringBefore('=').trim()
                    val value = kv.substringAfter('=').trim()
                    if (have[name] == value) {
                        kept++
                        return@forEach
                    }
                    cm.setCookie(url, "$kv; Path=/")
                    written++
                }
            }
            put("https://zhjw.hpu.edu.cn/", zhjw + shared)
            put("https://uia.hpu.edu.cn/", uia + shared)
            put("https://webvpn.hpu.edu.cn/", webvpn + shared)
            if (written > 0) cm.flush()
            com.zzupocket.app.core.CrashLog.i(
                "session",
                "cookie synced to WebView: zhjw=${zhjw.size} uia=${uia.size} " +
                    "webvpn=${webvpn.size} shared=${shared.size} written=$written kept=$kept",
            )
        }.onFailure {
            com.zzupocket.app.core.CrashLog.w("session", "cookie sync to WebView failed: ${it.message}")
        }
    }

    /**
     * 从 WebView 的 CookieManager 导入会话。
     * @param cookieHeader `CookieManager.getCookie(url)` 的 `k=v; k2=v2` 字符串
     */
    fun adoptFromWebView(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val names = cookieHeader.split(';').map { it.trim().substringBefore('=').trim() }
        // 统一认证 TGT（CASTGC）是标准凭证；走 WebVPN 时学校把它收口到代理，
        // 此时落的是深信服 WebVPN 票据 wengine_vpn_ticket*。任取其一即视为登录成功。
        val hasTgt = names.any { it == HpuCasClient.COOKIE_TGT }
        val hasVpnTicket = names.any {
            it.startsWith("wengine_vpn_ticket") || it == "webvpn_ticket" || it == "CAS_TICKET"
        }
        // 学校实测（2026-11 真机日志）：没有 CASTGC，靠 HuauAuthName(WebVPN 网关) +
        // JSESSIONID/GSESSIONID(EAMS 会话) 穿过认证。这些都是有效会话的可靠标志。
        val hasEamsSession = names.any {
            it == "JSESSIONID" || it == "GSESSIONID" || it == "HuauAuthName"
        }
        if (!hasTgt && !hasVpnTicket && !hasEamsSession) return false
        store.hpuCookies = cookieHeader
        store.hpuLoginAt = System.currentTimeMillis()
        cookieJar.seed(SchoolHosts.UIA, cookieHeader)
        // 登录刚采到的 Cookie 也按域规范化写回 CookieManager：避免父子域同名 Cookie 冲突，
        // 保证随后抓取用的 WebView 拿到的是同一份、按域正确的会话。
        syncToWebView(cookieHeader)
        com.zzupocket.app.core.CrashLog.i(
            "session",
            "adopted: tgt=$hasTgt vpnTicket=$hasVpnTicket eamsSession=$hasEamsSession names=$names",
        )
        return true
    }

    /**
     * 会话是否可用于抓课表。
     *
     * 只做**本地判定**：WebView 登录成功后落在 `zhjw/eams/homeExt.action`，
     * 并拿到 JSESSIONID/GSESSIONID/HuauAuthName 等教务会话 cookie —— 这是登录成功的铁证。
     *
     * ⚠️ 这里**不再发任何网络请求**（2026-11-19 18:38 真机日志）：
     * 原先每次同步前都拿 OkHttp 直连一次 `eams/login.action` 做探测，而这条链路在已登录状态下
     * 会重新走一遍 CAS 兑票并签发新的 EAMS 会话；紧接着 WebView 拿旧 JSESSIONID 抓学期页，
     * 就只剩 `len=59` 的空壳（服务端 JSP 提前 return），重试则直接弹回 `loginExt.action`。
     * 探测结论（`bounced=false`）与 WebView 结论（空壳/弹回）相反，正是"探测本身把会话换掉了"。
     *
     * 真正的网络探测降级为 [probeNetwork]，只在诊断页手动触发。
     */
    suspend fun isValid(): Boolean = withContext(Dispatchers.IO) {
        if (!hasSession()) {
            com.zzupocket.app.core.CrashLog.w("session", "isValid: no session stored")
            return@withContext false
        }
        val names = store.hpuCookies.orEmpty().split(';').map { it.trim().substringBefore('=').trim() }
        val hasEamsSession = names.any {
            it == "JSESSIONID" || it == "GSESSIONID" || it == "HuauAuthName" ||
                it == HpuCasClient.COOKIE_TGT || it == "CAS_TICKET" ||
                it.startsWith("wengine_vpn_ticket")
        }
        com.zzupocket.app.core.CrashLog.i("session", "isValid(local)=$hasEamsSession names=$names")
        hasEamsSession
    }

    /**
     * 网络探测（**仅诊断用，不要放进同步流程**）。
     * 拿持久化 Cookie 直连教务入口，看最终落在 homeExt 还是被弹回登录页。
     * @return 一行结论，直接显示/记录用
     */
    suspend fun probeNetwork(): String = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext "未登录（没有保存的会话）"
        runCatching {
            val request = Request.Builder()
                .url("${HpuEamsClient.BASE}/eams/login.action")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val bounced = body.contains("loginExtForm") || body.contains("从平台登录") ||
                    body.contains("webvpn") || body.contains("统一身份认证")
                val summary = "code=${response.code} finalUrl=${response.request.url} " +
                    "bodyLen=${body.length} bounced=$bounced"
                com.zzupocket.app.core.CrashLog.i("session", "probe login.action: $summary (diagnostic only)")
                summary
            }
        }.getOrElse {
            com.zzupocket.app.core.CrashLog.w("session", "probe login.action exception (ignored): ${it.message}")
            "探测失败：${it.message}"
        }
    }

    /** 退出登录：清掉持久化会话与内存 Cookie */
    fun logout() {
        store.hpuCookies = null
        store.hpuLoginAt = 0L
        cookieJar.clear()
    }

    private object SchoolHosts {
        const val UIA = "uia.hpu.edu.cn"
    }
}
