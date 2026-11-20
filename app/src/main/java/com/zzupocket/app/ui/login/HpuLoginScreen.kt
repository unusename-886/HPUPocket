package com.zzupocket.app.ui.login

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.CrashLog
import com.zzupocket.app.data.auth.HpuCasClient
import kotlinx.coroutines.launch

/**
 * 河南理工大学统一认证登录页。
 *
 * 与郑大 CAS（App 内表单 + RSA 加密）不同，HPU 强制图形验证码（算术题/字母），
 * 最稳妥的方式就是**在 App 内 WebView 打开学校自己的登录页**，让用户看验证码手输 ——
 * 密码始终输在学校页面，App 不接触、不保存。登录成功后：
 *  1. WebView 会被重定向到教务（直连 zhjw 或经 WebVPN 重写域名均可）；
 *  2. 从 CookieManager 收集 `*.hpu.edu.cn` 的会话 Cookie；
 *  3. 交给 [AppGraph.hpuSessionStore] 加密持久化 + 灌回内存 CookieJar。
 *
 * 捕获策略（双保险）：
 *  - 自动：URL 命中 `hpu.edu.cn` 域下的 `/eams/` 或 WebVPN 兑票 `ticket=ST`；
 *  - 手动：顶栏「完成」按钮 —— 用户已看到教务首页但自动捕获没触发时兜底。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HpuLoginScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showWebView by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // WebView 回调可能多次命中教务域名，用普通变量守护只导入一次
    var captured by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = showWebView) { showWebView = false }

    /**
     * 收集会话并导入。
     * @param forced true=用户点了「完成」按钮，跳过 URL 判断直接收 Cookie（最可靠兜底）
     */
    fun performCapture(url: String?, forced: Boolean) {
        if (captured || done) return
        if (!forced) {
            val atEams = url != null && url.contains("hpu.edu.cn") && url.contains("/eams/")
            val vpnTicketOk = url != null && url.contains("webvpn") && url.contains("ticket=ST")
            if (!atEams && !vpnTicketOk) return
            CrashLog.i("login", "auto capture atEams=$atEams vpnTicket=$vpnTicketOk url=$url")
        } else {
            CrashLog.i("login", "manual capture triggered at url=${url ?: "n/a"}")
        }
        captured = true
        val collect = {
            val cookies = collectHpuCookies()
            CrashLog.i("login", "capture cookies len=${cookies.length} names=${cookieNames(cookies)}")
            if (AppGraph.hpuSessionStore.adoptFromWebView(cookies)) {
                CrashLog.i("login", "adopt OK -> done")
                done = true
                scope.launch { onDone() }
            } else {
                captured = false
                error = "还没拿到登录会话。请确认已进入教务首页（能看到课程表/教学周），再点「完成」。"
                CrashLog.w("login", "adopt failed: no usable session cookie")
            }
        }
        // 自动捕获时跳转刚落地，CookieManager 可能还需一帧才写完；手动点击时 Cookie 早已落定。
        if (forced) collect() else webViewRef?.postDelayed({ collect() }, 400) ?: collect()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("河南理工大学统一身份认证") },
                navigationIcon = {
                    IconButton(onClick = { showWebView = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
                actions = {
                    // 手动兜底：用户已在 WebView 里看到教务首页，直接点完成登录
                    if (showWebView && !done) {
                        TextButton(onClick = {
                            performCapture(webViewRef?.url?.toString(), forced = true)
                        }) { Text("完成登录") }
                    }
                },
            )
        },
    ) { padding ->
        if (done) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("登录成功 ✓", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "会话已保存，正在进入课表…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (!showWebView) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "登录河南理工大学统一身份认证后即可查看课表。密码输在学校页面上，App 不保存密码。\n\n" +
                        "进入教务首页后，若页面没有自动跳回，请点顶栏「完成登录」。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { showWebView = true; error = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("打开登录页")
                }
                Spacer(Modifier.height(8.dp))
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { context ->
                    WebView(context).apply {
                        webViewRef = this
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // WebVPN 跨 uia/webvpn/zhjw 多个子域来回跳并写 Cookie，
                        // 必须允许第三方 Cookie，否则登录后跳回教务会因 Cookie 丢失卡在 VPN 页。
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // WebVPN 对 UA 敏感：用标准移动端 Chrome UA，别用 WebView 默认（带 ;wv），
                        // 与用户手机浏览器能走通的环境保持一致。
                        settings.userAgentString = settings.userAgentString.replace(";wv", "")
                        // 从干净状态登录：清掉 WebView 里可能残留的过期会话
                        CookieManager.getInstance().removeAllCookies(null)
                        var lastUrl = ""

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                // 记录每次跳转，便于导出日志定位卡在 VPN/教务/CAS 哪一步
                                val u = request.url?.toString().orEmpty()
                                if (u != lastUrl) {
                                    lastUrl = u
                                    CrashLog.i("login", "nav: $u")
                                }
                                return false // 全部在 WebView 内跳转，跟完官网 → WebVPN → CAS → 教务链
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (url != null && url != lastUrl) {
                                    lastUrl = url
                                    CrashLog.i("login", "page: $url")
                                }
                                performCapture(url, forced = false)
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                performCapture(url, forced = false)
                            }
                        }

                        // 从学校官网的「教务管理系统」入口起步，让它自然走
                        // zhjw → WebVPN(带 redirect_authurl) → UIA 登录 → 回教务，
                        // 而不是直接打 CAS+webvpn回调（那样会卡在 VPN 页）。
                        CrashLog.i("login", "start from ${HpuCasClient.ENTRY_JW}")
                        loadUrl(HpuCasClient.ENTRY_JW)
                    }
                },
            )
        }
    }
}

/** 只取 Cookie 名字（脱敏），便于日志判断拿到了哪种会话 */
private fun cookieNames(header: String): String =
    header.split(';').mapNotNull { part ->
        val eq = part.trim().indexOf('=')
        if (eq > 0) part.trim().substring(0, eq) else null
    }.joinToString(",")

/** 从 CookieManager 收集河南理工所有子域的会话 Cookie（uia/webvpn/zhjw 跨子域兑票都要） */
private fun collectHpuCookies(): String {
    val cm = CookieManager.getInstance()
    // 顺序很关键：同名 cookie 后写覆盖先写。把课表所在的 zhjw 放最后，
    // 保证 JSESSIONID / HuauAuthName 取的是教务子域的正确值，不被父域同名 cookie 覆盖。
    val domains = listOf("hpu.edu.cn", "uia.hpu.edu.cn", "webvpn.hpu.edu.cn", "zhjw.hpu.edu.cn")
    val merged = LinkedHashMap<String, String>()
    domains.forEach { domain ->
        val url = "https://$domain/"
        val raw = cm.getCookie(url).orEmpty()
        CrashLog.i("login", "cookie domain=$domain raw=${cookieNames(raw)}")
        raw.split(';').forEach { part ->
            val t = part.trim()
            val eq = t.indexOf('=')
            if (eq > 0) merged[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
        }
    }
    return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
