package com.zzupocket.app.data.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HPU 课表 HTML 解析回归。用真机抓包的 HTML 样本（test resources）验证：
 * 解析器能把内联 TaskActivity + index 定位还原成 weekday/节次/周次。
 */
class HpuScheduleParserTest {

    private fun readRes(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test
    fun `学期页_解析当前学期与任务id`() {
        val html = readRes("hpu_semester_page_sample.html")
        val page = HpuScheduleParser.parseSemesterPage(html)
        assertNotNull(page)
        assertEquals(244, page!!.semesterId)
        assertTrue("应解析出至少一个教学任务 id", page.taskIds.isNotEmpty())
        assertTrue("ids 应全为数字", page.taskIds.all { it.all(Char::isDigit) })
    }

    @Test
    fun `课表页_解析出全部课程`() {
        val html = readRes("hpu_course_table_sample.html")
        val sessions = HpuScheduleParser.parseCourseTable(html)
        assertEquals("应解析出 23 门课（与抓包一致）", 23, sessions.size)
    }

    @Test
    fun `课表页_首门课字段正确`() {
        val html = readRes("hpu_course_table_sample.html")
        val sessions = HpuScheduleParser.parseCourseTable(html)
        // 第一门：形势与政策-1，周六(day=5→weekday=6) 第1-3节
        val first = sessions.first()
        assertTrue(first.courseName.contains("形势与政策"))
        assertEquals(6, first.weekday)          // index=5*11+0..2 → 周六
        assertEquals(1, first.startUnit)
        assertEquals(3, first.endUnit)
        assertTrue("应有周次位串", first.weeks.isNotEmpty())
    }

    @Test
    fun `课表页_星期与节次在合法范围`() {
        val html = readRes("hpu_course_table_sample.html")
        HpuScheduleParser.parseCourseTable(html).forEach { s ->
            assertTrue("weekday 越界: ${s.weekday}", s.weekday in 1..7)
            assertTrue("startUnit 越界: ${s.startUnit}", s.startUnit in 1..11)
            assertTrue("endUnit 越界: ${s.endUnit}", s.endUnit in s.startUnit..11)
            assertFalse("周次不应为空", s.weeks.isEmpty())
        }
    }

    @Test
    fun `解析_课名与代码分离`() {
        val html = readRes("hpu_course_table_sample.html")
        val sessions = HpuScheduleParser.parseCourseTable(html)
        // "大学物理（一）(13200002.008)" → 课名不含尾部代码
        val physics = sessions.first { it.courseName.contains("大学物理") }
        assertFalse("课名不应含尾部(代码)", physics.courseName.contains("("))
        assertEquals("13200002.008", physics.courseCode)
    }

    @Test
    fun `解析_周次位串按位展开`() {
        val html = readRes("hpu_course_table_sample.html")
        val sessions = HpuScheduleParser.parseCourseTable(html)
        val first = sessions.first()
        // 首门 vaildWeeks="000000010000..." 置位在下标 7 → 第 7 周（下标即周次，0 位是占位）
        assertEquals(setOf(7), first.weeks)
    }

    @Test
    fun `周次_下标即周次不加一`() {
        val html = readRes("hpu_course_table_sample.html")
        val sessions = HpuScheduleParser.parseCourseTable(html)
        // 高等数学/大学外语/体育与健康 的首个置位都在下标 1 → 第 1 周开课
        val math = sessions.first { it.courseName.contains("高等数学") }
        assertEquals("高等数学应从第 1 周开始", 1, math.weeks.min())
        val pe = sessions.first { it.courseName.contains("体育与健康") }
        assertEquals("体育与健康应从第 1 周开始", 1, pe.weeks.min())
        assertEquals("体育与健康应到第 18 周", 18, pe.weeks.max())
    }

    @Test
    fun `周次_单双周不应整体后移`() {
        val html = readRes("hpu_course_table_sample.html")
        val sessions = HpuScheduleParser.parseCourseTable(html)
        // 物理实验（一）置位在下标 3,5,7..17 → 3-17 单周。
        // 真机上曾被解成 4-18 双周（整体 +1，奇偶翻转），用户 2026-11-19 反馈。
        val lab = sessions.first { it.courseName.contains("物理实验") }
        assertEquals(3, lab.weeks.min())
        assertEquals(17, lab.weeks.max())
        assertTrue("应为单周（全为奇数）", lab.weeks.all { it % 2 == 1 })
    }

    @Test
    fun `教师名_从teachers数组回退而非JS表达式`() {
        val html = readRes("hpu_course_table_sample.html")
        val sessions = HpuScheduleParser.parseCourseTable(html)
        val first = sessions.first()
        // 页面实参是 actTeacherName.join(',')，直接取会得到这串 JS 源码
        assertEquals(listOf("张红涛"), first.teachers)
        sessions.forEach { s ->
            assertFalse("教师名不应含 JS 表达式: ${s.teachers}", s.teachers.any { it.contains("join(") })
        }
    }
}
