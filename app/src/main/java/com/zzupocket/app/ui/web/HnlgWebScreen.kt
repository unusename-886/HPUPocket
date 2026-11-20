package com.zzupocket.app.ui.web

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.zzupocket.app.core.CrashLog
import com.zzupocket.app.core.HnlgLinkPolicy
import com.zzupocket.app.core.SchoolConfig

/**
 * 「更多 → 校园服务 → 宿舍用电」：应用内 WebView 承载微后勤 H5。
 *
 * 为什么是 WebView 而不是原生 HTTP：微后勤（whgfwdt.hpu.edu.cn/hnlg）的全部用电接口
 * 都在 `/fapi` 下且需要微信 OAuth 令牌（无令牌实测 401 no_session），令牌只有微信内
 * OAuth 或本站扫码登录能拿到。让站点自己在 WebView 里完成登录、管令牌与接口变更，
 * 是维护成本最低、也不会失效的方案。协议细节见 docs/interfaces/hnlg-wechat-electricity.md。
 *
 * 与登录页 WebView 一致的两个关键设置：去 `;wv` UA（WebVPN/微信环境敏感）、
 * 允许第三方 Cookie（跨子域写会话）；再叠加本工程 WebView 的 vh 修复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HnlgWebScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    var title by remember { mutableStateOf("宿舍用电") }

    // 系统返回键：WebView 有历史就回退一页，退到头才离开本页
    BackHandler(enabled = true) {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (webView?.canGoBack() == true) webView?.goBack() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 微信里登录最稳：给一个用系统浏览器/微信打开的兜底入口
                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(SchoolConfig.URL_HNLG)),
                            )
                        }.onFailure { CrashLog.e("hnlg", "open external failed", it) }
                    }) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = "在浏览器中打开")
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.apply {
                            javaScriptEnabled = true          // SPA 全靠 JS
                            domStorageEnabled = true          // localStorage 存 OAuth token
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            // 与能登录的移动端环境一致（去掉 WebView 默认的 ;wv）
                            userAgentString = userAgentString.replace(";wv", "")
                        }
                        // 跨子域写会话 Cookie（微信 OAuth 回调、微后勤各子系统）
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, pageTitle: String?) {
                                if (!pageTitle.isNullOrBlank()) title = pageTitle
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url?.toString().orEmpty()
                                return when (HnlgLinkPolicy.classify(url)) {
                                    HnlgLinkPolicy.Action.IN_APP -> false // WebView 内跟随
                                    HnlgLinkPolicy.Action.OPEN_EXTERNAL -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                            )
                                        }.onFailure {
                                            CrashLog.e("hnlg", "override open failed: $url", it)
                                        }
                                        true // 已交给系统，本 WebView 不再加载
                                    }
                                    HnlgLinkPolicy.Action.BLOCK -> true
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                CrashLog.i("hnlg", "nav: ${url.orEmpty()}")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = 100
                                // 本工程 WebView 的 vh 单位恒为 0 的老问题，重复注入幂等
                                WebViewVhFix.install(view)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                error?.let { CrashLog.w("hnlg", "load error: ${it.description}") }
                            }
                        }

                        CrashLog.i("hnlg", "start ${SchoolConfig.URL_HNLG}")
                        loadUrl(SchoolConfig.URL_HNLG)
                    }
                },
            )
        }
    }
}
