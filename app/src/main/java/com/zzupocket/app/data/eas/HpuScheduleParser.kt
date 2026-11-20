package com.zzupocket.app.data.eas

/**
 * 河南理工 EAMS 课表 HTML 解析器（纯 Kotlin，无 Android 依赖，便于单测）。
 *
 * 协议来源：2026-11-19 真机抓包，详见 docs/interfaces/hpu-eams-protocol.md。
 * 课表接口 `courseTableForStd!courseTable.action` 返回 HTML，课程数据以内联 JS 形式给出：
 *
 * ```
 * var unitCount = 11;
 * activity = new TaskActivity(teacherIds, teacherNames,
 *     "courseId(code)", "name(code)", "roomId", "校区  楼\t房号",
 *     "0101...", // 53 位周次位串
 *     taskId, remark, assistantName, experiItemName, schGroupNo);
 * index = day*unitCount + unitIndex;
 * table0.activities[index][...] = activity;
 * ```
 *
 * 星期/节次由紧随其后的 `index = D*unitCount + U` 决定：D=0 周一..6 周日，U=0 第 1 节..10 第 11 节。
 */
object HpuScheduleParser {

    /** 一门解析出来的课 */
    data class ParsedSession(
        val courseCode: String,
        val courseName: String,
        val teachers: List<String>,
        val roomText: String?,
        val campusText: String?,
        val weekday: Int,          // 1=周一..7=周日
        val startUnit: Int,        // 1-based
        val endUnit: Int,          // 1-based, inclusive
        val weeks: Set<Int>,       // 有课的教学周（1-based，从 vaildWeeks 位串解出）
    )

    /** 解析结果：学期页里的 ids、当前学期 id，以及 taskId->活动 的映射所需信息 */
    data class SemesterPage(
        val semesterId: Int,
        val taskIds: List<String>,
    )

    /**
     * 从 `courseTableForStd.action` 页面解析当前学期 id 与教学任务 ids。
     * - 学期：`semesterCalendar({... value:"244"})`
     * - 任务：`bg.form.addInput(form,"ids","134837")`
     */
    fun parseSemesterPage(html: String): SemesterPage? {
        // 用 [\x22] 表示引号，避免三引号字符串结尾写一堆引号的可读性问题
        val semesterId = Regex("""semesterCalendar\(\{[^}]*?value\s*:\s*\x22(\d+)\x22""")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val ids = Regex("""addInput\([^,]+,\s*\x22ids\x22\s*,\s*\x22(\d+)\x22""")
            .findAll(html).map { it.groupValues[1] }.distinct().toList()
        return SemesterPage(semesterId, ids)
    }

