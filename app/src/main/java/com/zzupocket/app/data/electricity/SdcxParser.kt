package com.zzupocket.app.data.electricity

/**
 * 电费站（sdcx.hpu.edu.cn / 南校区 183.169.255.4:8080，同为 ASP.NET WebForms）的 HTML 解析
 * —— 纯函数，可单测。字节→字符串由 client 负责；这里只处理已经是文本的 HTML。
 *
 * 两个容易踩的坑（都有实证）：
 *  · **同一站点不同页面编码不同**：`Default.aspx` 是 GB2312，`usedRecord.aspx` 是 UTF-8，
 *    解码交给 client 嗅探，解析层只认文本。
 *  · **空调表计的房间名带短横线**：照明 `竹园1#101`，空调 `竹园1#-101`，正则必须容忍 `-`。
 */
object SdcxParser {

    /** 下拉里的一项：id 是 POST 提交的 value，label 是给用户看的文字 */
    data class Option(val id: String, val label: String)

    /** 表计类型：同一栋楼在站上是两个独立楼栋编号（照明 / 空调） */
    enum class Meter(val label: String) {
        LIGHT("照明"),
        AC("空调"),
    }

    /**
     * 一栋「物理楼」= 一组表计。
     *
     * 站上的楼栋字典是按表计平铺的：`竹园1号楼照明`(09) 与 `竹园1号楼空调`(41) 是两条，
     * 但用户心里只有"竹园1号楼"。这里把它们并成一条，UI 与查询都按这个粒度走。
     */
    data class BuildingGroup(
        val base: String,
        val light: Option? = null,
        val ac: Option? = null,
    ) {
        /** 主表计：优先照明（房间号/楼层号以它为准），没有照明就用空调 */
        val primary: Option get() = light ?: ac!!
        val hasAc: Boolean get() = ac != null
        val subtitle: String
            get() = listOfNotNull(
                light?.let { Meter.LIGHT.label },
                ac?.let { Meter.AC.label },
            ).joinToString(" · ")
    }

