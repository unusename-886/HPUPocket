package com.zzupocket.app.data.eas

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zzupocket.app.core.CrashLog
import com.zzupocket.app.core.SchoolConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 在**已登录的 WebView 里**提交空闲教室查询表单，取回服务端渲染的结果页 HTML。
 *
 * ## 三次真机迭代换来的三条规则
 *
 * 1. **不要用 `fetch`**（0.2.3 的坑）：校外 WebVPN 链路上 POST 的 302 会跳到 `http://zhjw…`，
 *    https 页面里的 fetch 遇到降级/跨源重定向一律 `TypeError: Failed to fetch`，连状态码都拿不到。
 *    改成 `form.submit()` 做真·顶层导航，跳转/302/http 都能走完。
 * 2. **不要自己拼表单**（0.2.4 的坑）：只带我们认识的 12 个字段，教务直接回
 *    「出错了 / Error happened」——页面表单里还有 `semester.id` 等必填 hidden。
 *    现在按名字填进页面已有的 select/input，缺的才补 hidden，交出去的是完整表单。
 * 3. **User-Agent 必须去掉 `;wv`**：WebVPN 会把带 `;wv` 的 UA 当异常客户端，会话判定和跳转都变。
 *    课表那套（[HpuEamsWebFetcher]）一直这么干，这里之前漏了。
 *
 * 另外：撞到 WebVPN 登录页时先走一次 `huauauth/caslogin` 静默续期（TGT 还有效时不弹密码），
 * 成功了再重新打开目标页，避免"每次冷启动第一次查询都要求重新登录"。
 */
class HpuRoomWebFetcher(private val context: Context) {

    sealed interface Outcome {
        data class Ok(val html: String) : Outcome

        /** [needLogin] = 真的没会话了（登录页），UI 要引导去登录；否则是网络/脚本错误 */
        data class Failed(val message: String, val needLogin: Boolean = false) : Outcome
    }

