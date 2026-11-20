# 河南理工教务 EAMS 登录与课表协议（真机抓包实测）

> 来源：2026-11-19 用户提供 HAR 抓包（257 条请求），已脱敏。  
> 原始 HAR 存于 `captures/zhjw.hpu.edu.cn.har`（已 gitignore，**绝不入库**）。  
> 本文只记协议结构，不含任何 Cookie/Token/学号值。

---

## 0. 最重要的修正

之前我试 `eams/loginExt.action` 走**账密+验证码**登录，是错的。抓包证明：

> **正确路径是 CAS 单点登录**：`eams/login.action`（带 CAS ticket）→ `eams/loginExt.action`  
> → **`eams/homeExt.action`**（真正首页，用户明确指出）。

只要 UIA 统一认证登录成功（拿到 CASTGC），访问 `eams/login.action` 会自动经 CAS 兑票，  
一路 302 到 `homeExt.action`，**无需在 EAMS 再输账密/验证码**。这解释了为何我之前卡在验证码——走错了门。

---

## 1. 完整登录跳转链（抓包实测）

```
POST https://uia.hpu.edu.cn/cas/login   (账密 + captcha + token + lt + pid + execution)
  → 302  https://uia.hpu.edu.cn/sso/redirect?pid=<...>
  → 302  https://uia.hpu.edu.cn/cas/login?service=...
  → 302  https://uia.hpu.edu.cn/sso/login?redirect_uri=...&x_client=cas&ticket=ST-...
  → 302  https://zhjw.hpu.edu.cn/eams/login.action           (第1次，无 ticket)
  → 302  https://uia.hpu.edu.cn/cas/login?service=.../eams/login.action
  → 302  https://zhjw.hpu.edu.cn/eams/login.action?ticket=ST-...   (带票)
  → 302  https://zhjw.hpu.edu.cn/eams/loginExt.action
  → 302  https://zhjw.hpu.edu.cn/eams/homeExt.action    ✅ 登录完成
```

**实现要点**：OkHttp client 必须 `followRedirects(true)` + 共享 `*.hpu.edu.cn` CookieJar（已实现  
`HpuCookieJar`）。入口直接用 `https://zhjw.hpu.edu.cn/eams/login.action`，不用 webvpn 中转。

---

## 2. 课表接口（核心）

### 2.1 课表页：`GET /eams/courseTableForStd.action`

返回 HTML 页面，内含：

- 当前学期 id：`semesterCalendar({...,value:"244"})` 里的 value（抓包时为 244）
- 当前学生的教学任务 id：`bg.form.addInput(form,"ids","134837")` 多条（抓包有 `134837`、`8527` 等）
- ⚠️ 页内 `searchTable()` 是 `if(setting.kind=="std") addInput(...,"134837") else addInput(...,"8527")` 的 **if/else 两分支**，
  两个 id 不能同时 POST（会拿到空表）。学生账号 `setting.kind=std` → 只用第一个 id（134837）。
- ⚠️ **不要调用页面自带的 `searchTable()`**：真机 17:59 日志实锤 `searchTable() -> "err:jQuery is not defined"`——
  抓取用的 WebView 里外链 jQuery/bg 没加载成功，页面函数体一执行就抛错。
  **最可靠做法是在已登录 WebView 页面上下文里用原生 `fetch()` 同源 POST**，参数复刻 `searchTable()`
  （`ignoreHead=1 / setting.kind=std / startWeek="" / project.id=1 / semester.id=<学期> / ids=<二选一>`），
  相对 URL 自动带会话 Cookie，结果写入 `window.__hpuKbResult`，原生轮询读取 `TaskActivity`。
  （对应 `HpuEamsWebFetcher.fetchCourseTableViaPage()` 的 `kbFetchJs()`；HAUTPocket 同源请求同思路。）
- 项目 id：由 `POST /eams/dataQuery.action` `{dataType:projectId}` 返回（值为 `1` = 河南理工大学）
- 学期下拉：由 `POST /eams/dataQuery.action` `{dataType:semesterCalendar, value:<学期id>, empty:false, tagId:semesterBar…Semester}` 返回  
  `{yearDom:"...", termDom:"..."}` 形式的 JS 对象

### 2.2 取课表数据：`POST /eams/courseTableForStd!courseTable.action`

| 参数             | 示例       | 含义                        |
| -------------- | -------- | ------------------------- |
| `ignoreHead`   | `1`      | 固定                        |
| `setting.kind` | `std`    | 固定（学生课表）                  |
| `startWeek`    | 空        | 起始周                       |
| `project.id`   | `1`      | 项目 id（切换项目时页面会带，见抓包第2次请求） |
| `semester.id`  | `244`    | 学期 id                     |
| `ids`          | `134837` | 教学任务 id（2.1 页里解析）         |

返回 **HTML**（约 57KB），课程数据以 JS 形式内联：

