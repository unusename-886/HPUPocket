# 河南理工 空闲教室 / 教室占用 接口协议（进展中）

> 状态：**已实现**（2026-11-20，0.2.2-hpu）。入口链路、表单字段、楼栋字典已确认；
> `free!search.action` 的请求参数与返回形态已用同构系统补齐（§5），App 端已接（§6）；
> 只剩"HPU 返回表头的实际文字"待真机截图核对（解析器已把表头原样显示在 0 结果页）。
> 域名：`https://zhjw.hpu.edu.cn/eams/`，与课表同源，可复用 `HpuSessionStore` 会话。
> 校外实测 302 → `webvpn.hpu.edu.cn/login/index?redirect_authurl=…`，与课表同一条 WebVPN 通道。
> 数据来源：`docs/interfaces/captures/zhjw.hpu.edu.cn(1).har`（76 条，含会话令牌，试运行前删除）。

---

## 1. 入口链路（已确认）

从教务首页到空闲教室页的跳转链：

```
GET /eams/homeExt!main.action          → 教务首页（菜单入口）
GET /eams/classroom/apply/std-activity.action  → 教室申请中转页
     页面内 JS: bg.Go('/eams/classroom/apply/free!index.action.action','contentDiv')
GET /eams/classroom/apply/free!index.action.action?_=<时间戳>   → 查询表单页（XHR, text/html）
     Header: X-Requested-With: XMLHttpRequest
```

`free!index.action.action` 也可用无 `!index.action.action` 后缀的 `free.action` 进入（中转页两者都出现）。

---

## 2. 查询表单字段（已确认）

`free!index.action.action` 返回的 HTML 表单：
`action = /eams/classroom/apply/free!search.action`，`method = post`，`target = freeRoomList`（iframe）。

| 字段名 | 类型 | 选项 / 示例 | 含义 |
|---|---|---|---|
| `classroom.type.id` | select | `3`=普通教室, `2`=固定桌椅多媒体, `41`=活动桌椅多媒体, `8`=活动桌椅教室, `1`=同声传译, `4`=语音教室, `5`=艺术类专用, `6`=录播教室, `7`=实验室, `21`=体育类专用 | 教室类型 |
| `classroom.campus.id` | select | `1`=南校区, `2`=北校区, `22`=郑州校区 | 校区 |
| `classroom.building.id` | select | 见 §3，随校区联动 | 教学楼 |
| `seats` | input | 空 | 最少座位数 |
| `classroom.name` | input | 空 | 教室名关键字 |
| `cycleTime.cycleType` | select | `1`=天, `2`=周 | 查询周期 |
| `cycleTime.cycleCount` | input | `1` | 周期数 |
| `cycleTime.dateBegin` | input | 日期 | 起始日期 |
| `cycleTime.dateEnd` | input | 日期 | 结束日期 |
| `roomApplyTimeType` | select | `1`=按教室使用时间, `0`=按教室使用小节 | 时间模式 |
| `timeBegin` | input | `00:00` | 起始时间/节次 |
| `timeEnd` | input | `00:00` | 结束时间/节次 |

> 表单默认 `dateBegin/dateEnd` 为空、`timeBegin/timeEnd` 为 `00:00`——
> 抓 `free!search.action` 时需实际选日期/时间，才能看到参数格式。

---

## 3. 楼栋联动接口（已确认）

```
POST /eams/classroom/apply/free!getBuildingsByCampus.action
Header: X-Requested-With: XMLHttpRequest
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
Body: campusId=<校区id>
```

返回 `text/html`，是一段 `<option>` 列表（纯 HTML 片段，非 JSON）：

```html
    <option value="31">东区体育场</option>
    <option value="1">南校区1号教学楼</option>
    <option value="2">南校区2号教学楼</option>
    ...
```

实测完整楼栋字典（已从 HAR 提取，删包后仍保留于此）：

<details>
<summary>南校区 campusId=1（24 栋）</summary>

