package com.zzupocket.app.core

/**
 * 学校身份配置的**唯一来源**。
 *
 * 本工程只服务河南理工大学，郑州大学版的登录链路、JSON 教务接口、校园卡、图书馆、
 * 研究生教务、空教室等模块已整体移除；如需再适配别的学校，从这里改身份、在
 * `data/` 下按该校协议实现即可。
 *
 * 命名说明：
 *  - Kotlin 包名 `com.zzupocket.app` 保持不动 —— 它是编译期标识符，改它要同时改
 *    所有 import / AndroidManifest / 测试引用，收益为零、风险极高；
 *  - `applicationId` 是 `com.hpupocket.app`（见 app/build.gradle.kts），
 *    所以与上游郑大版可以在同一台手机上共存、数据互不影响。
 */
object SchoolConfig {

    // ── 身份 ───────────────────────────────────────────────────────────
    const val SCHOOL_NAME_ZH = "河南理工大学"
    const val SCHOOL_NAME_EN = "Henan Polytechnic University"
    const val SCHOOL_SHORT = "河南理工"
    const val SCHOOL_ABBREV = "HPU"
    const val MOTTO = "明德任责"
    const val FOUNDED_YEAR = "1909"

    /** 应用显示名（与 strings.xml 的 app_name 保持一致） */
    const val APP_NAME = "HPUPocket"

    /**
     * HPU 蓝种子色（Material You 由它推导整套浅/深配色）。
     *
     * 取自官方校徽主蓝 #004894（校徽圆环/核心图案色）。换主题只需改这一处；
     * widget 兜底色在 colors.xml。
     */
    const val BRAND_SEED_ARGB = 0xFF004894.toInt()

    /** 深色模式小组件的强调色兜底（与 values-night/colors.xml 一致） */
    const val BRAND_SEED_DARK_ARGB = 0xFFAAC7FF.toInt()

    // ── 系统域名 ───────────────────────────────────────────────────────
    // ⚠️ 以下均为**公开页面可确认的入口**，不是接口协议。
    const val HOST_UIA = "uia.hpu.edu.cn"
    const val HOST_SSO = "sso.hpu.edu.cn"
    const val HOST_JW = "zhjw.hpu.edu.cn"
    // 旧移动端接口域名 lgjw.hpu.edu.cn（2024 年）现已整站跳 WebVPN，不再直连，代码里不留常量。
    const val HOST_WEBVPN = "webvpn.hpu.edu.cn"

    const val URL_UIA = "https://$HOST_UIA/"
    const val URL_SSO = "https://$HOST_SSO/sso"
    const val URL_JW = "https://$HOST_JW/"

    /**
     * 教务 EAMS 根路径（**结尾必须带 `/`**，所有相对路径都拼在它后面）。
     *
     * 课表、空闲教室都在这下面：`classroom/apply/free!index.action.action` 是查询表单页，
     * `classroom/apply/free!search.action` 是结果表。与课表同源同会话。
     */
    const val URL_JW_EAMS = "${URL_JW}eams/"

    /**
     * 微后勤「数字后勤服务大厅」（`whgfwdt.hpu.edu.cn`）的 H5 —— **本版不接入，代码里没有入口**。
     *
     * 它的 `/fapi` 全要微信 OAuth 令牌，原生 HTTP 直连一律 401，只能在内置 WebView 里打开；
     * 而它又依赖微信 UA 与授权回调，套壳 WebView 也拿不到登录态。曾经写过一版页面，
     * 因为永远打不开登录而从未接到任何入口，已整个删除。协议与取舍留在
     * `docs/interfaces/hnlg-wechat-electricity.md`，宿舍电费走下面那套免登录 WebForms。
     */

    /**
     * 宿舍电费**免登录**查询站（后勤官网列出的北校区电费查询）。
     *
     * ASP.NET WebForms：楼栋→楼层→房间三级回发靠 `__VIEWSTATE` 接力，查询结果
     * 在 `usedRecord.aspx` 的 HTML 文本里（`X#XXX剩余电量：XXX.XX 度`）。
     * 无需微信、无需统一认证 —— 是本 App 原生电量查询的数据源。
     * 协议见 `docs/interfaces/hpu-electricity-protocol.md`。
     */
    const val HOST_SDCX = "sdcx.hpu.edu.cn"
    const val URL_SDCX = "https://$HOST_SDCX/"
    /** 南校区电费查询站（同款 WebForms 系统，楼栋字典是松园/竹园/梅园/兰园） */
    const val URL_SDCX_SOUTH = "http://183.169.255.4:8080/"

    /** 统一认证的账号规则（官网原文）：学生=学号，教职工=工号 */
    const val ACCOUNT_HINT = "学号"
    const val PASSWORD_HINT =
        "初始密码为 HPU + 身份证号后六位（末位 X 大写）+ \$!，改过则以修改后的为准"

    // ── 校区（公开信息；教室/宿舍字段仍待学校数据补齐）─────────────────
    val CAMPUSES = listOf("南校区", "北校区", "郑州校区", "白沙校区")

    /** 技术标识前缀（deviceId / 日志 TAG 用），保留小写缩写 */
    const val LOG_TAG = "HPUPocket"
}