    /**
     * 先打开 [pageUrl]（建立同源上下文并确认会话有效），再提交表单到 [postUrl]，返回结果页 HTML。
     *
     * @param pageUrl 必须是同源页面（教务的空闲教室表单页），不能是 about:blank
     * @param postUrl 相对路径即可（如 `free!search.action`）。校外经 WebVPN 时页面会落在
     *   `webvpn.hpu.edu.cn/https/<编码>/…` 的改写地址上，相对路径按页面自身地址解析，两种部署都对。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun postInPage(
        pageUrl: String,
        postUrl: String,
        form: Map<String, String>,
        loadTimeoutMs: Long = 20_000L,
        fetchTimeoutMs: Long = 25_000L,
        extractTimeoutMs: Long = 8_000L,
    ): Outcome = withContext(Dispatchers.Main) {
        val bridge = Bridge()
        var web: WebView? = null
        try {
            val gate = Gate()
            val view = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                // WebVPN 用 UA 里的 ";wv" 识别 WebView 并把会话判定/跳转搞歪，课表那边早就去掉了
                settings.userAgentString = settings.userAgentString.replace(";wv", "")
                // 结果表不需要图片，顺手省流量（老接口，但各版本都还生效）
                @Suppress("DEPRECATION")
                settings.blockNetworkImage = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, url: String?) = gate.onFinished(url.orEmpty())
                }
                addJavascriptInterface(bridge, BRIDGE)
            }
            web = view

            // ── 第一步：打开表单页，确认会话 ────────────────────────────────
            var landing = gate.arm()
            view.loadUrl(pageUrl)
            var finalUrl = withTimeoutOrNull(loadTimeoutMs) { landing.await() }

            if (finalUrl == null && isLoginUrl(gate.last)) {
                // 撞到 WebVPN 登录页：先静默续期一次（TGT 还有效时不会弹密码）
                CrashLog.w(TAG, "webview hit login, try silent re-auth: ${gate.last}")
                val recover = gate.arm()
                view.loadUrl(URL_CAS_RELOGIN)
                withTimeoutOrNull(12_000L) { recover.await() }
                CrashLog.i(TAG, "webview re-auth landed: ${gate.last}")
                landing = gate.arm()
                view.loadUrl(pageUrl)
                finalUrl = withTimeoutOrNull(loadTimeoutMs) { landing.await() }
            }

            if (finalUrl == null) {
                return@withContext if (isLoginUrl(gate.last)) {
                    CrashLog.w(TAG, "webview stuck on login page: ${gate.last}")
                    Outcome.Failed("教务登录已失效", needLogin = true)
                } else {
                    CrashLog.w(TAG, "webview load timeout, last=${gate.last}")
                    Outcome.Failed("打开教务页面超时，请检查网络")
                }
            }
            CrashLog.i(TAG, "webview landed: $finalUrl")

            // 页面结构快照：表单在顶层还是 frame 里、是不是菜单页，一次日志说清
            view.evaluateJavascript("javascript:$BRIDGE.onProbe($JS_PROBE)", null)
            withTimeoutOrNull(800) { while (bridge.probe == null) delay(60) }
            val probe = bridge.probe.orEmpty()
            bridge.probe?.let { CrashLog.i(TAG, "webview page probe: $it") }

            if (probe.contains("出错了") || probe.contains("Error happened")) {
                // 入口页本身就是错误页：多半缺学年学期（project）上下文。
                // 这时候去首页把「空闲教室」的真实链接抄下来 —— 那串 href 带着教务自己认的参数。
                CrashLog.w(TAG, "entry page is error page, harvest links from homeExt")
                val base = pageUrl.substringBefore("/eams/").ifBlank { SchoolConfig.URL_JW.trimEnd('/') }
                val home = gate.arm()
                view.loadUrl("$base/eams/homeExt.action")
                withTimeoutOrNull(15_000L) { home.await() }
                view.evaluateJavascript("javascript:$BRIDGE.onInfo($JS_LINKS)", null)
                withTimeoutOrNull(1_500L) { while (bridge.info == null) delay(60) }
                bridge.info?.let { CrashLog.w(TAG, "webview links: $it") }
                return@withContext Outcome.Failed("教务入口页报错（缺学年学期上下文），已抓取可用链接")
            }

            // ── 第二步：填页面自己的表单并提交（真导航） ─────────────────────
            gate.submitted = true
            val baseline = gate.count
            CrashLog.i(TAG, "webview submit form -> $postUrl")
            val startedAt = System.currentTimeMillis()
            view.evaluateJavascript(submitScript(postUrl, form), null)
            withTimeoutOrNull(600) { while (bridge.info == null) delay(60) }
            bridge.info?.let { CrashLog.i(TAG, "webview form: $it") }

            val posted = withTimeoutOrNull(fetchTimeoutMs) {
                while (!gate.posted.isCompleted) delay(120)
                gate.posted.await()
            }
            val cost = System.currentTimeMillis() - startedAt
            if (posted == null && gate.count <= baseline) {
                CrashLog.w(TAG, "webview submit no navigation after ${cost}ms, last=${gate.last}")
                return@withContext if (isLoginUrl(gate.last)) {
                    Outcome.Failed("教务登录已失效", needLogin = true)
                } else {
                    Outcome.Failed("提交查询后页面没有反应（教务可能改了表单）")
                }
            }
            CrashLog.i(TAG, "webview result page in ${cost}ms: ${posted ?: gate.last}")

            // ── 第三步：抽取结果页 DOM ──────────────────────────────────────
            delay(400)
            view.evaluateJavascript("javascript:$BRIDGE.onDone($JS_COLLECT)", null)
            val got = withTimeoutOrNull(extractTimeoutMs) {
                while (bridge.html == null) delay(120)
                true
            }
            if (got == null) {
                CrashLog.w(TAG, "webview extract timeout, last=${gate.last}")
                return@withContext Outcome.Failed("结果页读取超时，请重试")
            }
            val html = bridge.html.orEmpty()
            CrashLog.i(TAG, "webview html len=${html.length}")
            if (html.isBlank()) return@withContext Outcome.Failed("教务返回了空内容")
            if (isLoginUrl(gate.last) || FreeRoomParser.isLoginPage(html)) {
                CrashLog.w(TAG, "webview body is login page")
                return@withContext Outcome.Failed("教务登录已失效", needLogin = true)
            }
            Outcome.Ok(html)
        } catch (e: Exception) {
            CrashLog.e(TAG, "webview query crashed", e)
            Outcome.Failed(e.message ?: "WebView 抓取失败")
        } finally {
            runCatching {
                web?.removeJavascriptInterface(BRIDGE)
                web?.stopLoading()
                web?.destroy()
            }
        }
    }

    /** 页面加载/跳转状态机：等待"落地"和"提交后结果页"两段，各自只认第一次非登录页 */
    private class Gate {
        @Volatile var last: String = ""
        @Volatile var count: Int = 0
        @Volatile var submitted: Boolean = false
        private var landing: CompletableDeferred<String>? = null
        val posted = CompletableDeferred<String>()