```
31=东区体育场, 20=东区体育场地, 35=体育馆, 1=南校区1号教学楼, 2=南校区2号教学楼,
3=南校区3号教学楼, 12=土木综合楼, 61=安全学院楼, 28=工程训练中心, 17=文科综合楼,
5=机械综合楼, 15=材料综合楼, 8=测绘综合楼, 9=理化综合楼, 4=电气综合楼, 13=经管综合楼,
23=综合实验大楼, 6=能源综合楼, 32=西区体育场, 21=西区体育场地, 18=计算机综合楼,
10=语音室, 7=资环综合楼, 14=音乐系
```
</details>

- 北校区 `campusId=2`（3 栋）：`24=北区体育场, 36=北校区1号教学楼, 37=北校区2号教学楼`
- 郑州校区 `campusId=22`（4 栋）：`104=郑州校区体育场地, 102=郑州校区实验楼, 103=郑州校区干训楼, 101=郑州校区教学楼`

> 解析方式：正则提 `<option value="(\d+)">([^<]+)</option>` 即得楼栋 id→名称字典。
> 以上字典可直接生成 `hpu_rooms.json` 的楼栋层（教室层仍需查询结果补抓后确认）。

---

## 4. 会话 / 鉴权（已确认）

- 全程走 `zhjw.hpu.edu.cn/eams/` 同一会话，抓包请求带教务 `JSESSIONID`。
- 登录入口仍是统一认证（UIA），与课表/电费同一套 → **复用 `HpuSessionStore`**，无需另行登录。
- 所有 XHR 带 `X-Requested-With: XMLHttpRequest`。

---

## 5. `free!search.action`：请求与返回契约（2026-11-20 补齐，待真机确认列名）

原 HAR 只到表单页、没点"查询"，所以这一段一直空着。本次用**同构系统**补齐：
东北大学秦皇岛分校的 `neuq-classroom-query`（同样是树维 EAMS + WebVPN，已稳定跑通）
对同一 endpoint 发的是下面这组参数，与 HPU 表单字段名**逐一对得上**（见 §2）。

### 5.1 请求

```
POST /eams/classroom/apply/free!search.action
Content-Type: application/x-www-form-urlencoded
X-Requested-With: XMLHttpRequest
```

| 参数 | 取值 | 说明 |
|---|---|---|
| `classroom.type.id` | `""` 或 §2 的 id | 空 = 全部类型 |
| `classroom.campus.id` | `""` / `1` / `2` / `22` | 空 = 全部校区 |
| `classroom.building.id` | `""` 或 §3 的 id | 空 = 全部楼栋 |
| `seats` | `""` | 最少座位数 |
| `classroom.name` | `""` | 教室名关键字（如 `E5`、`101`） |
| `cycleTime.cycleType` | `"1"` | 1=天、2=周（查单日固定填 1） |
| `cycleTime.cycleCount` | `"1"` | 周期数 |
| `cycleTime.dateBegin` | `2026-11-20` | `YYYY-MM-DD` |
| `cycleTime.dateEnd` | 与 dateBegin 相同 | 查单日时两边一致 |
| `roomApplyTimeType` | `"0"` | **0=按小节**（`timeBegin=1` 表示第 1 节）；`1`=按时间（`08:00`） |
| `timeBegin` / `timeEnd` | `"1"` / `"4"` | 按小节时是节次序号；按时间时是 `HH:mm` |
| `pageSize` | `"1000"` | 结果一次性拉全，避免翻页 |

> 与 §2 的表单字段完全同构 —— 这也反证了 §2 抓到的表单就是提交这套参数。

### 5.2 返回

`text/html`，一张 `table.gridtable`：

```html
<table class="gridtable">
  <thead><tr><th>校区</th><th>序号</th><th>名称</th><th>教学楼</th><th>容量</th><th>教室设备配置</th>…</tr></thead>
  <tbody>
    <tr><td>南校区</td><td>1</td><td>E201</td><td>逸夫楼</td><td>30</td><td>多媒体</td>…</tr>
  </tbody>
</table>
```

- **不是 JSON**，是服务端渲染的表格；没有这张表 = 会话失效/参数被拒（不是"没有空教室"）。
- 解析策略：**先读 `thead th` 建"列名→索引"，再按列名取 `td`**，不要写死列序
  （不同学校列数不同，HPU 的列名待真机确认，但"按表头取名"对列序变化免疫）。
