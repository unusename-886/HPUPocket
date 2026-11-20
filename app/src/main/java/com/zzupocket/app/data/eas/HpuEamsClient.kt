package com.zzupocket.app.data.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 河南理工教务 EAMS 客户端。
 *
 * HPU 的 EAMS 是**老式 HTML 表格**（不是 JSON API）：
 *  - 登录靠 UIA CAS 单点跳转（见 docs/interfaces/hpu-eams-protocol.md 第 1 节），
 *    本类只负责"已登录会话下"的页面抓取；
 *  - 课表是内联 JS（TaskActivity），由 [HpuScheduleParser] 解析。
 *
 * 抓取通道：优先用 [HpuEamsWebFetcher]（复用登录 WebView 的会话，绕开 WebVPN 网关
 * 对 OkHttp 直连的拦截）；注入不到 WebView（如纯 JVM 单测）时退回 OkHttp。
 */
class HpuEamsClient(
    private val client: OkHttpClient,
    private val parser: HpuScheduleParser = HpuScheduleParser,
    private val webFetcher: HpuEamsWebFetcher? = null,
) {


    /** 抓课表页，解析出当前学期 id 与教学任务 ids */
    suspend fun fetchSemesterPage(): HpuScheduleParser.SemesterPage? = withContext(Dispatchers.IO) {
        // 第 0 步：WebView 侧会话自检（homeExt 是登录落地页）。
        // 明确被弹回登录页时直接放弃，别把 3 次 × 9s 的重试烧在注定失败的通道上；
        // 超时/无法判定（null）不算失效，继续正常抓取。
        webFetcher?.let { f ->
            when (f.warmUp()) {
                false -> {
                    com.zzupocket.app.core.CrashLog.w(
                        "eas",
                        "fetchSemesterPage: webview session dead (homeExt bounced), abort",
                    )
                    return@withContext null
                }
                true -> com.zzupocket.app.core.CrashLog.i("eas", "fetchSemesterPage: webview session ok")
                null -> com.zzupocket.app.core.CrashLog.w(
                    "eas",
                    "fetchSemesterPage: webview session unknown (warmup timeout), continue",
                )
            }
        }
        val url = "$BASE/eams/courseTableForStd.action"
        // 学期页真实内容含 semesterCalendar(...)，用它判定 DOM 已就绪，避开空壳/登录页
        val html = fetchHtmlSuspend(url, expectMarker = "semesterCalendar")
        if (html == null) {
            com.zzupocket.app.core.CrashLog.w("eas", "fetchSemesterPage: no html from $url")
            return@withContext null
        }
        val bounced = isLoginBody(html)
        val page = parser.parseSemesterPage(html)
        com.zzupocket.app.core.CrashLog.i(
            "eas",
            "fetchSemesterPage: bodyLen=${html.length} bounced=$bounced " +
                "parsed=${page != null} taskIds=${page?.taskIds?.size ?: -1}",
        )
        if (page == null) {
            com.zzupocket.app.core.CrashLog.w(
                "eas",
                "fetchSemesterPage: parse fail html head=${html.take(800).replace(Regex("\\s+"), " ")}",
            )
        }
        page
    }

    /**
     * 抓整学期课表并解析。
     * @param semesterId 学期 id（来自 [fetchSemesterPage]）
     * @param ids 教学任务 ids（来自 [fetchSemesterPage]）
     */
    suspend fun fetchCourseTable(
        semesterId: Int,
        ids: List<String>,
    ): List<HpuScheduleParser.ParsedSession> = withContext(Dispatchers.IO) {
        val url = "$BASE/eams/courseTableForStd!courseTable.action"

        // ── 首选：在已登录 WebView 页面上下文里用原生 fetch() 同源 POST 抓课表。
        // 真机 17:59 日志证实：页面自带 searchTable() 依赖外链 jQuery，抓取 WebView 里未加载，
        // 报 "err:jQuery is not defined"。改为原生 fetch 复刻它的参数（零外链依赖，
        // HAUTPocket 同源请求同思路）；ids 按页面 if/else 二选一，不能两个都传（否则空表）。
        val fetcher = webFetcher
        var html: String? = null
        if (fetcher != null) {
            html = fetcher.fetchCourseTableViaPage(
                "$BASE/eams/courseTableForStd.action",
                semesterId,
                ids,
            )
            com.zzupocket.app.core.CrashLog.i(
                "eas",
                "fetchCourseTable(PageFetch) -> len=${html?.length ?: 0}",
            )
        }

        // ── 回退：手工 POST（页面内方案不可用时使用）。ids 保留同名多值。
        if (html == null) {
            com.zzupocket.app.core.CrashLog.w("eas", "fetchCourseTable: page-fetch failed, fallback POST")
            if (ids.isEmpty()) return@withContext emptyList()
            val fields = buildList {
                add("ignoreHead" to "1")
                add("setting.kind" to "std")
                add("startWeek" to "")
                add("project.id" to "1")
                add("semester.id" to semesterId.toString())
                ids.forEach { add("ids" to it) }
            }
            html = fetchHtmlSuspend(url, fields, expectMarker = "TaskActivity")
        }

        if (html == null) {
            com.zzupocket.app.core.CrashLog.w("eas", "fetchCourseTable: no html from $url")
            return@withContext emptyList()
        }
        val sessions = parser.parseCourseTable(html)
        com.zzupocket.app.core.CrashLog.i(
            "eas",
            "fetchCourseTable: bodyLen=${html.length} parsed=${sessions.size}",
        )
        // 周次/教师抽样：单双周曾整体后移一周（物理实验 3-17 单周被显示成 4-18 双周），
        // 这两个字段最容易悄悄错，每次同步打几条便于对照教务系统。
        if (sessions.isNotEmpty()) {
            com.zzupocket.app.core.CrashLog.i(
                "eas",
                "fetchCourseTable: sample " + sessions.take(6).joinToString("; ") { s ->
                    "${s.courseName}[${s.weeks.minOrNull()}-${s.weeks.maxOrNull()}共${s.weeks.size}周]"
                },
            )
        }
        if (sessions.isEmpty()) {
            // 解析 0 条时把 HTML 片段打出来：能直接看出是错误页/空表/iframe 壳，不用再反复抓包
            com.zzupocket.app.core.CrashLog.w(
                "eas",
                "fetchCourseTable: parsed=0 html head=${html.take(1200).replace(Regex("\\s+"), " ")}",
            )
        }
        sessions
    }

    /** 统一抓取：优先 WebView（会话在 WebView 里），否则 OkHttp */
    private suspend fun fetchHtmlSuspend(
        url: String,
        postFields: List<Pair<String, String>>? = null,
        expectMarker: String? = null,
    ): String? {
        val fetcher = webFetcher
        if (fetcher != null) {
            val html = fetcher.fetch(url, postFields, expectMarker)
            com.zzupocket.app.core.CrashLog.i(
                "eas",
                "GET(WebView) $url ${postFields?.let { "(POST ${it.size} fields)" } ?: ""} -> len=${html?.length ?: 0}",
            )
            return html
        }
        return withContext(Dispatchers.IO) { fetchHtmlOkHttp(url, postFields) }
    }

    private fun fetchHtmlOkHttp(
        url: String,
        postFields: List<Pair<String, String>>? = null,
    ): String? = runCatching {
        val requestBuilder = Request.Builder().url(url)
        if (postFields != null) {
            val form = FormBody.Builder().apply { postFields.forEach { add(it.first, it.second) } }.build()
            requestBuilder.post(form).header("Referer", "$BASE/eams/courseTableForStd.action")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            com.zzupocket.app.core.CrashLog.i(
                "eas",
                "GET(OkHttp) $url -> code=${response.code} finalUrl=${response.request.url} bodyLen=${body?.length ?: 0}",
            )
            if (!response.isSuccessful || body == null || isLoginBody(body)) return@use null
            body
        }
    }.onFailure {
        com.zzupocket.app.core.CrashLog.e("eas", "GET(OkHttp) $url exception: ${it.message}")
    }.getOrNull()

    private fun isLoginBody(html: String): Boolean =
        html.contains("loginExtForm") || html.contains("从平台登录") ||
            html.contains("统一身份认证") || html.contains("webvpn.hpu.edu.cn/login")

    companion object {
        const val BASE = "https://zhjw.hpu.edu.cn"
    }
}
