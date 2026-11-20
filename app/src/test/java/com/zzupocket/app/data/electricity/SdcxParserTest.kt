package com.zzupocket.app.data.electricity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 北校区电费站 HTML 解析：固定结构，用真实页面片段的形状做样本。 */
class SdcxParserTest {

    @Test
    fun `解析楼栋下拉_跳过占位项`() {
        val html = """
            <select name="drlouming" id="drlouming">
              <option selected="selected" value="">请选择</option>
              <option value="01">1号公寓照明</option>
              <option value="07">7号公寓照明</option>
              <option value="21">1号楼空调</option>
              <option value="28">8号楼空调</option>
            </select>
            <select name="drceng" id="drceng">
              <option value="0701">1层</option>
            </select>
        """.trimIndent()
        val opts = SdcxParser.parseOptions(html, "drlouming")
        assertEquals(5, opts.size)
        assertEquals("01", opts[1].id)
        assertEquals("7号公寓照明", opts[2].label)
        assertEquals("28", opts[4].id)
        // 只取 drlouming，不混入 drceng 的选项
        assertEquals(1, SdcxParser.parseOptions(html, "drceng").size)
    }

    @Test
    fun `解析楼层与房间_按name隔离`() {
        val html = """
            <select name="drceng"><option value="0701">1层</option><option value="0702">2层</option></select>
            <select name="drfangjian"><option value="070201">7#201</option><option value="070210">7#210淋浴间</option></select>
        """.trimIndent()
        assertEquals(listOf("0701", "0702"), SdcxParser.parseOptions(html, "drceng").map { it.id })
        assertEquals("7#210淋浴间", SdcxParser.parseOptions(html, "drfangjian")[1].label)
    }

    @Test
    fun `视图状态提取`() {
        val html = """<input type="hidden" name="__VIEWSTATE" id="__VIEWSTATE" value="/wEPDwUKLabc==" />"""
        assertEquals("/wEPDwUKLabc==", SdcxParser.parseViewState(html))
        assertEquals("", SdcxParser.parseViewState("<html></html>"))
    }

    @Test
    fun `解析剩余电量_span包裹形态`() {
        val html = """<h6>7#201剩余电量：<span class="number orange">246.20</span> 度&nbsp;</h6>"""
        val b = SdcxParser.parseBalance(html)!!
        assertEquals("7#201", b.room)
        assertEquals(246.20, b.kwh, 0.0001)
    }

    @Test
    fun `解析剩余电量_纯文本形态`() {
        val b = SdcxParser.parseBalance("3#405剩余电量： 88 度")!!
        assertEquals("3#405", b.room)
        assertEquals(88.0, b.kwh, 0.0001)
    }

    @Test
    fun `无电量文本返回null`() {
        assertNull(SdcxParser.parseBalance("<html><body>请选择查询类型</body></html>"))
    }

    @Test
    fun `南校区中文房间名带照明后缀`() {
        val html = "备用剩余电量：<span class=\"number orange\">0.00</span> 度&nbsp; 竹园1#101照明剩余电量：<span class=\"number orange\">141.50</span> 度"
        val b = SdcxParser.parseBalance(html)!!
        assertEquals("竹园1#101照明", b.room)
        assertEquals(141.50, b.kwh, 0.0001)
    }

    @Test
    fun `南校区结果页列出备用与房间两条`() {
        val html = "备用剩余电量：<span class=\"number orange\">0.00</span> 度&nbsp; 竹园1#101照明剩余电量：<span class=\"number orange\">141.50</span> 度"
        val all = SdcxParser.parseBalances(html)
        assertEquals(2, all.size)
        assertEquals("竹园1#101照明", all[0].room)
        assertEquals(141.50, all[0].kwh, 0.0001)
        assertEquals("备用", all[1].room)
        assertEquals(0.0, all[1].kwh, 0.0001)
    }

    @Test
    fun `北校区纯数字房间仍可解析`() {
        val html = "<h6>7#201剩余电量：<span class=\"number orange\">246.20</span> 度</h6>"
        val b = SdcxParser.parseBalance(html)!!
        assertEquals("7#201", b.room)
        assertEquals(246.20, b.kwh, 0.0001)
    }

    /** 空调表计的房间名是 `竹园1#-101空调`——`#` 后带短横线，旧正则在这里漏解析 */
    @Test
    fun `空调房间名带短横线可解析`() {
        val html = "竹园1#-101空调剩余电量：<span class=\"number orange\">7.97</span> 度&nbsp; 空调：<span class=\"number orange\">0</span> 度"
        val b = SdcxParser.parseBalance(html)!!
        assertEquals("竹园1#-101空调", b.room)
        assertEquals(7.97, b.kwh, 0.0001)
    }