- 逐行 = 一个"该时段可用"的教室（系统已做完"课表反算"，不需要我们再减已排课）。

### 5.3 仍需真机确认的两件事

1. HPU 返回表的**实际表头文字**（决定 `教室名/楼栋/容量` 取哪个 key）。
2. `timeBegin/timeEnd` 在 HPU 是"第 N 节"还是"第 N 小节编号"——按 §2 表单的
   `roomApplyTimeType` 默认值（`1`=按教室使用时间）看，页面默认可能是时间模式，
   两种都试一次即可定。

> 校内直连 `zhjw` 即可；校外实测同样 302 → `webvpn.hpu.edu.cn/login/index?redirect_authurl=…`
> （2026-11-20 验证），所以这个查询走的是与课表完全相同的 WebVPN 会话。


---

## 6. App 端实现（0.2.2-hpu 已完成）

| 环节 | 落地 |
|---|---|
| 楼栋/校区字典 | `data/eas/HpuClassroomDict.kt`（写死，不额外请求 `getBuildingsByCampus`） |
| 请求 + 双链路 | `data/eas/HpuFreeRoomClient.kt`：OkHttp 快路 → 失败降级 WebView |
| WebView 页面内抓取 | `data/eas/HpuRoomWebFetcher.kt`：`@JavascriptInterface` 桥回传 HTML |
| 结果解析 | `data/eas/FreeRoomParser.kt`：按 `thead th` 列名取列 |
| 页面 | `ui/room/FreeRoomScreen.kt` + `FreeRoomViewModel.kt`（校园 → 空闲教室） |
| 测试 | `FreeRoomParserTest`（10 例：列序打乱、td 表头、nbsp、登录页、无数据、表单参数） |

### 6.1 三个实现期踩到的坑（都已规避）

1. **相对 URL 发请求**。校外经 WebVPN 访问时，页面会落在
   `https://webvpn.hpu.edu.cn/https/<编码>/eams/…` 这种改写地址上。此时写死
   `https://zhjw.hpu.edu.cn/eams/...` 的 `fetch` 就是跨域请求，会被浏览器直接拦掉。
   传相对路径 `free!search.action` 按页面自身地址解析，直连/改写两种情况都对。
2. **不能按主机名判断"是否登录失效"**。WebVPN 改写后的正常页面也带
   `webvpn.hpu.edu.cn`。判据改成 `login/index` / `redirect_authurl` / `uia.hpu.edu.cn`
   这三类特征，叠加对响应体的登录页特征检测。
3. **0 行 ≠ 没教室**。会话失效时返回登录页、参数不对时返回错误页，
   两者都不能显示成"该时段没有空教室"。现在的判据：
   有表头 → 正常（0 行就是真没有）；无表头且无"无数据"文案 → 报错 + 引导登录。
   另外「全校 + 全天」查出 0 间会在页面上明确提示"多半是接口参数需要更新"。

### 6.2 仍未验证的一件事

HPU 结果表的**实际表头文字**。解析器用别名表匹配（校区/名称/教学楼/容量/教室类型/教室设备配置），
且 0 结果页会把教务原始表头原样显示出来 —— 真机跑一次、截图那一行即可确认识别是否命中。

---

## 7. 真机踩坑记录（2026-11-20，0.2.3 → 0.2.5）

三段真机日志依次否掉了三种实现，最终方案是「填教务自己的表单 + form.submit() 真导航」：

| 版本 | 做法 | 真机结果 | 结论 |
|---|---|---|---|
| 0.2.3 | 页面内 `fetch('free!search.action', {method:'POST'})` | 页面正常落地，122ms 后 `TypeError: Failed to fetch` | 校外 WebVPN 链路上 POST 的 302 会跳 `http://zhjw…`，https 页面里属降级/跨源重定向，浏览器直接拦，且**不返回状态码**，无法诊断 |
| 0.2.4 | 自建隐藏表单（只带我们知道的 12 个字段）`submit()` | `POST free!search.action` 成功、122ms 返回 | 教务回 **「出错了 / Error happened」** 通用错误页（12KB，`div#exceptionStack` 里是 Java 异常栈，默认 `display:none`）——表单缺必填 hidden（如 `semester.id`） |
| 0.2.5 | 按名字把值填进**页面自己的表单**，缺的才补 hidden，再 `submit()`；UA 去掉 `;wv`；登录页走一次 `huauauth/caslogin` 静默续期 | 待验证 | 交出去的是完整表单，字段清单已打进日志（`[freeroom] webview form: action=… fields=[…]`） |