```js
var table0 = new CourseTable(2026,77);   // (year, weekCount)
var unitCount = 11;                       // 一天 11 节
var teachers = [{id:1945,name:"张红涛",lab:false}];
var assistants = [];
var actTeachers = teachers; /* ... */
activity = new TaskActivity(
  actTeacherId.join(','), actTeacherName.join(','),
  "21938(12100008.002)",                 // courseId(课程代码.班号)
  "形势与政策-1(12100008.002)",           // courseName(课名(代码))
  "74",                                   // roomId
  "南校区  南校区1号教学楼\t1406",         // roomName（校区 + 楼 + 房间，制表符分隔）
  "000000010000...",                      // vaildWeeks：53 位 0/1 串（一年 53 周，1=该周有课）
  null,                                   // taskId
  "",                                     // remark
  assistantName,                          // assistantName
  "",                                     // experiItemName
  ""                                      // schGroupNo
);
```

`TaskActivity` 构造参数顺序（来自 `TaskActivity.js`，已确认）：  
`teacherId, teacherName, courseId, courseName, roomId, roomName, vaildWeeks, taskId, remark, assistantName, experiItemName, schGroupNo`

两个必须注意的坑（2026-11-19 真机反馈后修正）：

1. **前两个参数不是字面量**，页面里写的是变量表达式：
   ```js
   activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),
       "21938(12100008.002)","形势与政策-1(12100008.002)",...)
   ```
   直接取第 2 个实参只会得到字符串 `actTeacherName.join(',')`（详情页"教师"一栏曾原样显示这串 JS）。
   真实姓名在同区块前面的 `var teachers = [{id:1945,name:"张红涛",lab:false}];` 里；
   助教（assistants）在此之后会被剔除，所以取 `teachers` 的名字与实任教师一致。

2. **`vaildWeeks` 的下标就是教学周**，第 0 位是恒为 `0` 的占位：
   `"000000010000..."` 表示**第 7 周**，不是第 8 周。曾按"第 n 位对应第 n+1 周"解，
   结果每门课整体后移一周、单双周全部翻转（物理实验 3-17 单周 → 显示 4-18 双周）。
   反证：样本里 高等数学 / 大学外语 / 体育与健康 / 侦探之旅 的首个置位都在下标 1，
   对应"第 1 周开课"这一常识排课；若按 +1 解，全学期没有任何一门课在第 1 周开课。

**星期/节次从哪来**：`vaildWeeks` 只给周次；星期与节次由该 activity 被写入的表格单元格  
`TD<index>_<tableIndex>` 决定（`index = unit*... + day`）。解析时需要把 `TaskActivity` 调用与它紧邻的  
`TD` 单元格位置对应起来。**这是与郑大版 EAS 最大的差异**（郑大版接口直接给 weekday/startUnit）。

### 2.3 学期与项目

- `project.id`：默认 `1`（河南理工大学）
- 学期 id：抓包出现 `224`、`244`、`264`…… 呈 +20 递增（疑为学年学期编码，需确认规律）

---

## 3. 与现有代码的关系

- 登录：UIA 部分用已落地的 `HpuCasClient`；EAMS 免登跳转用 `HpuCookieJar` 共享 Cookie。
- 课表：`EasClient` 的 JSON 接口在这里不适用（HPU 是 HTML 表格），需新写  
  `HpuEamsClient` + HTML 解析器（`TaskActivity` 调用 + `vaildWeeks` 位串 + TD 位置）。
- 目标模型仍是 `EasClient.Session`，解析后回填 `weekIndex/weekday/startUnit/endUnit/date/roomText/campusText`。

## 4. 已解决 / 待确认

✅ **星期/节次映射（已破解并落地）**：`index = day * unitCount + unitIndex`，  
`unitCount=11`，`day=0..6`（周一..周日），`unitIndex=0..10`（第 1..11 节）。  
证据：抓包里 `index =5*unitCount+0/1/2; table0.activities[index][…]=activity`（周六 1-3 节）。  
解析器 `HpuScheduleParser` 已用真实 HTML 验证：正确解析出 23 门课的星期/节次/周次。

- [ ] 学期 id 编码规律（抓包出现 224/244/264，疑为学年学期编码；当前直接用页面给出的 value）
✅ **节次起止时间（已解决）**：课表 HTML 内联了时刻表（第一行"上午 第一节 08:00-08:45"），
且用户提供 2026-2027 校历图确认学校执行**固定两套作息**（春季 5·1–10·1 / 秋季 10·1–5·1）：
- 上午第 1-5 节两套相同：08:00/08:55/10:00/10:55/11:50 起，各 45 分钟；
- 春季第 6-11 节：15:00 / 15:55 / 17:00 / 17:55 / 19:30 / 20:25 起；
- 秋季第 6-11 节：14:30 / 15:25 / 16:30 / 17:25 / 19:00 / 19:55 起（整体早 30 分钟）。
落地：`domain/HpuPeriods.kt`（两套 + 自动按日期切换 + 手动固定），偏好 `periodMode`，
设置入口在「课程表设置」，课表 VM 与桌面组件经 `ScheduleRepository.periods()` 自动取生效作息。

## 5. 安全与合规

- HAR 含 Cookie/令牌，已放 gitignore 目录，分析后只保留本文的结构化结论；
- 实现时不持久化密码，只保存会话 Cookie（TGT + EAMS JSESSIONID）；
- 教学数据仅本机存储，与上游项目口径一致。