    /** 北校区两套命名不一致（1号公寓照明 / 1号楼空调），归一化后要并成同一栋 */
    @Test
    fun `北校区照明与空调并成同一栋`() {
        val opts = listOf(
            SdcxParser.Option("01", "1号公寓照明"),
            SdcxParser.Option("09", "商铺表"),
            SdcxParser.Option("21", "1号楼空调"),
            SdcxParser.Option("27", "7号楼空调"),
            SdcxParser.Option("07", "7号公寓照明"),
        )
        val groups = SdcxParser.groupBuildings(opts)
        val one = groups.first { it.base == "1号楼" }
        assertEquals("01", one.light?.id)
        assertEquals("21", one.ac?.id)
        assertEquals("照明 · 空调", one.subtitle)

        val shop = groups.first { it.base == "商铺表" }
        assertNull(shop.ac)
        assertEquals("09", shop.primary.id) // 单表计项走 primary
    }

    @Test
    fun `南校区楼栋按名称后缀并组`() {
        val opts = listOf(
            SdcxParser.Option("09", "竹园1号楼照明"),
            SdcxParser.Option("41", "竹园1号楼空调"),
            SdcxParser.Option("51", "松园1号楼空调"),
        )
        val groups = SdcxParser.groupBuildings(opts)
        assertEquals(2, groups.size)
        assertEquals("竹园1号楼", groups[0].base)
        assertEquals("41", groups[0].ac?.id)
        assertEquals("松园1号楼", groups[1].base)
        assertNull(groups[1].light)
        assertEquals("51", groups[1].primary.id)
    }

    @Test
    fun `换表计时只换楼栋前两位`() {
        // 南 竹园1：照明 09 → 空调 41
        assertEquals("4101", SdcxParser.swapBuilding("0901", "09", "41"))
        assertEquals("410101", SdcxParser.swapBuilding("090101", "09", "41"))
        // 北 7号公寓：照明 07 → 空调 27
        assertEquals("270201", SdcxParser.swapBuilding("070201", "07", "27"))
        // 前缀不匹配时不硬换
        assertNull(SdcxParser.swapBuilding("090101", "07", "27"))
        assertNull(SdcxParser.swapBuilding("09", "09", "41"))
    }

    @Test
    fun `楼层文案去掉楼栋前缀与表计后缀`() {
        assertEquals("1层", SdcxParser.floorLabel("竹园1#1层照明"))
        assertEquals("2层", SdcxParser.floorLabel("7#2层空调"))
        assertEquals("3层", SdcxParser.floorLabel("3层"))
    }

    /**
     * 欠费房间的结果页是 `剩余电量：-0.01 度`。旧正则的 `[\d.]+` 匹配不上负号，
     * 整条房间都被跳过，用户看到的是「这个房间查不到」——这是本条回归的意义。
     */
    @Test
    fun `欠费负数电量可解析_span形态`() {
        val html = "竹园1#101照明剩余电量：<span class=\"number orange\">-0.01</span> 度&nbsp;"
        val b = SdcxParser.parseBalance(html)!!
        assertEquals("竹园1#101照明", b.room)
        assertEquals(-0.01, b.kwh, 0.000001)
    }

    @Test
    fun `欠费负数电量可解析_纯文本形态`() {
        val b = SdcxParser.parseBalance("竹园1#-101空调剩余电量：-12.30 度")!!
        assertEquals("竹园1#-101空调", b.room)
        assertEquals(-12.30, b.kwh, 0.000001)
    }

    @Test
    fun `备用行的负数也照实解析`() {
        val all = SdcxParser.parseBalances("备用剩余电量：<span class=\"number orange\">-0.50</span> 度")
        assertEquals(-0.50, all.single { it.room == SdcxParser.BACKUP_ROOM }.kwh, 0.000001)
    }

    /** 房间格子里只放门牌号：`竹园1#101照明` → `101`，空调表计的负号也不展示 */
    @Test
    fun `房间文案只留门牌号`() {
        assertEquals("101", SdcxParser.roomLabel("竹园1#101照明"))
        assertEquals("101", SdcxParser.roomLabel("竹园1#-101空调"))
        assertEquals("201", SdcxParser.roomLabel("7#201"))
        assertEquals("210淋浴间", SdcxParser.roomLabel("7#210淋浴间"))
    }
}