关于错误页的识别：`html.contains("出错了") || html.contains("Error happened")`，
用 `<div id="exceptionStack">` 抽栈给日志（`FreeRoomParser.errorDetail()`），比只打 HTML 前 600 字有用得多——
那 600 字全是 CSS。

两点顺带确认的边界：
- **User-Agent 里的 `;wv` 必须去掉**：WebVPN 会把带 `;wv` 的 WebView 当异常客户端。课表抓取器
  （`HpuEamsWebFetcher.createWebView`）一直这么处理，空闲教室这边 0.2.5 才补上。
- **冷启动第一次查询常撞 WebVPN 登录页**：Cookie 罐里同步过去的 `PHPSESSID` 已过期。
  `https://webvpn.hpu.edu.cn/huauauth/caslogin` 在 TGT 仍有效时能静默换票（登录日志里那条链路就是它），
  所以 0.2.5 撞到登录页会先走这一跳再重开目标页，失败才判 `needLogin`。

### 7.1 `project not selected` —— 最后一个必须的参数

0.2.5 提交完整表单后，教务返回的异常栈（`FreeRoomParser.errorDetail()` 抽出来的）直接点名：

```
EamsException: project not selected
  at com.ekingstar.eams.context.impl.ProjectContextImpl.getProject_internal(ProjectContextImpl.java:31)
  at com.ekingstar.eams.web.action.common.RestrictionSupportAction.getProject(...)
  at com.newcapec.eams.classroom.apply.web.action.FreeAction.search(FreeAction.java:72)
```

教务是 beangle 框架，`FreeAction.search` 第一步就取 project（学年学期项目）。**请求里没有 `project.id` 时直接抛这个异常**，
和 `classroom.*`、`cycleTime.*` 都没关系。值固定 `1`，与课表链路（`HpuEamsClient` 的两处 `project.id=1`）一致。

所以最终请求体 = `project.id=1` + 原来的 12 个字段，缺一不可：

```
project.id=1
classroom.type.id= (空)
classroom.campus.id=1
classroom.building.id=3
seats=
classroom.name=
cycleTime.cycleType=1
cycleTime.cycleCount=1
cycleTime.dateBegin=2026-11-20
cycleTime.dateEnd=2026-11-20
roomApplyTimeType=0
timeBegin=1
timeEnd=11
pageSize=1000
```

### 7.2 `project.id` 必须是 URL 参数，不是表单字段

0.2.6 的真机日志把上一节的结论又推翻一层：

```
[freeroom] webview page probe: title= forms=0 actions=[] frames=[] text= 出错了 Error happened
[freeroom] webview form: action=free!search.action created=true set=[project.id=+1, ...]
[freeroom] webview error page: ... EamsException: project not selected
```

两件事：

1. **入口页 `free!index.action.action` 自己就是错误页**。0.2.5 之所以 `document.forms` 为 0，
   不是"菜单页"，而是这页根本没渲染出来——同样是缺 project。
2. **`project.id=1` 放 POST body 里没人读**。beangle 的 `ProjectContextImpl` 只认请求 URL 上的参数
   （课表链路 `HpuEamsClient` 的两处也是拼在 URL 上：`...&project.id=1&semester.id=...`）。

所以 0.2.7 改成给入口页和查询页都加 `?project.id=1`：

```
https://zhjw.hpu.edu.cn/eams/classroom/apply/free!index.action.action?project.id=1
free!search.action?project.id=1        ← 表单 action 也带
```

顺带加了一条兜底：入口页若仍是「出错了」，就去 `homeExt.action` 把页面上所有含
`classroom` / `free!` / `project.id` 的 `<a href>` 抄进日志（`webview links:`）——
教务自己导航里的那串 href 才是它认的参数，比猜可靠。
