package com.zzupocket.app.data.electricity

import com.zzupocket.app.core.SchoolConfig
import com.zzupocket.app.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 电费查询站的原生客户端（北 `sdcx.hpu.edu.cn` / 南 `183.169.255.4:8080`，同款 ASP.NET WebForms）。
 *
 * 免登录（无需微信/统一认证）：楼栋→楼层→房间三级 WebForms 回发，靠 `__VIEWSTATE` 接力；
 * 查询结果页 `usedRecord.aspx` 用 `ASP.NET_SessionId` 维持会话，所以必须带独立 cookieJar。
 *
 * 与 `HttpClients.hpu()` 分开：电费站自成一套会话，绝不混进统一认证的 Cookie（工程约定
 * "每个上游系统一个独立 client"）。
 *
 * **编码**：同一站点不同页面编码不一致（表单页 GB2312、结果页 UTF-8），所以不能写死，
 * 逐响应嗅探：[decode]。
 */
class SdcxElectricityClient(
    private val baseUrl: String = SchoolConfig.URL_SDCX,
) {
    /** 当前校区（UI 展示用；client 行为只由 baseUrl 决定） */
    val campus: String = if (baseUrl == SchoolConfig.URL_SDCX_SOUTH) "南校区" else "北校区"

    /** 校区键：写进偏好存储，下次进页面按它恢复 */
    val campusKey: String = if (baseUrl == SchoolConfig.URL_SDCX_SOUTH) KEY_SOUTH else KEY_NORTH

    /** 独立的内存 Cookie 罐：只服务电费站，进程内有效，退出即失效（本就无需登录） */
    private val cookieJar = object : CookieJar {
        private val store = LinkedHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store[it.name] = it }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.values.toList()
    }

    private val client: OkHttpClient = HttpClients.builder(minIntervalMs = 250)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .build()

    private val gb2312: Charset = Charset.forName("GB2312")

    /** 首页：楼栋字典（已按「物理楼」合并照明/空调）+ 用于后续回发的 VIEWSTATE */
    data class Home(val viewState: String, val buildings: List<SdcxParser.BuildingGroup>)

    data class Page(val viewState: String, val options: List<SdcxParser.Option>)

    /** 单个表计的读数 */
    data class MeterReading(val meter: SdcxParser.Meter, val room: String, val kwh: Double)

    /**
     * 一次查询的结果：同一房间的照明/空调用一个入口一起查。
     *
     * [absent] 是「该表计下没有这个房间」——例如北校区淋浴间只有照明表计，
     * 空调表计里没有对应房间；这不是错误，UI 只提示该表计无数据。
     */
    data class QueryResult(
        val readings: List<MeterReading> = emptyList(),
        val absent: List<SdcxParser.Meter> = emptyList(),
    ) {
        val hasData: Boolean get() = readings.isNotEmpty()
    }

    suspend fun loadHome(): Home = withContext(Dispatchers.IO) {
        val html = get(baseUrl)
        Home(
            viewState = SdcxParser.parseViewState(html),
            buildings = SdcxParser.groupBuildings(SdcxParser.parseOptions(html, "drlouming")),
        )
    }

    /** 选楼栋 → 楼层联动（楼栋用主表计的编号，两个表计楼层号同构） */
    suspend fun loadFloors(group: SdcxParser.BuildingGroup, homeVs: String): Page =
        withContext(Dispatchers.IO) {
            val html = post(homeVs, "drlouming", listOf("drlouming" to group.primary.id))
            Page(SdcxParser.parseViewState(html), SdcxParser.parseOptions(html, "drceng"))
        }

    /** 选楼层 → 房间联动 */
    suspend fun loadRooms(
        group: SdcxParser.BuildingGroup,
        floorId: String,
        floorVs: String,
    ): Page = withContext(Dispatchers.IO) {
        val html = post(
            floorVs,
            "drceng",
            listOf("drlouming" to group.primary.id, "drceng" to floorId),
        )
        Page(SdcxParser.parseViewState(html), SdcxParser.parseOptions(html, "drfangjian"))
    }

    /**
     * 查同一房间的**全部表计**（照明 + 空调），按需逐表计走一次轻量回发。
     *
     * 每个表计是站上独立的楼栋编号，且 VIEWSTATE 与楼栋绑定（实测借用照明页的
     * VIEWSTATE 查空调会返回空），所以每个表计都必须自己走 楼栋→楼层 两次回发。
     * 楼层/房间号由 [SdcxParser.swapBuilding] 换前缀得到，编号不存在就记进 [QueryResult.absent]。
     */
    suspend fun queryRoom(
        group: SdcxParser.BuildingGroup,
        floorId: String,
        roomId: String,
        homeVs: String,
    ): QueryResult = withContext(Dispatchers.IO) {
        val readings = mutableListOf<MeterReading>()
        val absent = mutableListOf<SdcxParser.Meter>()

        group.meters().forEach { (meter, option) ->
            val reading = readMeter(group, meter, option, floorId, roomId, homeVs)
            if (reading != null) readings += reading else absent += meter
        }
        QueryResult(readings = readings, absent = absent)
    }

    /** 单个表计的一次完整回发链：换编号 → 楼层 → 房间 → 查询。编号不存在或页面无电量返回 null */
    private fun readMeter(
        group: SdcxParser.BuildingGroup,
        meter: SdcxParser.Meter,
        option: SdcxParser.Option,
        floorId: String,
        roomId: String,
        homeVs: String,
    ): MeterReading? {
        val meterFloorId = SdcxParser.swapBuilding(floorId, group.primary.id, option.id) ?: return null
        val meterRoomId = SdcxParser.swapBuilding(roomId, group.primary.id, option.id) ?: return null
        return try {
            val floorHtml = post(homeVs, "drlouming", listOf("drlouming" to option.id))
            if (meterFloorId !in SdcxParser.parseOptions(floorHtml, "drceng").map { it.id }) return null

            val floorVs = SdcxParser.parseViewState(floorHtml)
            val roomHtml = post(
                floorVs,
                "drceng",
                listOf("drlouming" to option.id, "drceng" to meterFloorId),
            )
            if (meterRoomId !in SdcxParser.parseOptions(roomHtml, "drfangjian").map { it.id }) return null

            val html = post(
                SdcxParser.parseViewState(roomHtml),
                "",
                listOf(
                    "drlouming" to option.id,
                    "drceng" to meterFloorId,
                    "drfangjian" to meterRoomId,
                    "radio" to "usedR",
                    "ImageButton1.x" to "63",
                    "ImageButton1.y" to "18",
                ),
            )
            SdcxParser.parseBalances(html)
                .firstOrNull { it.room != SdcxParser.BACKUP_ROOM }
                ?.let { MeterReading(meter, it.room, it.kwh) }
        } catch (e: Exception) {
            com.zzupocket.app.core.CrashLog.w("electricity", "meter ${meter.label} query failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun SdcxParser.BuildingGroup.meters(): List<Pair<SdcxParser.Meter, SdcxParser.Option>> =
        listOfNotNull(
            light?.let { SdcxParser.Meter.LIGHT to it },
            ac?.let { SdcxParser.Meter.AC to it },
        )

    /**
     * 逐响应解码：先按严格 UTF-8 试解，失败才回退 GB2312。
     *
     * GB2312 的中文字节序列几乎不可能同时是合法 UTF-8，反之亦然，所以这个嗅探在两套
     * 页面（`Default.aspx` GB2312 / `usedRecord.aspx` UTF-8）上都能判对。之前写死 GB2312
     * 会让结果页整页乱码，直接表现为"查不到电量"。
     */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, gb2312)
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return client.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: return ""
            decode(bytes)
        }
    }

    private fun post(
        viewState: String,
        eventTarget: String,
        extra: List<Pair<String, String>>,
    ): String {
        val body = FormBody.Builder(gb2312).apply {
            add("__VIEWSTATE", viewState)
            add("__VIEWSTATEGENERATOR", "CA0B0334")
            add("__EVENTTARGET", eventTarget)
            add("__EVENTARGUMENT", "")
            extra.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder()
            .url(baseUrl)
            .post(body)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return client.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: return ""
            decode(bytes)
        }
    }

    companion object {
        const val KEY_SOUTH = "south"
        const val KEY_NORTH = "north"
    }
}
