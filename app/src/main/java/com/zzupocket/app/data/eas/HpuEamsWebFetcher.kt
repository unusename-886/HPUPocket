package com.zzupocket.app.data.eas

import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zzupocket.app.core.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * 用 WebView 抓取河南理工教务页面 HTML。
 *
 * 为什么不用 OkHttp（真机日志 2026-11-19 证实）：
 *  - 校外访问教务被 WebVPN 网关接管，登录在 WebView 里完成，会话上下文只活在
 *    WebView/CookieManager 里；OkHttp 带导出的 Cookie 直连会被 302 弹回 loginExt.action。
 *
 * 真机日志逐步暴露的失败模式与对策：
 *  1. `onPageFinished` 抢跑 —— DOM 还是空壳（len=59）就抓了 outerHTML。
 *     ⇒ [fetchLocked] 不依赖单次回调，改为**轮询**直到 HTML 出现 [expectMarker] 真实内容。
 *  2. 会话偶发弹回 loginExt.action —— 单次抓取直接失败。
 *     ⇒ 整体 [MAX_ATTEMPTS] 次重试；[mutex] 串行化，避免连续两次同步时 loadUrl 互相覆盖。
 *  3. 页面自带 `searchTable()` 依赖外链 jQuery/bg —— 真机 17:59 日志实锤
 *     `searchTable() -> "err:jQuery is not defined"`（抓取 WebView 里脚本没加载成功）。
 *     ⇒ [fetchCourseTableViaPage] 不再调用它，改为在已登录 WebView 页面上下文里用**原生 fetch()**
 *       同源 POST 复刻它的参数逻辑（HAUTPocket 同源请求思路），零外链依赖；再轮询 window 结果。
 *  4. ids 是 if/else 二选一（std→第一个，class→第二个），不能两个都传（17:14 空表根因）。
 *
 * 关键实现细节：evaluateJavascript 的回调值是**JSON 编码的字符串字面量**（外层带引号+
 * 转义），必须先 [unwrapJsString] 还原；否则 `JSONObject(raw)` 会抛
 * "Value ... of type java.lang.String cannot be converted to JSONObject"（真机证实）。
 *
 * 抓到 HTML 后交给纯 Kotlin 的 [HpuScheduleParser] 解析，解析器无需改动。
 */
class HpuEamsWebFetcher(private val appContext: Context) {

    /** 保活的 WebView：复用它的 Cookie 上下文与登录页一致 */
    private var webView: WebView? = null

    /** 串行化：同一时刻只允许一次抓取，防止并发 loadUrl 互相覆盖 */
    private val mutex = Mutex()

    /**
     * 抓学期页 `courseTableForStd.action`。
     * @param expectMarker HTML 必须包含此标记才算真实内容就绪（学期页传 semesterCalendar）
     */
    suspend fun fetch(
        url: String,
        postFields: List<Pair<String, String>>? = null,
        expectMarker: String? = null,
    ): String? = withContext(Dispatchers.Main) {
        mutex.withLock { fetchLocked(url, postFields, expectMarker) }
    }

    /**
     * 课表页专用：在已登录 WebView 页面上下文里用原生 fetch() 同源 POST 抓课表 HTML。
     * 不依赖页面外链 jQuery/bg（真机证实 searchTable() 会报 jQuery is not defined）。
     * @param semesterPageUrl 学期页 URL，用于保证 fetch 的 origin 与会话上下文正确
     * @param semesterId 当前学期 id（来自学期页解析）
     * @param taskIds 学期页解析出的 ids（if/else 二选一：std 用第一个、class 用第二个）
     */
    suspend fun fetchCourseTableViaPage(
        semesterPageUrl: String,
        semesterId: Int,
        taskIds: List<String>,
    ): String? = withContext(Dispatchers.Main) {
        mutex.withLock { fetchCourseTableLocked(semesterPageUrl, semesterId, taskIds) }
    }

    // ---------------------------------------------------------------- 会话自检

