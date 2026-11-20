package com.zzupocket.app.core

/**
 * 微后勤 H5 里链接的处置策略（纯逻辑，可单测；不依赖 Android）。
 *
 * 背景：宿舍用电页在 WebView 里跑，站点会往外跳几类链接：
 *  - 微信 OAuth（`open.weixin.qq.com`）：**必须留在 WebView 内跟随**，否则登录链断掉；
 *  - 一卡通购电（`ykt.hpu.edu.cn`）：支付页，留在 WebView；
 *  - `tel:` / `weixin://` / 邮件等：交给系统应用（拨号、微信、邮件）；
 *  - 其他外部网页：交给系统浏览器，别把用户困在 App 的 WebView 里。
 *
 * 判断只看 URL 字符串本身，不做网络请求。
 */
object HnlgLinkPolicy {

    enum class Action {
        /** 在 WebView 内继续加载 */
        IN_APP,

        /** 交给系统（Intent ACTION_VIEW），由拨号/微信/浏览器接管 */
        OPEN_EXTERNAL,

        /** 空链接 / 无意义 scheme，直接丢弃 */
        BLOCK,
    }

    fun classify(raw: String?): Action {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return Action.BLOCK

        val lower = url.lowercase()

        // 拨号、微信 deep link、短信、邮件 —— 系统应用的事
        if (lower.startsWith("tel:")) return Action.OPEN_EXTERNAL
        if (lower.startsWith("sms:")) return Action.OPEN_EXTERNAL
        if (lower.startsWith("mailto:")) return Action.OPEN_EXTERNAL
        if (lower.startsWith("weixin://") || lower.startsWith("wechat://")) {
            return Action.OPEN_EXTERNAL
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            // 微信 OAuth 授权链：留在 WebView 内跟随 302，登录靠它完成
            if (lower.contains("open.weixin.qq.com")) return Action.IN_APP
            // 本校域（微后勤自身 + 购电跳的一卡通）：留在 WebView
            if (lower.contains(SchoolConfig.HOST_HNLG)) return Action.IN_APP
            if (lower.contains("hpu.edu.cn")) return Action.IN_APP
            // 其他外部网页交给系统浏览器
            return Action.OPEN_EXTERNAL
        }

        // about:blank / javascript: / data: 等 WebView 内部导航，允许其自然处理
        if (lower.startsWith("about:")) return Action.BLOCK

        return Action.BLOCK
    }
}
