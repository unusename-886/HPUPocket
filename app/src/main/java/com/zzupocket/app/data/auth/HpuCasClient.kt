package com.zzupocket.app.data.auth

/**
 * 河南理工大学统一身份认证的**常量**（不发起任何请求）。
 *
 * 历史：这里曾经有一整套"账号密码 + 图形验证码"的 CAS 表单登录实现
 * （`fetchLoginPage` / `fetchCaptcha` / `login`）。实际方案是**用 WebView 打开学校自己的
 * 登录页**（用户直接在学校页面上输入，App 不接触密码），那套实现从未被调用过，
 * 已于 2026-11-19 删除 —— 留着只会让人误以为存在第二条登录通道。
 *
 * 协议与跳转链见 `docs/interfaces/hpu-login-protocol.md`。
 */
object HpuCasClient {

    /**
     * 教务入口（取自学校官网首页「教务管理系统」链接）。
     *
     * 关键：不能直接用 `uia/cas/login?service=webvpn回调` 起步 —— 那样登录成功后
     * CAS 带 ST 回到 WebVPN 回调页就停住了（用户看到的「只能登录到 VPN 界面」），
     * 因为该回调缺少 redirect_authurl，不会自动跳转教务。
     *
     * 正确做法：从官网这条入口起步，让它自然走
     *   zhjw/eams/login.action → 302 WebVPN login/index?redirect_authurl=zhjw/eams/login.action
     *   → 302 UIA CAS 登录页 → 用户登录 → 带 ST 回 WebVPN → 凭 redirect_authurl 跳回教务。
     * 这样登录后能落到 zhjw/eams/，App 才能捕获会话。
     */
    const val ENTRY_JW = "https://zhjw.hpu.edu.cn/eams/login.action"

    /** 会话票据 Cookie（TGT）名称，见 [HpuCookieJar] / [HpuSessionStore] */
    const val COOKIE_TGT = "CASTGC"
}