    /**
     * 从 `courseTableForStd!courseTable.action` 响应解析所有课程。
     *
     * 解析策略：按出现顺序扫描 `new TaskActivity(...)` 与其后紧邻的 `index = D*unitCount+U` 赋值，
     * 同一个 activity 会被写入多个单元格（一节课占多节），把它们合并成 [startUnit, endUnit]。
     */
    fun parseCourseTable(html: String): List<ParsedSession> {
        val unitCount = Regex("""var\s+unitCount\s*=\s*(\d+)""")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 11

        // 教师姓名的真实来源。页面里 TaskActivity 的第 2 个实参永远是
        // `actTeacherName.join(',')` 这种表达式（不是字面量），直接取值只会得到
        // 这串 JS 源码本身——详情页"教师"一栏曾原样显示 `actTeacherName.join(',')`。
        // 每个授课区块开头都有 `var teachers = [{id:1945,name:"张红涛",lab:false}]`，
        // assistants（助教）会在之后被剔除，所以拿 teachers 的名字与实际任课教师一致。
        val teacherBlocks = Regex("""var\s+teachers\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { m ->
                m.range.first to Regex("""name\s*:\s*\x22([^\x22]*)\x22""")
                    .findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            }
            .toList()

        // 找到每个 TaskActivity 调用的完整参数（括号内可能含逗号，需括号配平）
        data class Raw(
            val args: List<String>,
            val positions: MutableList<Pair<Int, Int>>,
            val fallbackTeachers: List<String>,
        )
        val raws = mutableListOf<Raw>()
        var pos = 0
        val activityRe = Regex("""new\s+TaskActivity\s*\(""")
        val boundaryRe = Regex("""var\s+teachers\s*=|new\s+TaskActivity\s*\(|fillTable\(""")
        while (true) {
            val m = activityRe.find(html, pos) ?: break
            val openPos = m.range.last           // '(' 的下标
            val splitArgsResult = splitArgs(html, openPos)
            if (splitArgsResult == null) { pos = openPos + 1; continue }
            val args = splitArgsResult
            val closeResult = findClosingParen(html, openPos)
            if (closeResult == null) { pos = openPos + 1; continue }
            val endArgs = closeResult
            // 下一个区块起点：var teachers / 下一个 TaskActivity / fillTable —— 本 activity 的 index 赋值只能在其之前
            val boundary = boundaryRe.find(html, endArgs + 1)?.range?.first ?: html.length
            val tail = html.substring(endArgs, boundary)
            val positions = tail.let {
                Regex("""index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)""")
                    .findAll(it)
                    .map { im -> im.groupValues[1].toInt() to im.groupValues[2].toInt() }
                    .toList()
            }
            val fallback = teacherBlocks.lastOrNull { it.first < m.range.first }?.second.orEmpty()
            raws.add(Raw(args, positions.toMutableList(), fallback))
            pos = if (boundary < html.length) boundary else endArgs + 1
        }

        return raws.mapNotNull { raw -> toSession(raw.args, raw.positions, raw.fallbackTeachers, unitCount) }
    }

    /** 括号配平地切出函数实参（处理引号内的逗号与括号）。[openPos] 为 '(' 的下标 */
    private fun splitArgs(html: String, openPos: Int): List<String>? {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var inQuote: Char? = null
        var i = openPos
        while (i < html.length) {
            val ch = html[i]
            if (inQuote != null) {
                if (ch == inQuote && (i == 0 || html[i - 1] != '\\')) inQuote = null
                else sb.append(ch)
                i++
                continue
            }
            when (ch) {
                '"', '\'' -> { inQuote = ch; sb.append(ch) }
                '(' -> { depth++; if (depth > 1) sb.append(ch) }
                ')' -> {
                    depth--
                    if (depth == 0) { out.add(sb.toString().trim()); return out }
                    sb.append(ch)
                }
                ',' -> if (depth == 1) { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        return null
    }

    /** [openPos] 为 '(' 的下标，返回配对的 ')' 下标 */
    private fun findClosingParen(html: String, openPos: Int): Int? {
        var depth = 0
        var inQuote: Char? = null
        var i = openPos
        while (i < html.length) {
            val ch = html[i]
            if (inQuote != null) {
                if (ch == inQuote && (i == 0 || html[i - 1] != '\\')) inQuote = null
            } else when (ch) {
                '"', '\'' -> inQuote = ch
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun unquote(s: String): String =
        s.trim().trim('"', '\'')

    private fun toSession(
        args: List<String>,
        positions: List<Pair<Int, Int>>,
        fallbackTeachers: List<String>,
        unitCount: Int,
    ): ParsedSession? {
        if (args.size < 7 || positions.isEmpty()) return null
        // 参数：0 teacherId, 1 teacherName, 2 courseId(code), 3 courseName, 4 roomId, 5 roomName, 6 vaildWeeks
        val teacherArg = args[1].trim()
        val teacherNames = if (teacherArg.startsWith("\"") || teacherArg.startsWith("'")) {
            unquote(teacherArg).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            // 页面传的是 `actTeacherName.join(',')` 表达式，真名在区块前的 `var teachers=[...]` 里
            fallbackTeachers
        }
        val courseRaw = unquote(args[3])               // "形势与政策-1(12100008.002)"
        val courseName = courseRaw.substringBeforeLast('(').trim().ifEmpty { courseRaw }
        val courseCode = Regex("""\(([^)]+)\)\s*$""").find(courseRaw)?.groupValues?.getOrNull(1)
            ?: unquote(args[2]).substringBefore('(').trim()

        val roomRaw = unquote(args[5])                  // "南校区  南校区1号教学楼\t1406"
        val roomText = roomRaw.split(Regex("[\t\u0009]"), limit = 2).lastOrNull()?.trim()
        val campusText = roomRaw.split(Regex("\\s+")).firstOrNull()?.trim()

        // vaildWeeks：53 位 0/1 串，**下标即教学周**（第 0 位是恒为 0 的占位）。
        // 曾误按"第 n 位对应第 n+1 周"解，导致每门课整体后移一周、单双周全部翻转：
        // 物理实验（一）真实 3-17 单周 → 显示成 4-18 双周（真机 2026-11-19 反馈）。
        // 反证：样本里 高等数学/大学外语/体育与健康/侦探之旅 的首个置位都在下标 1，
        // 对应"第 1 周开课"这一常识排课；若按 +1 解，全学期没有任何一门课在第 1 周开课。
        val weeksBits = unquote(args[6])
        val weeks = weeksBits.mapIndexedNotNull { idx, c ->
            if (c == '1' && idx >= 1) idx else null
        }.toSet()

        // positions: (day, unitIndex)，day 0=周一，unitIndex 0=第1节
        val days = positions.map { it.first }.distinct()
        val day0 = days.minOrNull() ?: return null
        val units = positions.filter { it.first == day0 }.map { it.second }
        val startUnit = units.minOrNull()?.plus(1) ?: return null
        val endUnit = units.maxOrNull()?.plus(1) ?: startUnit

        return ParsedSession(
            courseCode = courseCode,
            courseName = courseName,
            teachers = teacherNames,
            roomText = roomText,
            campusText = campusText,
            weekday = day0 + 1,
            startUnit = startUnit,
            endUnit = endUnit,
            weeks = weeks,
        )
    }
}