        fun arm(): CompletableDeferred<String> =
            CompletableDeferred<String>().also { landing = it }

        fun onFinished(url: String) {
            last = url
            count++
            CrashLog.i(TAG, "webview page#$count${if (submitted) " [posted]" else ""}: $url")
            if (isLoginUrl(url)) return
            if (!submitted) {
                landing?.let { if (!it.isCompleted) it.complete(url) }
            } else if (!posted.isCompleted) {
                posted.complete(url)
            }
        }

        private fun isLoginUrl(url: String) = HpuRoomWebFetcher.isLoginUrl(url)

        companion object {
            const val TAG = "freeroom"
        }
    }

    /**
     * 填**教务自己的查询表单**再提交，不要另建一个干净表单。
     *
     * 0.2.4 的教训：自建表单只带我们自己那 12 个字段，教务直接回「出错了 / Error happened」——
     * 表单里还有 `semester.id` 之类的必填 hidden。现在按名字填进页面已有的 select/input，
     * 缺的才补 hidden，交出去的是完整表单；同时把字段清单推给原生，出错时一眼看出差什么。
     */
    private fun submitScript(postUrl: String, form: Map<String, String>): String {
        val fields = form.entries.joinToString(",") { (k, v) ->
            "{n:${JSONObject.quote(k)},v:${JSONObject.quote(v)}}"
        }
        return """
            (function(){
              var target = ${JSONObject.quote(postUrl)};
              var key = target.split('?')[0];
              var forms = document.forms, f = null;
              for (var i = 0; i < forms.length; i++) {
                var a = forms[i].getAttribute('action') || '';
                if (a.indexOf(key) >= 0) { f = forms[i]; break; }
              }
              var created = false;
              if (!f && forms.length) f = forms[0];
              if (!f) {
                f = document.createElement('form');
                f.method = 'POST'; f.action = target; created = true;
                (document.body || document.documentElement).appendChild(f);
              } else if (!f.getAttribute('action')) {
                f.setAttribute('action', target);
              }
              f.method = 'POST';
              var list = [$fields], set = [];
              function pick(n){
                var el = f.elements[n];
                if (el && el.tagName) return el;
                var all = f.querySelectorAll('[name="' + n + '"]');
                return all.length ? all[0] : null;
              }
              function put(n, v){
                var el = pick(n);
                if (!el) {
                  var h = document.createElement('input');
                  h.type = 'hidden'; h.name = n; h.value = v;
                  f.appendChild(h); set.push(n + '=+' + v); return;
                }
                if (el.tagName === 'SELECT') {
                  var hit = false;
                  for (var k = 0; k < el.options.length; k++) {
                    if (el.options[k].value === v) { hit = true; break; }
                  }
                  if (!hit) el.add(new Option(v, v));
                  el.value = v;
                } else {
                  el.value = v;
                }
                set.push(n + '=' + v);
              }
              for (var j = 0; j < list.length; j++) put(list[j].n, list[j].v);
              var names = [];
              for (var m = 0; m < f.elements.length; m++) {
                if (f.elements[m].name) names.push(f.elements[m].name);
              }
              $BRIDGE.onInfo(
                'action=' + (f.getAttribute('action') || '') +
                ' created=' + created +
                ' fields=[' + names.join(',') + ']' +
                ' set=[' + set.join(',') + ']'
              );
              f.submit();
              return 'SUBMITTED';
            })()
        """.trimIndent()
    }

