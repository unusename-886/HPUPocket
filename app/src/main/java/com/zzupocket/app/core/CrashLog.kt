package com.zzupocket.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.zzupocket.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 试运行日志与崩溃收集。
 *
 * 目的：真机试运行失败时，用户不需要连电脑看 logcat —— 直接从「更多 → 诊断」导出一份
 * 文本文件发回来即可分析。这里同时抓 **未捕获崩溃** 和 **主动记录的关键事件**。
 *
 * 安全：只写我们自己的日志行；**不收集**令牌/Cookie/密码（见 [redact]）。
 * 文件写在 app 私有目录 `filesDir/crash/`，别人拿不到；只有用户主动点"导出"才分享出去。
 */
object CrashLog {

    private const val TAG = "HPUPocketLog"
    private const val DIR = "crash"
    private const val LOG_FILE = "runtime.log"
    private const val CRASH_FILE = "crash.txt"
    /** 日志上限，防止长期运行把文件撑爆（约 256KB） */
    private const val MAX_BYTES = 256 * 1024
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    private var appCtx: Context? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** 在 Application.onCreate 最早调用，越早越能抓到初始化阶段的崩溃 */
    fun install(context: Context) {
        appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val body = buildString {
                append("===== CRASH ${now()} =====\n")
                append("thread=").append(thread.name).append("\n")
                append("device=").append(Build.MANUFACTURER).append('/').append(Build.MODEL)
                    .append(" android=").append(Build.VERSION.SDK_INT).append("\n")
                append("app=").append(BuildConfig.VERSION_NAME)
                    .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                append(sw.toString())
            }
            appendRaw(CRASH_FILE, body)     // 崩溃单独存，覆盖写，永远保留最新一次
            appendRaw(LOG_FILE, "$body\n")  // 运行日志里也留一份上下文
            // 交给系统默认处理器：让它照常弹"应用已停止"、保存 ANR 等，不改变系统行为
            previous?.uncaughtException(thread, throwable)
        }
        i("app", "crash log installed")
    }

    /** 记录一条关键事件（只写级别+TAG+消息，消息内部请自行不要带敏感值） */
    fun i(tag: String, message: String) = write("I", tag, message)

    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val detail = if (throwable == null) message else {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "$message\n${sw.toString()}"
        }
        write("E", tag, detail)
    }

    private fun write(level: String, tag: String, raw: String) {
        val line = "${now()} $level/$TAG [$tag] ${redact(raw)}\n"
        appendRaw(LOG_FILE, line)
        // 同时写 logcat，开发时也能实时看
        when (level) {
            "E" -> Log.e(TAG, "[$tag] $raw")
            "W" -> Log.w(TAG, "[$tag] $raw")
            else -> Log.i(TAG, "[$tag] $raw")
        }
    }

    private fun appendRaw(name: String, text: String) {
        val ctx = appCtx ?: return
        runCatching {
            val dir = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }
            val file = File(dir, name)
            if (name == LOG_FILE && file.exists() && file.length() > MAX_BYTES) {
                // 截断：保留后半段（最近的日志更有价值）
                val keep = file.readText().takeLast(MAX_BYTES / 2)
                file.writeText(keep)
            }
            file.appendText(text)
        }
    }

    /** 脱敏：把常见的令牌/Cookie/手机号片段打码，避免日志外泄凭据 */
    private fun redact(s: String): String = s
        .replace(Regex("(JSESSIONID|CASTGC|TGT|ST-\\d+|token|password|pwd)=[^\\s&,;]+", RegexOption.IGNORE_CASE)) {
            "${it.groupValues[1]}=<脱敏>"
        }
        .replace(Regex("1[3-9]\\d{9}"), "<手机号>")

    private fun now(): String = fmt.format(Date())

    /** 崩溃摘要（诊断页展示；无崩溃返回 null） */
    fun crashSummary(): String? {
        val ctx = appCtx ?: return null
        val file = File(File(ctx.filesDir, DIR), CRASH_FILE)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { file.readText().lineSequence().take(12).joinToString("\n") }.getOrNull()
    }

    /**
     * 生成合并日志（崩溃 + 运行日志），写到 cacheDir 下供 FileProvider 分享。
     * 返回 Uri；失败返回 null。
     */
    fun exportUri(): Uri? {
        val ctx = appCtx ?: return null
        return runCatching {
            val dir = File(ctx.filesDir, DIR)
            val parts = listOf(CRASH_FILE, LOG_FILE).mapNotNull { name ->
                val f = File(dir, name)
                if (f.exists() && f.length() > 0) "----- $name -----\n${f.readText()}" else null
            }
            val body = if (parts.isEmpty()) {
                "（暂无日志。触发一次崩溃或操作后再导出）\n设备：${Build.MANUFACTURER}/${Build.MODEL} Android ${Build.VERSION.SDK_INT}\nApp：${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})\n"
            } else parts.joinToString("\n\n")

            val out = File(ctx.cacheDir, "hpu_pocket_log_${System.currentTimeMillis()}.txt")
            out.writeText(body)
            FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, out)
        }.getOrNull()
    }

    /** 返回合并日志全文（诊断页直接显示 + 用户长按复制，不依赖系统分享应用） */
    fun exportText(): String {
        val ctx = appCtx ?: return "（日志未初始化）"
        val dir = File(ctx.filesDir, DIR)
        val parts = listOf(CRASH_FILE, LOG_FILE).mapNotNull { name ->
            val f = File(dir, name)
            if (f.exists() && f.length() > 0) "----- $name -----\n${f.readText()}" else null
        }
        return if (parts.isEmpty()) {
            "（暂无日志。先操作一次登录/课表同步再来看）\n设备：${Build.MANUFACTURER}/${Build.MODEL} Android ${Build.VERSION.SDK_INT}\nApp：${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
        } else parts.joinToString("\n\n")
    }

    /** 组装一个"分享日志"的 Intent，调用方 startActivity 即可 */
    fun shareIntent(): Intent? {
        val ctx = appCtx ?: return null
        val uri = exportUri() ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "HPUPocket 试运行日志 ${now()}")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "导出日志") }
    }
}
