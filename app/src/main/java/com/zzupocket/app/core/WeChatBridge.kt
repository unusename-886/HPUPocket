package com.zzupocket.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 微信跳转：宿舍用电的「更多功能」要落到公众号「河南理工大学微后勤」。
 *
 * Android 没有公开接口能直接打开某个公众号主页（`weixin://` 只有微信自己的参数才能定位），
 * 所以采用「复制公众号名 + 拉起微信」的组合：用户回到微信后在「搜一搜」粘贴即可。
 * 调用方负责复制剪贴板（Compose 侧用 LocalClipboardManager，避免这里持有 UI 引用）。
 */
object WeChatBridge {

    /** 微信包名，写入 AndroidManifest 的 `<queries>` 才能在 Android 11+ 查到 */
    private const val PACKAGE = "com.tencent.mm"

    const val OFFICIAL_ACCOUNT = "河南理工大学微后勤"

    /**
     * 尝试拉起微信。
     *
     * @return true 表示成功切到微信；false 表示未安装（或系统拦截），调用方应提示手动打开。
     */
    fun openWeChat(context: Context): Boolean {
        val app = context.applicationContext

        val launch = runCatching {
            app.packageManager.getLaunchIntentForPackage(PACKAGE)
        }.getOrNull()
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { app.startActivity(launch) }.isSuccess
            if (ok) return true
        }

        // 退路：走 scheme（不依赖包可见性声明），部分机型/平行空间只能这样唤起
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("weixin://"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(view) }.isSuccess
    }
}
