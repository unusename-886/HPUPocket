package com.zzupocket.app.ui.nav

/**
 * 导航路由的**唯一来源**。
 *
 * 桌面小组件点进来时要指定"落到哪一页"，如果组件那边自己写一遍字面量，
 * 两边迟早会写歪（改名时只改一处、另一处静默失效）。所以放在这里共用。
 */
object AppRoutes {

    /** 底部导航的 tab */
    const val TODAY = "today"
    const val SCHEDULE = "schedule"
    const val FOCUS = "focus"
    const val CAMPUS = "campus"

    /**
     * 小组件带过来的路由 extra。
     *
     * MainActivity 读到它之后会**把它从 Intent 里删掉**：不删的话，转屏、切深色模式
     * 这类 Activity 重建会再次执行跳转，用户正看着明细时会被莫名弹回去。
     */
    const val EXTRA_ROUTE = "com.zzupocket.app.extra.ROUTE"
}
