package com.zzupocket.app.data.auth

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 河南理工各系统共享的会话 Cookie 罐。
 *
 * 为什么不能按 host 精确匹配：`uia.hpu.edu.cn` 登录落的 `CASTGC`，要在
 * `webvpn.hpu.edu.cn` 与 `zhjw.hpu.edu.cn` 上都能用（兑票链跨子域，实测见协议文档）。
 * 所以这里对 `*.hpu.edu.cn` 统一放行同域 Cookie。
 *
 * 与 [com.zzupocket.app.data.gms.GmsCookieJar] 的区别：那个按 host 精确隔离（研究生系统独占），
 * 这个按二级域共享（HPU 全系统单点登录）。
 */
class HpuCookieJar : CookieJar {

    private val store = LinkedHashMap<String, Cookie>()   // name -> cookie（同名覆盖）

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { store[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.values.filter { cookie -> belongs(cookie, url.host) }

    /** 把 WebView 登录后从 CookieManager 拿到的 `k=v; k2=v2` 注入（用户手输验证码的场景） */
    fun seed(host: String, cookieHeader: String?) {
        if (cookieHeader.isNullOrBlank()) return
        cookieHeader.split(';').forEach { part ->
            val t = part.trim()
            val eq = t.indexOf('=')
            if (eq <= 0) return@forEach
            val name = t.substring(0, eq).trim()
            val value = t.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@forEach
            store[name] = Cookie.Builder().domain(host).path("/").name(name).value(value).build()
        }
    }

    fun clear() = store.clear()

    private fun belongs(cookie: Cookie, host: String): Boolean {
        val domain = cookie.domain.trimStart('.')
        // 精确 host 或其子域（原有逻辑）
        if (host == domain || host.endsWith(".$domain")) return true
        // HPU 单点登录：seed 时 domain 是精确 host（如 uia.hpu.edu.cn），但 CASTGC 等
        // 要在 zhjw/webvpn 等兄弟子域上也能用。若两者同属 hpu.edu.cn 二级域则放行。
        // 注意：绝不按通用 eTLD+1 放宽到任意域，只认学校这一个固定二级域，守安全底线。
        val hpuDomain = "hpu.edu.cn"
        if (domain == hpuDomain || domain.endsWith(".$hpuDomain")) {
            if (host == hpuDomain || host.endsWith(".$hpuDomain")) return true
        }
        return false
    }
}
