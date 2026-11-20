package com.zzupocket.app.data.eas

/**
 * 教务「空闲教室」结果表解析。
 *
 * `free!search.action` 返回的**不是 JSON**，而是一张服务端渲染的 `<table class="gridtable">`
 * （见 `docs/interfaces/hpu-room-protocol.md` §5）。所以这里只能做 HTML 解析。
 *
 * 两条铁律：
 *  1. **按表头取列，不写死列序**。树维各校部署的列数不同（有的多一列"校区"、有的多"备注"），
 *     写死 `cells[2]` 会在换校区/换学期时静默错位 —— 把"教学楼"读成"容量"这种错最难发现。
 *  2. **拿不到表格 ≠ 没有空教室**。会话失效时返回的是登录页，必须能区分，
 *     否则用户会看到"该时段没有空闲教室"而白跑一趟。
 */
object FreeRoomParser {

    /** 一行 = 某教室在该时段可用 */
    data class Room(
        val name: String,
        val building: String,
        val campus: String,
        val seats: Int?,
        val type: String,
        val equipment: String,
    )

    /**
     * 解析结果。
     *
     * [headers] 原样保留表头文字：HPU 的实际列名尚未真机核对过，
     * 把它显示在空结果页上，用户截图一次就能定位是不是列名对不上。
     */
    data class Table(
        val headers: List<String> = emptyList(),
        val rooms: List<Room> = emptyList(),
    ) {
        val isEmpty: Boolean get() = rooms.isEmpty()
    }

    // ── 列名别名表（按"精确命中优先、包含次之"的顺序匹配）──
    private val CAMPUS_ALIASES = listOf("校区", "校区名称", "所属校区")
    private val BUILDING_ALIASES = listOf("教学楼", "所在教学楼", "教学楼名称", "楼栋", "楼宇", "建筑")
    private val SEATS_ALIASES = listOf("容量", "座位数", "座位", "可容纳人数", "可容纳", "人数")
    private val EQUIP_ALIASES = listOf("教室设备配置", "设备配置", "设备", "多媒体")
    private val TYPE_ALIASES = listOf("教室类型", "教室类别", "类型")
    private val NAME_ALIASES = listOf("教室名称", "房间名称", "名称", "教室", "房间", "门牌")

    /** 这些关键字出现在表头里时，说明不是"教室名"列（`教室设备配置` / `教室类型` 极易被误吃） */
    private val NAME_DECOY = listOf("设备", "类型", "校区", "楼", "容量", "座位", "序号")

