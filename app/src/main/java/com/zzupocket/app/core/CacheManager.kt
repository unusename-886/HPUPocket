package com.zzupocket.app.core

import android.content.Context
import java.io.File

/**
 * 缓存统计与清理。
 *
 * 边界（重要）——本类只处理**可再生的临时文件**，绝不碰用户数据或凭据：
 *  · 清：[cacheDir]（系统临时 + 日志导出副本）、[filesDir]/crash（运行/崩溃日志）；
 *  · 不清：Room 数据库（课表/专注）、DataStore 偏好、TokenStore（登录 Cookie）、
 *          背景壁纸文件、EncryptedSharedPreferences。
 *
 * 所以清完缓存后：登录态还在、课表还在、壁纸还在，只是临时文件和日志没了。
 * 目录遍历/删除都是纯函数（吃 File），便于单测；Context 入口只负责给出真实目录。
 */
object CacheManager {

    /** 缓存条目：给用户看的分类占用 */
    data class CacheInfo(
        val tempBytes: Long,   // cacheDir（不含子目录里的导出日志另算也无妨，这里合并统计）
        val logBytes: Long,    // filesDir/crash
    ) {
        val totalBytes: Long get() = tempBytes + logBytes
    }

    /** 统计当前缓存占用 */
    fun cacheInfo(context: Context): CacheInfo = CacheInfo(
        tempBytes = dirSize(context.cacheDir),
        logBytes = dirSize(File(context.filesDir, "crash")),
    )

    /**
     * 清除全部安全缓存，返回实际释放的字节数。
     * 逐文件删并跳过删不掉的文件（不抛异常——清缓存失败不该让 App 崩）。
     */
    fun clearCache(context: Context): Long {
        val before = cacheInfo(context).totalBytes
        deleteContents(context.cacheDir)
        deleteContents(File(context.filesDir, "crash"))
        val after = cacheInfo(context).totalBytes
        return (before - after).coerceAtLeast(0L)
    }

    /** 递归统计目录字节数；不存在/不可读返回 0 */
    internal fun dirSize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().onFail { _, _ -> Unit }.sumOf { if (it.isFile) it.length() else 0L }
    }

    /** 删掉目录里的所有内容，但保留目录本身（App 还在用它） */
    internal fun deleteContents(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }
}