    private class Bridge {
        @Volatile var html: String? = null
        @Volatile var info: String? = null
        @Volatile var probe: String? = null

        @JavascriptInterface
        fun onDone(value: String?) {
            html = value
        }

        @JavascriptInterface
        fun onInfo(value: String?) {
            info = value
        }

        @JavascriptInterface
        fun onProbe(value: String?) {
            probe = value
        }
    }

    companion object {
        private const val BRIDGE = "HpuRoomBridge"
        private const val TAG = "freeroom"
        private const val URL_CAS_RELOGIN = "https://webvpn.hpu.edu.cn/huauauth/caslogin"

        /** 主文档 + 同源 frame 的 outerHTML：结果表有可能在 iframe 里 */
        private val JS_COLLECT = """
            (function(){
              var out = (document.documentElement && document.documentElement.outerHTML) || '';
              try {
                var fs = window.frames;
                for (var i = 0; i < fs.length; i++) {
                  try {
                    var d = fs[i].document;
                    if (d && d.documentElement) out += '\n' + d.documentElement.outerHTML;
                  } catch (e) {}
                }
              } catch (e) {}
              return out;
            })()
        """.trimIndent()

        /**
         * 从首页扒「空闲教室」的真实链接。教务的导航 href 里带着它自己认的 project/学期参数，
         * 比我们猜的参数可靠得多。
         */
        private val JS_LINKS = """
            (function(){
              var out = [], as = document.querySelectorAll('a[href]');
              for (var i = 0; i < as.length && out.length < 12; i++) {
                var h = as[i].getAttribute('href') || '';
                if (h.indexOf('classroom') >= 0 || h.indexOf('free!') >= 0 || h.indexOf('project.id') >= 0) {
                  out.push((as[i].innerText || '').replace(/\s+/g, '') + '->' + h);
                }
              }
              return 'count=' + as.length + ' hits=[' + out.join(' ; ') + ']';
            })()
        """.trimIndent()

        /**
         * 页面结构快照。0.2.5 的日志里 `created=true` 说明 `document.forms` 是空的，
         * 但当时没有任何信息能判断"这个页面到底是菜单页、frameset，还是已经被改版"。
         * 这里把标题、表单数与 action、frame 里的表单、正文开头一次性报出来。
         */
        private val JS_PROBE = """
            (function(){
              function acts(d){
                var out = [], fs = d.forms || [];
                for (var i = 0; i < fs.length && i < 6; i++) out.push(fs[i].getAttribute('action') || '(none)');
                return out;
              }
              var top = acts(document);
              var frames = [];
              try {
                for (var i = 0; i < window.frames.length; i++) {
                  try {
                    var d = window.frames[i].document;
                    frames.push((d.forms ? d.forms.length : 0) + ':[' + acts(d).join('|') + ']');
                  } catch (e) { frames.push('x'); }
                }
              } catch (e) {}
              var txt = '';
              try { txt = (document.body ? (document.body.innerText || '') : '').replace(/\s+/g, ' ').slice(0, 240); } catch (e) {}
              return 'title=' + document.title
                + ' forms=' + (document.forms ? document.forms.length : -1)
                + ' actions=[' + top.join('|') + ']'
                + ' frames=[' + frames.join(',') + ']'
                + ' text=' + txt;
            })()
        """.trimIndent()

        /**
         * 是否是"被弹去登录"的地址。
         *
         * ⚠️ 不能按主机名判断：校外经 WebVPN 访问时，正常内容页也会落在
         * `webvpn.hpu.edu.cn/https/<编码>/…` 的改写路径上。只有这几类特征才是真·登录拦截。
         */
        private fun isLoginUrl(url: String): Boolean =
            url.contains("login/index") ||
                url.contains("redirect_authurl") ||
                url.contains("uia.hpu.edu.cn") ||
                url.contains("/cas/login") ||
                url.contains("huauauth")
    }
}
