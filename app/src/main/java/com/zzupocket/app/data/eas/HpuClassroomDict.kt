package com.zzupocket.app.data.eas

/**
 * 河南理工教务「空闲教室」的校区 / 教学楼字典。
 *
 * 数据来源：`free!getBuildingsByCampus.action` 的真实返回（见
 * `docs/interfaces/hpu-room-protocol.md` §3，从 HAR 提取），不是猜的。
 *
 * 为什么写死在本地而不是每次先请求一次楼栋列表：
 *  · 楼栋几乎不变，而多一次「POST 取 `<option>` 片段」意味着多一次 WebVPN 往返，
 *    冷启动时会明显拖慢首次查询；
 *  · 站点返回的是 HTML 片段而非 JSON，解析容错差；一旦改版整个页面直接不可用。
 * 所以把它当**静态字典**用，查询只发一次 `free!search.action`。
 * 若哪天学校新增楼栋，用户在站点上能看到、这里看不到 —— 这就是本文件的已知边界。
 */
object HpuClassroomDict {

    data class Campus(val id: String, val label: String) {
        /** 结果表里校区列可能写成「南校区」，也可能只写「南」，比对时都算命中 */
        val shortLabel: String get() = label.removeSuffix("校区")
    }

    data class Building(val id: String, val name: String) {
        /** 去掉校区前缀，选择器里只显示「1号教学楼」这种短名 */
        val shortName: String
            get() = name.removePrefix("南校区").removePrefix("北校区").removePrefix("郑州校区")
    }

    val campuses: List<Campus> = listOf(
        Campus("1", "南校区"),
        Campus("2", "北校区"),
        Campus("22", "郑州校区"),
    )

    /** 南校区 campusId=1（24 栋）：教学楼排在前面，体育场/语音室之类靠后 */
    val southBuildings: List<Building> = listOf(
        Building("1", "南校区1号教学楼"),
        Building("2", "南校区2号教学楼"),
        Building("3", "南校区3号教学楼"),
        Building("4", "电气综合楼"),
        Building("5", "机械综合楼"),
        Building("6", "能源综合楼"),
        Building("7", "资环综合楼"),
        Building("8", "测绘综合楼"),
        Building("9", "理化综合楼"),
        Building("12", "土木综合楼"),
        Building("13", "经管综合楼"),
        Building("15", "材料综合楼"),
        Building("17", "文科综合楼"),
        Building("18", "计算机综合楼"),
        Building("23", "综合实验大楼"),
        Building("28", "工程训练中心"),
        Building("61", "安全学院楼"),
        Building("10", "语音室"),
        Building("14", "音乐系"),
        Building("35", "体育馆"),
        Building("31", "东区体育场"),
        Building("20", "东区体育场地"),
        Building("32", "西区体育场"),
        Building("21", "西区体育场地"),
    )

    /** 北校区 campusId=2（3 栋） */
    val northBuildings: List<Building> = listOf(
        Building("36", "北校区1号教学楼"),
        Building("37", "北校区2号教学楼"),
        Building("24", "北区体育场"),
    )

    /** 郑州校区 campusId=22（4 栋） */
    val zhengzhouBuildings: List<Building> = listOf(
        Building("101", "郑州校区教学楼"),
        Building("102", "郑州校区实验楼"),
        Building("103", "郑州校区干训楼"),
        Building("104", "郑州校区体育场地"),
    )

    fun buildingsOf(campusId: String): List<Building> = when (campusId) {
        "2" -> northBuildings
        "22" -> zhengzhouBuildings
        else -> southBuildings
    }

    /**
     * 教室类型（`classroom.type.id`）。空串 = 全部，是默认值 ——
     * 学生找自习室不在乎类型，先要"有哪些"，再自己看容量。
     */
    val types: List<Pair<String, String>> = listOf(
        "" to "全部类型",
        "3" to "普通教室",
        "2" to "固定桌椅多媒体",
        "41" to "活动桌椅多媒体",
        "8" to "活动桌椅教室",
        "4" to "语音教室",
        "6" to "录播教室",
        "7" to "实验室",
    )
}
