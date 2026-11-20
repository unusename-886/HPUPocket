package com.zzupocket.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 会话存储。
 *
 * 原则：**只存会话，不存密码**。河南理工版全程在 WebView 里让用户在学校自己的页面上
 * 输密码，App 只保存登录后落下的会话 Cookie（CASTGC/TGT + EAMS 的 JSESSIONID 等）。
 *
 * 文件名 `secure_prefs` 与 `res/xml/backup_rules.xml` / `data_extraction_rules.xml`
 * 里的排除项必须一致 —— 会话不进任何备份。
 *
 * 注：郑州大学版曾在这里存过 CAS 令牌、一卡通令牌、研究生/图书馆会话，甚至
 * 网关自动认证用的账号密码。那些链路已整体移除，相关字段一并删除，避免残留凭据。
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 河南理工会话 Cookie（`k=v; k2=v2`，WebView 登录后从 CookieManager 取回）。
     * 包含 CASTGC(TGT) 与 EAMS 需要的 Cookie。
     */
    var hpuCookies: String?
        get() = prefs.getString(KEY_HPU_COOKIES, null)
        set(value) = prefs.edit().putString(KEY_HPU_COOKIES, value).apply()

    /** HPU 上次登录成功时间（毫秒） */
    var hpuLoginAt: Long
        get() = prefs.getLong(KEY_HPU_LOGIN_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_HPU_LOGIN_AT, value).apply()

    companion object {
        private const val FILE_NAME = "secure_prefs"
        private const val KEY_HPU_COOKIES = "hpu_cookies"
        private const val KEY_HPU_LOGIN_AT = "hpu_login_at"
    }
}
