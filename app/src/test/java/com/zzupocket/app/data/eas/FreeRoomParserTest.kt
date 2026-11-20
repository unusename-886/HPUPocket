package com.zzupocket.app.data.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FreeRoomParserTest {

    /** 与 `docs/interfaces/hpu-room-protocol.md` §5.2 记录的表结构一致 */
    private val standardTable = """
        <table class="gridtable" width="100%">
          <thead>
            <tr><th>校区</th><th>序号</th><th>名称</th><th>教学楼</th><th>容量</th><th>教室设备配置</th></tr>
          </thead>
          <tbody>
            <tr><td>南校区</td><td>1</td><td>E201</td><td>南校区1号教学楼</td><td>30</td><td>多媒体</td></tr>
            <tr><td>南校区</td><td>2</td><td>1406</td><td>南校区1号教学楼</td><td>120</td><td>多媒体、空调</td></tr>
          </tbody>
        </table>
    """.trimIndent()

    @Test
    fun `按表头解析出教室名 楼栋 容量`() {
        val table = FreeRoomParser.parse(standardTable)
        assertEquals(listOf("校区", "序号", "名称", "教学楼", "容量", "教室设备配置"), table.headers)
        assertEquals(2, table.rooms.size)

        val first = table.rooms[0]
        assertEquals("E201", first.name)
        assertEquals("南校区1号教学楼", first.building)
        assertEquals("南校区", first.campus)
        assertEquals(30, first.seats)
        assertEquals("多媒体", first.equipment)
        // 「教室设备配置」不能被当成「教室」列吃掉
        assertEquals("", first.type)
    }

    @Test
    fun `列序打乱也能正确取到各字段`() {
        val html = """
            <table class="gridtable">
              <tr><th>名称</th><th>教学楼</th><th>容量</th><th>校区</th><th>序号</th></tr>
              <tr><td>2101</td><td>南校区2号教学楼</td><td>60</td><td>南校区</td><td>1</td></tr>
            </table>
        """.trimIndent()
        val room = FreeRoomParser.parse(html).rooms.single()
        assertEquals("2101", room.name)
        assertEquals("南校区2号教学楼", room.building)
        assertEquals(60, room.seats)
        assertEquals("南校区", room.campus)
    }

    @Test
    fun `表头用 td 而没有 th 时把第一行当表头`() {
        val html = """
            <table class="gridtable">
              <tr><td>名称</td><td>教学楼</td><td>容量</td></tr>
              <tr><td>A305</td><td>电气综合楼</td><td>45</td></tr>
            </table>
        """.trimIndent()
        val table = FreeRoomParser.parse(html)
        assertEquals(listOf("名称", "教学楼", "容量"), table.headers)
        assertEquals("A305", table.rooms.single().name)
        assertEquals(45, table.rooms.single().seats)
    }

    @Test
    fun `表头带空格和 nbsp 也能匹配`() {
        val html = """
            <table class="gridtable">
              <tr><th>教 室 名 称</th><th>教&nbsp;学&nbsp;楼</th><th>座&nbsp;位&nbsp;数</th></tr>
              <tr><td>B101</td><td>理化综合楼</td><td>80</td></tr>
            </table>
        """.trimIndent()
        val room = FreeRoomParser.parse(html).rooms.single()
        assertEquals("B101", room.name)
        assertEquals("理化综合楼", room.building)
        assertEquals(80, room.seats)
    }

    @Test
    fun `没有表格时返回空表头而不是抛异常`() {
        val table = FreeRoomParser.parse("<div class=\"tips\">请选择查询条件</div>")
        assertTrue(table.headers.isEmpty())
        assertTrue(table.isEmpty)
    }

    @Test
    fun `表格只有表头没有数据行`() {
        val html = """
            <table class="gridtable">
              <thead><tr><th>校区</th><th>名称</th><th>教学楼</th><th>容量</th></tr></thead>
              <tbody></tbody>
            </table>
        """.trimIndent()
        val table = FreeRoomParser.parse(html)
        assertEquals(4, table.headers.size)
        assertTrue(table.isEmpty)
    }

    @Test
    fun `登录页能被识别出来`() {
        val login = """
            <html><head><title>统一身份认证平台</title></head>
            <body><form action="/cas/login" method="post">
            <input type="hidden" name="execution" value="e1s1">
            <input type="password" name="password"></form></body></html>
        """.trimIndent()
        assertTrue(FreeRoomParser.isLoginPage(login))
        assertFalse(FreeRoomParser.isLoginPage(standardTable))
    }

    @Test
    fun `无数据提示能被识别出来`() {
        assertTrue(FreeRoomParser.saysNoData("<div>没有查询到符合条件的教室</div>"))
        assertFalse(FreeRoomParser.saysNoData(standardTable))
    }

    @Test
    fun `表单参数符合协议`() {
        val form = HpuFreeRoomClient.Query(
            date = LocalDate.of(2026, 11, 20),
            beginSection = 3,
            endSection = 4,
            campusId = "1",
            buildingId = "4",
        ).toForm()

        assertEquals("2026-11-20", form["cycleTime.dateBegin"])
        assertEquals("2026-11-20", form["cycleTime.dateEnd"])
        assertEquals("1", form["cycleTime.cycleType"])
        assertEquals("0", form["roomApplyTimeType"])
        assertEquals("3", form["timeBegin"])
        assertEquals("4", form["timeEnd"])
        assertEquals("1", form["classroom.campus.id"])
        assertEquals("4", form["classroom.building.id"])
        assertEquals("1000", form["pageSize"])
        // 未指定类型/关键字时必须是空串（站点用空串表示"全部"）
        assertEquals("", form["classroom.type.id"])
        assertEquals("", form["classroom.name"])
    }

    @Test
    fun `楼栋字典按校区区分`() {
        assertEquals(24, HpuClassroomDict.buildingsOf("1").size)
        assertEquals(3, HpuClassroomDict.buildingsOf("2").size)
        assertEquals(4, HpuClassroomDict.buildingsOf("22").size)
        assertEquals("1号教学楼", HpuClassroomDict.buildingsOf("1").first().shortName)
        assertEquals("北校区1号教学楼", HpuClassroomDict.buildingsOf("2").first().name)
    }
}