    private val OPTION_RE = Regex("""<option\b[^>]*\bvalue="([^"]*)"[^>]*>([^<]*)</option>""", RegexOption.IGNORE_CASE)
    private val VIEWSTATE_RE = Regex("""name="__VIEWSTATE"[^>]*\bvalue="([^"]*)"""", RegexOption.IGNORE_CASE)

    // 房间名形态因校区/表计而异：
    //   北 照明 `X#XXX剩余电量：XXX.XX 度`
    //   南 照明 `竹园1#101照明剩余电量：141.50 度`
    //   南/北 空调 `竹园1#-101空调剩余电量：7.97 度`   ← `#` 后带短横线
    // 另有一页同时出现「备用剩余电量：0.00 度」的情况，那条没有 `#`，不能被当成房间。
    //
    // **电量可以是负数**：欠费房间实测返回 `剩余电量：-0.01 度`。早期的 `[\d.]+` 会整条
    // 匹配不上，表现成「这个房间查不到」；统一写成 `-?[\d.]+`（含备用行）。
    private const val KWH = """(-?[\d.]+)"""
    private val BALANCE_RE = Regex(
        """([^<>:：\s]*[0-9]+#-?[0-9]+[^<>:：]*?)剩余电量[：:]\s*<span[^>]*>\s*$KWH\s*</span>\s*度""",
    )
    private val BALANCE_TEXT_RE = Regex(
        """([^<>:：\s]*[0-9]+#-?[0-9]+[^<>:：]*?)剩余电量[：:]\s*$KWH\s*度""",
    )
    private val BACKUP_RE = Regex("""备用剩余电量[：:]\s*(?:<span[^>]*>)?\s*$KWH\s*(?:</span>)?\s*度""")

    /**
     * 解析某个 `<select name="...">` 的全部选项。
     *
     * 用 select 的 name 定位起始 `<select`，截到对应 `</select>`，避免把页面上其他
     * select（楼栋/楼层/房间同页出现）的选项混进来。
     */
    fun parseOptions(html: String, selectName: String): List<Option> {
        val open = Regex("""<select\b[^>]*\bname="$selectName"[^>]*>""", RegexOption.IGNORE_CASE)
            .find(html) ?: return emptyList()
        val rest = html.substring(open.range.last + 1)
        val close = rest.indexOf("</select>", ignoreCase = true)
        val block = if (close >= 0) rest.substring(0, close) else rest
        return OPTION_RE.findAll(block).map { m ->
            Option(id = m.groupValues[1].trim(), label = m.groupValues[2].trim())
        }.toList()
    }

    /** 取当前页的 `__VIEWSTATE`（每次回发都要带上一次响应里的值） */
    fun parseViewState(html: String): String =
        VIEWSTATE_RE.find(html)?.groupValues?.get(1).orEmpty()

    /**
     * 把「按表计平铺」的楼栋选项并成「物理楼」。
     *
     * 北校区两套命名不一致：照明叫 `1号公寓照明`、空调叫 `1号楼空调`，所以归一化时把
     * `公寓` 折成 `号楼`，否则同一栋楼会分成两组。没有表计后缀的项（`商铺表`、`利莫瑞克2号楼`）
     * 自成一组、只有单表计。
     */
    fun groupBuildings(options: List<Option>): List<BuildingGroup> {
        val groups = LinkedHashMap<String, BuildingGroup>()
        options.filter { it.id.isNotBlank() }.forEach { opt ->
            val meter = meterOf(opt.label)
            val base = normalizeBase(opt.label)
            if (base.isBlank()) return@forEach
            val cur = groups[base] ?: BuildingGroup(base)
            groups[base] = when (meter) {
                Meter.LIGHT -> cur.copy(light = opt)
                Meter.AC -> cur.copy(ac = opt)
                null -> cur.copy(light = cur.light ?: opt) // 无后缀项按单表计处理
            }
        }
        // 拆成只有「空调」的组时，primary 走 ac，不用特殊处理
        return groups.values.toList()
    }

    fun meterOf(label: String): Meter? = when {
        label.endsWith(Meter.LIGHT.label) -> Meter.LIGHT
        label.endsWith(Meter.AC.label) -> Meter.AC
        else -> null
    }

    /** `竹园1号楼照明` → `竹园1号楼`；`1号公寓照明` → `1号楼`（北校区两套命名不一致） */
    fun normalizeBase(label: String): String =
        label.removeSuffix(Meter.LIGHT.label)
            .removeSuffix(Meter.AC.label)
            .replace("号公寓", "号楼")
            .trim()

    /** 楼层选项文案：`竹园1#1层照明` → `1层` */
    fun floorLabel(label: String): String =
        label.substringAfterLast('#', label)
            .removeSuffix(Meter.LIGHT.label)
            .removeSuffix(Meter.AC.label)
            .trim()

    /**
     * 房间选项文案：只留门牌号，去掉楼栋前缀/表计后缀/空调表计的负号。
     *
     * `竹园1#101照明` → `101`、`竹园1#-101空调` → `101`、`7#201` → `201`。
     * 三级选择器里房间是排成网格的短标签，带前缀会撑成两三行且每格都重复楼栋名。
     * 提交用的 value 仍是原始编号，展示与提交分开。
     */
    fun roomLabel(label: String): String =
        label.substringAfterLast('#', label)
            .removeSuffix(Meter.LIGHT.label)
            .removeSuffix(Meter.AC.label)
            .trim()
            .removePrefix("-")
            .trim()

    /**
     * 把「主表计」的楼层/房间号换成同栋另一个表计的编号。
     *
     * 站点编号规则是 `楼栋(2位) + 楼层(2位) + 房间(2位)`，换表计只需换前两位；
     * 实测南 `09/0901/090101` ↔ `41/4101/410101`、北 `07/0702/070201` ↔ `27/2702/270201`
     * 都能直接查到电量，所以不必为每个表计再走一遍三级联动（省 2 次请求）。
     */
    fun swapBuilding(code: String, fromBuildingId: String, toBuildingId: String): String? {
        if (fromBuildingId.isBlank() || toBuildingId.isBlank()) return null
        if (!code.startsWith(fromBuildingId)) return null
        if (code.length <= fromBuildingId.length) return null
        return toBuildingId + code.removePrefix(fromBuildingId)
    }

    /** 从结果页解析**第一条**带 `#` 的房间电量（兼容旧调用点） */
    fun parseBalance(html: String): Balance? = parseBalances(html).firstOrNull { it.room != BACKUP_ROOM }

    /**
     * 从结果页解析**全部**电量条目。
     *
     * 结果页可能同时给出「备用剩余电量」与「房间电量」；全部列出，备用那条排在后面，
     * 由 UI 决定是否展示。
     */
    fun parseBalances(html: String): List<Balance> {
        val out = mutableListOf<Balance>()
        BALANCE_RE.findAll(html).forEach { m ->
            m.groupValues[2].toDoubleOrNull()?.let { out += Balance(m.groupValues[1].trim(), it) }
        }
        if (out.isEmpty()) {
            BALANCE_TEXT_RE.findAll(html).forEach { m ->
                m.groupValues[2].toDoubleOrNull()?.let { out += Balance(m.groupValues[1].trim(), it) }
            }
        }
        BACKUP_RE.find(html)?.groupValues?.get(1)?.toDoubleOrNull()?.let { out += Balance(BACKUP_ROOM, it) }
        return out.distinctBy { it.room }
    }

    const val BACKUP_ROOM = "备用"

    data class Balance(val room: String, val kwh: Double)
}
