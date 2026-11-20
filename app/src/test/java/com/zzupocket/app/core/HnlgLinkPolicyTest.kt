package com.zzupocket.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 微后勤 WebView 的链接分流。
 *
 * 核心不变量：微信 OAuth 与本校内链必须留在 WebView（否则登录/支付链断），
 * 电话与外链必须交给系统（否则用户被困住）。
 */
class HnlgLinkPolicyTest {

    @Test
    fun `空链接被丢弃`() {
        assertEquals(HnlgLinkPolicy.Action.BLOCK, HnlgLinkPolicy.classify(null))
        assertEquals(HnlgLinkPolicy.Action.BLOCK, HnlgLinkPolicy.classify(""))
        assertEquals(HnlgLinkPolicy.Action.BLOCK, HnlgLinkPolicy.classify("   "))
    }

    @Test
    fun `微信OAuth授权链留在WebView内跟随`() {
        assertEquals(
            HnlgLinkPolicy.Action.IN_APP,
            HnlgLinkPolicy.classify(
                "https://open.weixin.qq.com/connect/oauth2/authorize?appid=wx3fcea4626809c787" +
                    "&redirect_uri=https%3A%2F%2Fwhgfwdt.hpu.edu.cn%2Ffapi%2FwxApi%2FoiGet" +
                    "&response_type=code&scope=snsapi_base#wechat_redirect",
            ),
        )
    }

    @Test
    fun `微后勤自身与一卡通购电页留在WebView`() {
        assertEquals(
            HnlgLinkPolicy.Action.IN_APP,
            HnlgLinkPolicy.classify("https://whgfwdt.hpu.edu.cn/hnlg/index.html#/home/index"),
        )
        assertEquals(
            HnlgLinkPolicy.Action.IN_APP,
            HnlgLinkPolicy.classify("http://ykt.hpu.edu.cn/server/auth/host/open?host=27&app=electric"),
        )
    }

    @Test
    fun `拨号短信邮件与微信唤起交给系统`() {
        assertEquals(HnlgLinkPolicy.Action.OPEN_EXTERNAL, HnlgLinkPolicy.classify("tel:3989854"))
        assertEquals(HnlgLinkPolicy.Action.OPEN_EXTERNAL, HnlgLinkPolicy.classify("sms:10086"))
        assertEquals(HnlgLinkPolicy.Action.OPEN_EXTERNAL, HnlgLinkPolicy.classify("mailto:a@hpu.edu.cn"))
        assertEquals(
            HnlgLinkPolicy.Action.OPEN_EXTERNAL,
            HnlgLinkPolicy.classify("weixin://dl/business/?t=abc"),
        )
    }

    @Test
    fun `外部网页交给系统浏览器`() {
        assertEquals(
            HnlgLinkPolicy.Action.OPEN_EXTERNAL,
            HnlgLinkPolicy.classify("https://www.example.com/some/page"),
        )
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(
            HnlgLinkPolicy.Action.OPEN_EXTERNAL,
            HnlgLinkPolicy.classify("TEL:3989854"),
        )
        assertEquals(
            HnlgLinkPolicy.Action.IN_APP,
            HnlgLinkPolicy.classify("HTTPS://OPEN.WEIXIN.QQ.COM/connect/oauth2/authorize"),
        )
    }
}