    private val TR_RE = Regex("""<tr[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
    private val CELL_RE = Regex("""<t([hd])([^>]*)>([\s\S]*?)</t\1>""", RegexOption.IGNORE_CASE)
    private val TABLE_RE = Regex("""<table[^>]*>([\s\S]*?)</table>""", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("""<[^>]+>""")
    private val WS_RE = Regex("""[\s\u00A0]+""")

    private data class Cell(val isHeader: Boolean, val text: String)

    fun parse(html: String): Table {
        val tableBody = TABLE_RE.find(html)?.groupValues?.get(1)
            ?: return Table()
        val rows = TR_RE.findAll(tableBody)
            .map { tr -> CELL_RE.findAll(tr.groupValues[1]).map { m ->
                Cell(
                    isHeader = m.groupValues[1].equals("h", ignoreCase = true),
                    text = clean(m.groupValues[3]),
                )
            }.toList() }
            .filter { it.isNotEmpty() }
            .toList()

        if (rows.isEmpty()) return Table()

        // 表头行：优先找 <th>；整表都没有 th（有的模板用 td 当表头）才退化成第一行
        val headerIndex = rows.indexOfFirst { row -> row.any { it.isHeader } }
        val headerRow = if (headerIndex >= 0) rows[headerIndex] else rows.first()
        val headers = headerRow.map { it.text }
        val dataRows = if (headerIndex >= 0) rows.drop(headerIndex + 1) else rows.drop(1)

        // 没有表头文字（比如整表都是空 td 的"无数据"占位）就别按名取列了，直接返回空
        if (headers.count { it.isNotBlank() } < 3) return Table(headers = headers)

        val roles = assignColumns(headers)

        val rooms = dataRows.mapNotNull { cells ->
            fun cellOf(role: String): String =
                roles[role]?.let { idx -> cells.getOrNull(idx)?.text.orEmpty() }.orEmpty()

            // 兜底：连"名称"列都认不出来时，取第一个含数字、且不像序号的单元格
            val name = cellOf("name").ifBlank {
                cells.map { it.text }.firstOrNull { it.any(Char::isDigit) && it.length in 2..16 }.orEmpty()
            }
            if (name.isBlank()) return@mapNotNull null

            Room(
                name = name,
                building = cellOf("building"),
                campus = cellOf("campus"),
                seats = cellOf("seats").filter(Char::isDigit).toIntOrNull(),
                type = cellOf("type"),
                equipment = cellOf("equipment"),
            )
        }
        return Table(headers = headers, rooms = rooms)
    }

    /** 返回 role → 列索引。两轮：先全表精确匹配，再按别名包含匹配。 */
    private fun assignColumns(headers: List<String>): Map<String, Int> {
        val normalized = headers.map { it.replace(" ", "").replace("\u00A0", "") }
        val taken = mutableSetOf<Int>()
        val result = mutableMapOf<String, Int>()

        fun take(role: String, aliases: List<String>, exact: Boolean) {
            if (result.containsKey(role)) return
            for (alias in aliases) {
                val idx = normalized.indices.firstOrNull { i ->
                    normalized[i].isNotBlank() && i !in taken &&
                        if (exact) normalized[i] == alias else normalized[i].contains(alias)
                } ?: continue
                // 名称列额外挡掉"教室设备配置/教室类型"这类诱饵
                if (role == "name" && normalized[idx] != "教室" && NAME_DECOY.any { normalized[idx].contains(it) }) continue
                result[role] = idx
                taken += idx
                return
            }
        }

        val specs = listOf(
            "campus" to CAMPUS_ALIASES,
            "building" to BUILDING_ALIASES,
            "seats" to SEATS_ALIASES,
            "equipment" to EQUIP_ALIASES,
            "type" to TYPE_ALIASES,
            "name" to NAME_ALIASES,
        )
        // 第一轮：精确
        specs.forEach { (role, aliases) -> take(role, aliases, exact = true) }
        // 第二轮：包含
        specs.forEach { (role, aliases) -> take(role, aliases, exact = false) }
        return result
    }

    /** 会话失效：返回的是统一认证登录页，而不是结果表 */
    fun isLoginPage(html: String): Boolean =
        html.contains("统一身份认证") ||
            html.contains("uia.hpu.edu.cn/cas/login") ||
            html.contains("loginExt.action") ||
            html.contains("name=\"execution\"") ||
            (html.contains("login.action") && html.contains("password"))

    private val EXCEPTION_RE = Regex(
        """<div[^>]*id\s*=\s*["']exceptionStack["'][^>]*>([\s\S]*?)</div>""",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_RE = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)

    /**
     * 教务的通用错误页（`出错了 / Error happened`）。
     *
     * 它把真正的 Java 异常栈塞在 `<div id="exceptionStack">` 里（默认 `display:none`），
     * 这是判断"参数哪里不对"的唯一线索 —— 日志里只打前 600 字 HTML 只能看到样式表，没用。
     */
    fun errorDetail(html: String): String? {
        if (!html.contains("出错了") && !html.contains("Error happened")) return null
        val stack = EXCEPTION_RE.find(html)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        val title = TITLE_RE.find(html)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        val text = listOf(title, stack).filter { it.isNotBlank() }.joinToString(" | ")
        return text.ifBlank { clean(html).take(300) }
    }

    /** 表格存在但明确说"没有符合条件的教室" */
    fun saysNoData(html: String): Boolean =
        html.contains("没有查询到") || html.contains("没有找到") ||
            html.contains("不存在符合条件的") || html.contains("无符合条件的") ||
            html.contains("没有符合条件的")

    private fun clean(raw: String): String =
        WS_RE.replace(
            TAG_RE.replace(raw, " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'"),
            " ",
        ).trim()
}
