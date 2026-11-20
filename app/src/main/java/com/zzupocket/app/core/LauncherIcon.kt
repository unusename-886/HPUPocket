package com.zzupocket.app.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.first

/**
 * 启动器图标调色盘。
 *
 * Android 的桌面图标是静态资源，运行时无法直接改色；这里用「一个 MainActivity +
 * 6 个 activity-alias（各挂一套图标）」实现换色：切换 = 启用目标 alias、禁用其余。
 *
 * ⚠️ 固有行为（不是 bug）：切换瞬间桌面图标会消失再出现，部分启动器要回到桌面
 * 或重启桌面才会刷新；期间已打开的 App 不受影响。切换后不要立刻重复点，等图标稳定。
 */
object LauncherIcon {

    enum class IconColor(val aliasSuffix: String, val label: String) {
        BLUE("Blue", "理工蓝"),
        PURPLE("Purple", "紫"),
        TEAL("Teal", "青"),
        GREEN("Green", "绿"),
        AMBER("Amber", "琥珀"),
        PINK("Pink", "玫红"),
        ;

        companion object {
            val DEFAULT = BLUE
        }
    }

    /** 当前生效的配色（读偏好；老用户没有该键 → 默认蓝） */
    suspend fun current(): IconColor = runCatching {
        IconColor.valueOf(AppGraph.uiPrefsStore.flow.first().iconColor)
    }.getOrDefault(IconColor.DEFAULT)

    /** 持久化用户选择（真正切换 alias 由设置页在写入后调 [apply]） */
    suspend fun save(color: IconColor) = AppGraph.uiPrefsStore.setIconColor(color.name)

    /**
     * 设备上的实际启用状态是否恰好只剩 [color] 这一个 alias。
     *
     * 用来做启动校准：老用户升级、或用户在系统里手动改过组件状态时，manifest 的
     * 默认 enabled 可能与偏好不一致（典型症状 = 桌面出现两个图标）。恰好一个且匹配
     * 才算干净，避免每次启动都重设组件状态而让图标闪一下。
     */
    fun isApplied(context: Context, color: IconColor): Boolean {
        val pm = context.packageManager
        val enabled = IconColor.entries.filter { c ->
            runCatching {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(component(context, c), PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            }.getOrDefault(false)
        }
        return enabled.size == 1 && enabled.single() == color
    }

    /**
     * 把桌面图标切到 [color]：先启用目标、再禁用其余。
     * 返回是否全部设置成功。需要调用方在 IO 线程调用。
     */
    fun apply(context: Context, color: IconColor): Boolean {
        val pm = context.packageManager
        val target = component(context, color)
        return runCatching {
            pm.setComponentEnabledSetting(
                target,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            IconColor.entries.filter { it != color }.forEach { other ->
                pm.setComponentEnabledSetting(
                    component(context, other),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            true
        }.getOrDefault(false)
    }

    private fun component(context: Context, color: IconColor) =
        ComponentName(context.packageName, "${context.packageName}.alias.MainActivity${color.aliasSuffix}")
}
