package com.zzupocket.app.data.eas

import com.zzupocket.app.core.CrashLog
import com.zzupocket.app.core.SchoolConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 教务空闲教室查询：一次查询 = 表单页 + `free!search.action` 两步。
 *
 * 两条链路，自动择优：
 *  1. **OkHttp 快路**（校内网络或 Cookie 仍有效）：两次往返，秒级返回；
 *  2. **WebView 慢路**（校外 / 被 WebVPN 拦）：在已登录页面里 `form.submit()` 真导航到查询结果页，
 *     再把 DOM 推回原生。不用 `fetch` —— 真机实测校外必 `TypeError: Failed to fetch`
 *     （WebVPN 链路的 302 降级/跨源重定向被浏览器拦，拿不到状态码，见 [HpuRoomWebFetcher]）。
 *
 * 快路失败一次后本次进程内不再尝试，避免每次查教室都先撞一次登录页。
 */
class HpuFreeRoomClient(
    private val http: OkHttpClient,
    private val fetcher: HpuRoomWebFetcher,
    private val baseUrl: String = SchoolConfig.URL_JW_EAMS,
) {

    enum class Via { HTTP, WEBVIEW }

    /** 查询条件。节次按学校作息（第 1~11 节），`begin/end` 是闭区间。 */
    data class Query(
        val date: LocalDate,
        val beginSection: Int,
        val endSection: Int,
        val campusId: String,
        val buildingId: String = "",
        val typeId: String = "",
        val seats: String = "",
        val keyword: String = "",
    ) {
        fun toForm(): Map<String, String> {
            val day = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            return linkedMapOf(
                // ① 放 body 里只为兼容；真正生效的是 URL 上的 `?project.id=1`（见 withProject）
                "project.id" to PROJECT_ID,
                "classroom.type.id" to typeId,
                "classroom.campus.id" to campusId,
                "classroom.building.id" to buildingId,
                "seats" to seats,
                "classroom.name" to keyword,
                "cycleTime.cycleType" to "1",
                "cycleTime.cycleCount" to "1",
                "cycleTime.dateBegin" to day,
                "cycleTime.dateEnd" to day,
                // 0 = 按小节（timeBegin 填"第几节"）；1 = 按时间（HH:mm）
                "roomApplyTimeType" to "0",
                "timeBegin" to beginSection.toString(),
                "timeEnd" to endSection.toString(),
                "pageSize" to "1000",
            )
        }
    }

    sealed interface Result {
        data class Ok(val table: FreeRoomParser.Table, val via: Via) : Result

        /** 会话没了 —— UI 要引导去重新登录教务 */
        data object NeedLogin : Result

        data class Failed(val message: String) : Result
    }

    /** 快路是否还值得一试；被 WebVPN 拦过一次就永久走 WebView */
    @Volatile
    private var httpUsable = true

    suspend fun query(query: Query): Result = withContext(Dispatchers.IO) {
        val form = query.toForm()
        CrashLog.i(
            TAG,
            "query date=${query.date} section=${query.beginSection}-${query.endSection} " +
                "campus=${query.campusId} building='${query.buildingId}' httpUsable=$httpUsable",
        )

        if (httpUsable) {
            val fast = try {
                viaHttp(form)
            } catch (e: IOException) {
                CrashLog.w(TAG, "http exception: ${e.message}")
                return@withContext Result.Failed("网络不可用：${e.message ?: "连接失败"}")
            }
            when (fast) {
                is Result.Ok -> {
                    CrashLog.i(TAG, "http ok rooms=${fast.table.rooms.size} headers=${fast.table.headers}")
                    return@withContext fast
                }

                is Result.Failed -> CrashLog.w(TAG, "http failed: ${fast.message} -> webview fallback")
                Result.NeedLogin -> {
                    httpUsable = false
                    CrashLog.w(TAG, "http hit login -> disable fast path, webview fallback")
                }
            }
        }

        when (
            val outcome = fetcher.postInPage(
                // 入口页和查询页都要带 `?project.id=1`：教务是 beangle 框架，
                // project（学年学期）不在 URL 上时 `FreeAction` 第一步就抛 `project not selected`，
                // 连 `free!index` 这一页本身都会变成「出错了」（0.2.6 真机日志实证：
                // probe 打出 title= forms=0 text=出错了 Error happened，说明入口页就没开出来）。
                // 放 body 里不管用，必须是 query 参数。
                pageUrl = withProject(baseUrl + "classroom/apply/free!index.action.action"),
                postUrl = withProject("free!search.action"),
                form = form,
            )
        ) {
            is HpuRoomWebFetcher.Outcome.Ok -> {
                val table = FreeRoomParser.parse(outcome.html)
                CrashLog.i(TAG, "webview ok rooms=${table.rooms.size} headers=${table.headers}")
                when {
                    table.rooms.isNotEmpty() -> Result.Ok(table, Via.WEBVIEW)
                    table.headers.isNotEmpty() || FreeRoomParser.saysNoData(outcome.html) ->
                        Result.Ok(table, Via.WEBVIEW)
                    // 既没有表也没有"无数据"提示：多半被弹回了登录页/错误页，别谎报"没有空教室"
                    FreeRoomParser.isLoginPage(outcome.html) -> Result.NeedLogin
                    else -> {
                        // 教务的「出错了」页：把隐藏的异常栈抽出来，这是判断参数哪里不对的唯一线索
                        val detail = FreeRoomParser.errorDetail(outcome.html)
                        if (detail != null) {
                            CrashLog.w(TAG, "webview error page: $detail")
                            Result.Failed("教务报错：${detail.take(120)}")
                        } else {
                            CrashLog.w(TAG, "webview unparsed html: ${outcome.html.take(600).replace('\n', ' ')}")
                            Result.Failed("教务返回的内容无法解析（可能改版）")
                        }
                    }
                }
            }

            is HpuRoomWebFetcher.Outcome.Failed -> {
                CrashLog.w(TAG, "webview failed: ${outcome.message} needLogin=${outcome.needLogin}")
                if (outcome.needLogin) Result.NeedLogin else Result.Failed(outcome.message)
            }
        }
    }

    /** 登录页的三种落点：统一认证 / WebVPN 网关 / 教务自己的登录动作 */
    private fun isLoginHost(host: String): Boolean =
        host == SchoolConfig.HOST_UIA || host == SchoolConfig.HOST_WEBVPN

    /** 给 URL 加 `project.id`（已有就不动）。相对路径也能用，浏览器按当前文档解析 */
    private fun withProject(url: String): String {
        if (url.contains("project.id")) return url
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + "project.id=" + PROJECT_ID
    }

    /** 抛 [IOException] 表示网络层失败（不降级）；返回 [Result.Failed] 表示内容层失败（会降级重试） */
    private fun viaHttp(form: Map<String, String>): Result {
        val pageRequest = Request.Builder()
            .url(baseUrl + "classroom/apply/free!index.action.action?_=" + System.currentTimeMillis())
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        http.newCall(pageRequest).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            CrashLog.i(TAG, "http page: ${resp.code} ${resp.request.url} len=${body.length}")
            if (!resp.isSuccessful) return Result.Failed("HTTP ${resp.code}")
            // 被 WebVPN/UIA 拦走时 200 也能拿到登录页，看**最终落在哪个主机**最可靠
            if (isLoginHost(resp.request.url.host)) return Result.NeedLogin
            if (FreeRoomParser.isLoginPage(body)) return Result.NeedLogin
        }

        val formBody = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url(baseUrl + "classroom/apply/free!search.action")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(formBody)
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            CrashLog.i(TAG, "http search: ${resp.code} ${resp.request.url} len=${body.length}")
            if (!resp.isSuccessful) return Result.Failed("HTTP ${resp.code}")
            if (isLoginHost(resp.request.url.host)) return Result.NeedLogin
            if (FreeRoomParser.isLoginPage(body)) return Result.NeedLogin
            val table = FreeRoomParser.parse(body)
            if (table.headers.isEmpty() && !FreeRoomParser.saysNoData(body)) {
                // 没有表也没有"无数据"提示 —— 大概是拿了个错误页，交给 WebView 再试
                return Result.Failed("教务返回内容无法解析")
            }
            return Result.Ok(table, Via.HTTP)
        }
    }

    private companion object {
        const val TAG = "freeroom"

        /** beangle 的 project（学年学期项目），与课表链路同值 */
        const val PROJECT_ID = "1"
    }
}