    /**
     * 抓取前先确认「WebView 侧」会话是否成立：加载登录成功后必然落地的 `homeExt.action`。
     *
     * 为什么单独做这一步（真机 18:38 日志暴露的问题）：
     *  - OkHttp 探测 `probe: code=200 finalUrl=homeExt bounced=false` 说明**持久化的 Cookie 有效**；
     *  - 可同一时刻 WebView 抓学期页却拿到 `len=59` 空壳，再重试直接弹回 `loginExt.action`。
     *  两个通道结论相反时，必须先判断是「WebView 没带上会话」还是「页面本身空壳」，
     *  否则 3 次重试（每次 9s）只是在同一个错误上反复烧时间。
     *
     * @return true=会话成立；false=被弹回登录/网关（明确失效）；null=超时或无法判定（不下结论）
     */
    suspend fun warmUp(): Boolean? = withContext(Dispatchers.Main) {
        mutex.withLock { warmUpLocked() }
    }

    private suspend fun warmUpLocked(): Boolean? {
        val wv = webView ?: createWebView().also { webView = it }
        val url = "${HpuEamsClient.BASE}/eams/homeExt.action"
        var finishedUrl: String? = null

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String?) {
                finishedUrl = loadedUrl
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    CrashLog.w("eas", "warmup http error: ${errorResponse?.statusCode} $finishedUrl")
                }
            }
        }

        logContext(wv, url, tag = "warmup")
        runCatching { wv.loadUrl(url) }.onFailure {
            CrashLog.w("eas", "warmup load exception: ${it.message}")
            return null
        }

        val deadline = SystemClock.elapsedRealtime() + WARMUP_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_INTERVAL_MS)
            val f = finishedUrl ?: continue
            if (isLoginOrVpnPage(f)) {
                CrashLog.w("eas", "warmup: webview session DEAD -> $f")
                return false
            }
            val html = evalHtml(wv)
            CrashLog.i("eas", "warmup: session ALIVE url=$f len=${html?.length ?: -1}")
            return true
        }
        CrashLog.w("eas", "warmup: timeout, session state unknown")
        return null
    }

    /**
     * 打一次「这次请求到底带了什么」的快照：URL、CookieManager 里该域有哪些 Cookie、
     * 页面 readyState/title/body 长度。空壳页面排查全靠它——不用再猜。
     */
    private fun logContext(wv: WebView, url: String, tag: String) {
        runCatching {
            val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
            val names = cookies.split(';').map { it.trim().substringBefore('=').trim() }
                .filter { it.isNotEmpty() }
            CrashLog.i(
                "eas",
                "$tag ctx: url=$url cookies=${names.size}[${names.joinToString(",")}]",
            )
        }
    }

    /** 空壳/异常页快照：readyState、title、body 长度；正文很短时直接整段打出来（通常只有几十字） */
    private suspend fun logSnapshot(wv: WebView, html: String, tag: String) {
        val snap = unwrapJsString(evalJs(wv, JS_SNAPSHOT)).orEmpty()
        if (html.length <= TINY_BODY_MAX) {
            CrashLog.w("eas", "$tag tiny body(${html.length}) snap=$snap raw=${html.replace(Regex("\\s+"), " ")}")
        } else {
            CrashLog.w("eas", "$tag snapshot=$snap len=${html.length}")
        }
    }

    // ---------------------------------------------------------------- 学期页

    private suspend fun fetchLocked(
        url: String,
        postFields: List<Pair<String, String>>?,
        expectMarker: String?,
    ): String? {
        val wv = webView ?: createWebView().also { webView = it }
        var lastFinishedUrl: String? = null

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                CrashLog.i("eas", "webview onPageStarted: $startedUrl (want $url)")
            }

            override fun onPageFinished(view: WebView, loadedUrl: String?) {
                lastFinishedUrl = loadedUrl
                CrashLog.i("eas", "webview onPageFinished: $loadedUrl (want $url)")
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    CrashLog.w(
                        "eas",
                        "webview main frame http ${errorResponse?.statusCode} ${request.url}",
                    )
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    CrashLog.w("eas", "webview main frame error: ${error?.errorCode} ${error?.description}")
                }
            }
        }

        var lastHtmlLen = 0
        for (attempt in 1..MAX_ATTEMPTS) {
            lastFinishedUrl = null
            CrashLog.i("eas", "webview attempt $attempt/$MAX_ATTEMPTS -> $url")
            logContext(wv, url, tag = "fetch#$attempt")
            try {
                if (postFields == null) {
                    wv.loadUrl(url)
                } else {
                    val body = postFields.joinToString("&") { (k, v) ->
                        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                    }
                    wv.postUrl(url, body.toByteArray(Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                CrashLog.e("eas", "webview load exception: ${t.message}")
                delay(RETRY_DELAY_MS)
                continue
            }

            val deadline = SystemClock.elapsedRealtime() + PAGE_TIMEOUT_MS
            var bounced = false
            var tinyLogged = false
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(POLL_INTERVAL_MS)

                val finished = lastFinishedUrl
                if (finished != null && isLoginOrVpnPage(finished)) {
                    CrashLog.w("eas", "webview bounced to login/vpn: $finished")
                    bounced = true
                    break
                }

                val html = evalHtml(wv) ?: continue
                lastHtmlLen = html.length

                if (isLoginBody(html)) {
                    CrashLog.w("eas", "webview body is login page, retry")
                    bounced = true
                    break
                }

                val ready = if (expectMarker != null) {
                    html.contains(expectMarker, ignoreCase = true)
                } else {
                    html.length >= MIN_READY_LEN
                }
                if (ready) {
                    CrashLog.i("eas", "webview ready: len=${html.length} marker=$expectMarker")
                    return html
                }

                // 空壳现场只记一次：59 字符这种页面全文打出来就能定性（空白错误页/iframe 壳/被拦）
                if (!tinyLogged && html.length <= TINY_BODY_MAX) {
                    tinyLogged = true
                    logSnapshot(wv, html, tag = "fetch#$attempt after ${SystemClock.elapsedRealtime() - (deadline - PAGE_TIMEOUT_MS)}ms")
                }
            }
            CrashLog.w(
                "eas",
                "webview attempt $attempt ${if (bounced) "bounced" else "not ready"} (lastLen=$lastHtmlLen), retry",
            )
            delay(RETRY_DELAY_MS)
        }

        CrashLog.w("eas", "webview fetch give up: $url (lastLen=$lastHtmlLen)")
        return null
    }

    // ---------------------------------------------------------------- 课表页（原生同源 fetch，零外链依赖）

    /**
     * 在已登录 WebView 里用原生 fetch() 复刻页面 searchTable() 的提交：
     *  - 不调用页面 searchTable()（真机证实依赖的 jQuery 未加载，报 jQuery is not defined）；
     *  - 参数与页面 JS 一致：ignoreHead=1 / setting.kind=std / startWeek="" / project.id=1 /
     *    semester.id=<当前学期> / ids=<二选一>；
     *  - ids 严格按页面 if/else：kind=="std" 用第一个，否则用第二个（不能两个都传，否则空表）；
     *  - fetch 用相对 URL，同源自动带 WebView 会话 Cookie，不走 OkHttp（避免 WebVPN 弹回）。
     */
    private suspend fun fetchCourseTableLocked(
        semesterPageUrl: String,
        semesterId: Int,
        taskIds: List<String>,
    ): String? {
        val wv = webView ?: createWebView().also { webView = it }

        // 1) 确保学期页已加载（fetch 的 origin/会话上下文要正确）。以页面表单元素为就绪标志，
        //    不再依赖 searchTable 函数或 jQuery。
        if (evalJs(wv, JS_HAS_SEMESTER_FORM).asBool() != true) {
            CrashLog.i("eas", "page-fetch: load semester page for native fetch")
            var lastFinishedUrl: String? = null
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String?) {
                    lastFinishedUrl = loadedUrl
                }
            }
            try {
                wv.loadUrl(semesterPageUrl)
            } catch (t: Throwable) {
                CrashLog.e("eas", "page-fetch load exception: ${t.message}")
                return null
            }
            val navDeadline = SystemClock.elapsedRealtime() + PAGE_TIMEOUT_MS
            var ready = false
            while (SystemClock.elapsedRealtime() < navDeadline) {
                delay(POLL_INTERVAL_MS)
                if (lastFinishedUrl != null && isLoginOrVpnPage(lastFinishedUrl)) {
                    CrashLog.w("eas", "page-fetch bounced: $lastFinishedUrl")
                    return null
                }
                if (evalJs(wv, JS_HAS_SEMESTER_FORM).asBool() == true) {
                    ready = true
                    break
                }
            }
            if (!ready) {
                CrashLog.w("eas", "page-fetch: semester form not found after load")
                return null
            }
        }

        // 2) ids 按页面 if/else 二选一：学生课表(std) 用第一个，班级课表用第二个。
        //    真机页面默认 setting.kind=std，且这是学生账号 → 取第一个。
        val stdId = taskIds.firstOrNull().orEmpty()
        val classId = taskIds.getOrNull(1) ?: stdId
        if (stdId.isEmpty()) {
            CrashLog.w("eas", "page-fetch: no taskIds from semester page")
            return null
        }
        CrashLog.i("eas", "page-fetch: native fetch semester=$semesterId stdId=$stdId classId=$classId")

        // 3) 发起原生 fetch（异步 POST），结果写入 window.__hpuKbResult
        wv.evaluateJavascript(kbFetchJs(semesterId, stdId, classId)) { raw ->
            CrashLog.i("eas", "page-fetch: fetch start -> ${unwrapJsString(raw)?.take(80)}")
        }

        // 4) 轮询 window.__hpuKbResult 直到 done/error，再从 body 找 TaskActivity
        val deadline = SystemClock.elapsedRealtime() + FETCH_RESULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_INTERVAL_MS)
            val state = unwrapJsString(evalJs(wv, JS_KB_STATE))
            when (state) {
                "done" -> {
                    val body = evalHtml(wv, JS_KB_BODY)
                    if (body != null && body.contains("TaskActivity", ignoreCase = true)) {
                        CrashLog.i("eas", "page-fetch: got course table len=${body.length}")
                        return body
                    }
                    CrashLog.w(
                        "eas",
                        "page-fetch: done but no TaskActivity (len=${body?.length ?: 0}) " +
                            "head=${body?.take(300)?.replace(Regex("\\s+"), " ")}",
                    )
                    return null
                }
                "error" -> {
                    val err = evalHtml(wv, JS_KB_BODY)
                    CrashLog.w("eas", "page-fetch: fetch error -> ${err?.take(200)}")
                    return null
                }
            }
        }
        CrashLog.w("eas", "page-fetch: timeout waiting fetch result")
        return null
    }

    // ---------------------------------------------------------------- JS 求值（统一 unwrap）

    /** evaluateJavascript 回调返回 JSON 字符串字面量（外层带引号+转义），还原成原文 */
    private fun unwrapJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching {
            JSONObject("{\"v\":$raw}").getString("v")
        }.getOrElse { raw.trim('"') }
    }

    /**
     * 把 evaluateJavascript 的原始返回解析为布尔。
     * 页面就绪标志 [JS_HAS_SEMESTER_FORM] 返回 'yes'/'no'，这里同时兼容 'true'/'false'；
     * 曾因只认 true/false 导致 asBool() 恒为 null、页面通道 100% 失败（真机已复现并修复）。
     */
    private fun String?.asBool(): Boolean? = when (unwrapJsString(this)) {
        "true", "yes" -> true
        "false", "no" -> false
        else -> null
    }

    /** 在主线程求值 JS，超时返回 null，避免 callback 不回调导致协程永久挂起 */
    private suspend fun evalJs(wv: WebView, js: String): String? = withTimeoutOrNull(EVAL_TIMEOUT_MS) {
        suspendCancellableCoroutine<String?> { cont ->
            wv.evaluateJavascript(js) { raw -> cont.resume(raw) }
        }
    }

    /** 求 HTML：JS 返回纯字符串，经 evaluateJavascript 编码后 unwrap 一次即得原文 */
    private suspend fun evalHtml(wv: WebView, js: String = JS_COLLECT): String? =
        unwrapJsString(evalJs(wv, js))

    private fun createWebView(): WebView = WebView(appContext.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 与登录页一致：去掉 ;wv，避免 WebVPN 把 WebView 当异常客户端
        settings.userAgentString = settings.userAgentString.replace(";wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    private fun isLoginOrVpnPage(url: String?): Boolean {
        if (url == null) return false
        return url.contains("/loginExt.action") ||
            url.contains("/login.action") ||
            url.contains("webvpn.hpu.edu.cn/login")
    }

    private fun isLoginBody(html: String): Boolean =
        html.contains("loginExtForm") ||
            html.contains("从平台登录") ||
            html.contains("统一身份认证")

    companion object {
        /** 主文档 + 所有同源 frame 的 outerHTML（直接返回纯字符串，不再 JSON.stringify 二次包装） */
        private val JS_COLLECT = """
            (function(){
              var out = (document.documentElement && document.documentElement.outerHTML) || '';
              try {
                var fs = window.frames;
                for (var i=0;i<fs.length;i++) {
                  try {
                    var d = fs[i].document;
                    if (d && d.documentElement) out += '\n' + d.documentElement.outerHTML;
                  } catch(e) {}
                }
              } catch(e) {}
              return out;
            })()
        """.trimIndent()

        /** 学期页表单就绪标志：只查静态 DOM 元素，不依赖任何外链脚本 */
        private const val JS_HAS_SEMESTER_FORM =
            "(function(){return document.getElementById('semesterForm') ? 'yes' : 'no';})()"

        private const val JS_KB_STATE =
            "(function(){var r=window.__hpuKbResult; return r ? r.state : 'pending';})()"

        private const val JS_KB_BODY =
            "(function(){var r=window.__hpuKbResult; return r ? (r.body||'') : '';})()"

        /**
         * 原生同源 fetch，完全复刻页面 searchTable() 的参数，不调用它本体（避开 jQuery 依赖）。
         * 结果写入 window.__hpuKbResult = {state, body}，原生侧轮询读取。
         * - setting.kind 读页面 select 的真实值（默认 std）；ids 随之二选一。
         * - 相对 URL 继承当前 origin，会话 Cookie 由 WebView 自动带上。
         */
        private fun kbFetchJs(semesterId: Int, stdId: String, classId: String): String = """
            (function(){
              window.__hpuKbResult = {state:'pending', body:''};
              try {
                var sel = document.getElementById('courseTableType');
                var kind = (sel && sel.value) ? sel.value : 'std';
                var ids = (kind === 'std') ? '$stdId' : '$classId';
                var body = 'ignoreHead=1'
                  + '&setting.kind=' + encodeURIComponent(kind)
                  + '&startWeek='
                  + '&project.id=1'
                  + '&semester.id=$semesterId'
                  + '&ids=' + encodeURIComponent(ids);
                fetch('courseTableForStd!courseTable.action', {
                  method: 'POST',
                  credentials: 'same-origin',
                  headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'},
                  body: body
                }).then(function(resp){
                  return resp.text().then(function(t){
                    window.__hpuKbResult = {state:'done', body:t};
                  });
                }).catch(function(e){
                  window.__hpuKbResult = {state:'error', body:String(e && e.message || e)};
                });
              } catch (e) {
                window.__hpuKbResult = {state:'error', body:String(e && e.message || e)};
              }
              return 'sent';
            })()
        """.trimIndent()

        /** 页面快照：`readyState|title|body.innerHTML 长度`，用于判定空壳页究竟停在哪一步 */
        private const val JS_SNAPSHOT =
            "(function(){try{" +
                "var b=document.body;" +
                "return document.readyState+'|'+(document.title||'').substring(0,40)+'|'+((b&&b.innerHTML)?b.innerHTML.length:0);" +
                "}catch(e){return 'snapshot-err:'+e;}})()"

        private const val MAX_ATTEMPTS = 3
        /** 会话自检窗口：homeExt 是登录落地页，正常 1~2s 就完成 */
        private const val WARMUP_TIMEOUT_MS = 6_000L
        /** 小于等于这个长度视为空壳页，直接把全文打进日志 */
        private const val TINY_BODY_MAX = 300
        private const val PAGE_TIMEOUT_MS = 9_000L
        /** 原生 fetch 结果等待窗口：同源请求通常 <1s，给 6s 足够且失败时能快速回退 */
        private const val FETCH_RESULT_TIMEOUT_MS = 6_000L
        private const val POLL_INTERVAL_MS = 250L
        private const val EVAL_TIMEOUT_MS = 1_500L
        private const val RETRY_DELAY_MS = 500L
        private const val MIN_READY_LEN = 500
    }
}
