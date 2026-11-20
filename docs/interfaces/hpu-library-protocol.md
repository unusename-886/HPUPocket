# 河南理工 图书馆（座位 / 研讨间预约）接口

> **⚠️ 状态：仅协议调研，App 尚未实现。**
> 本仓库当前**没有**图书馆相关代码（无 `data/lib/`、无图书馆页面、无 seatlib/roomlib 请求），
> 「更多 → 校园服务」里也没有图书馆入口。本文是开工前的协议底稿，不是已完成功能的说明。
> 若要做，先看 §5「待真机验证」——那里 5 项没测完，写出来的代码大概率是假实现。

> 调研时间：2026-11-20。目标系统：`seatlib.hpu.edu.cn`（座位）、`roomlib.hpu.edu.cn`（研讨间）。
> 结论：**座位系统是「利昂软件 leosys」的 libseat**，REST + token，接口形状已有公开实践可对照；
> 校外访问与教务同构——**全部被 WebVPN 拦截**，必须先过 `webvpn.hpu.edu.cn`。

---

## 1. 入口与访问边界（本次实测）

图书馆主页 `lib.hpu.edu.cn` 的「座位预约」按钮指向：

```
https://seatlib.hpu.edu.cn/libseat/#/login     ← 利昂 libseat 的 SPA（hash 路由）
```

研讨间是另一个独立域名（同一套观感，疑似同厂商）：

```
https://roomlib.hpu.edu.cn/                    ← 研讨间预约
```

**校外直连一律 302 到 WebVPN**（实测 2026-11-20，本机公网 IP）：

| 请求 | 结果 |
|---|---|
| `GET https://seatlib.hpu.edu.cn/` | `302 → https://webvpn.hpu.edu.cn/login/index?redirect_authurl=https%3A%2F%2Fseatlib.hpu.edu.cn%2F` |
| `GET https://seatlib.hpu.edu.cn/libseat/` | `302 → …redirect_authurl=https%3A%2F%2Fseatlib.hpu.edu.cn%2Flibseat%2F` |
| `GET https://seatlib.hpu.edu.cn/rest/auth?...` | `302 → …redirect_authurl=<原路径含 query>` |
| `GET https://roomlib.hpu.edu.cn/` | `302 → …redirect_authurl=https%3A%2F%2Froomlib.hpu.edu.cn%2F` |

要点：

1. **拦在整个主机上**，不是只拦页面——`/rest/*` 同样 302，所以"直接打 API"在校外不成立。
2. `redirect_authurl` **保留了完整路径和 query**，说明网关是"登录后原样回跳"，与教务
   `zhjw/eams/...` 一模一样的机制（见 `hpu-login-protocol.md`）。
3. 因此 App 侧有两条可行路（都待真机确认）：
   - **A. 复用现成 WebVPN 票据**：`HpuSessionStore` 已把 `HuauAuthName` 网关票写入各子域，
     若把该 cookie 也种到 `seatlib.hpu.edu.cn` 就能直连 REST（App 现在就是这么"每个域都设一遍"的）；
   - **B. WebView 兜底**：进 `seatlib` 首页让网关自己完成一次 SSO，代价是拿不到结构化的
     "空闲座位"数据，只能看页面。

---

## 2. 登录与会话

```http
GET /rest/auth?username=<学号>&password=<统一认证密码>
```

- 明文 query（HTTPS 传输）；账号密码与统一认证同一套（图书馆说明原文："输入学号和统一身份认证密码"）。
- 成功：`{"status":"success","data":{"token":"..."},"code":"0","message":""}`
- 失败：`{"status":"fail","code":"13","message":"登录失败: 用户名或密码不正确","data":null}`
  （另有 `code:"10"` + `System Maintenance`，夜间维护时段）
- 之后所有请求带 `token`（URL 参数或 `Authorization`，两种在开源实现里都见过；**以真机为准**）。

> ⚠️ 这是本模块与 App 现有登录态**唯一不兼容**的地方：教务走 WebVPN 票据、不碰密码，
> 而座位系统要 `/rest/auth` 明文账密。产品上必须明示"图书馆需单独授权一次"，
> 且密码默认不落盘（或至少进 EncryptedSharedPreferences，与现有凭据同一档保护）。

---

## 3. 座位接口清单

| 用途 | 方法与路径 | 关键参数 | 返回 |
|---|---|---|---|
| 登录 | `GET /rest/auth` | `username`、`password` | `data.token` |
| 用户信息 | `GET /rest/v2/user` | `token` | `id / username / lastLogin / checkdIn / violationCount` |
| 历史预约 | `GET /rest/v2/history/{page}/{size}` | `token` | `data.reservations[]` |
| 违约记录 | `GET /rest/v2/violations` | `token` | `data[]` |
| 当前预约 | `GET /rest/v2/user/reservations` | `token` | `data[0]`：`seatID / location / onDate / begin / end / status` |
| 预约座位 | `POST /rest/v2/freeBook` | `token`、`seat`、`startTime`、`endTime`、`date` | 预约结果 |
| 楼层概况 | `GET /rest/v2/room/stats2/{libraryId}/{date}` | `token` | `roomId / room / floor / totalSeats / free / reserved / inUse / away` |
| 可用座位 | `GET /rest/v2/searchSeats/{date}/{begin}/{end}` | `token`、`roomId`、`batch`、`page` | `data.seats`、`page`、`hasMore` |

细节：

- **时间格式**：`startTime` / `endTime` 是**当天第几分钟**（9:00 → `540`，18:00 → `1080`）；
  `date` 是 `YYYY-MM-DD`；可用座位查询里 `begin=-1` 表示"现在"。
- **库/房间层级**：`libraryId`（南校区第一/第二图书馆等）→ `roomId`（楼层里的阅览区/自主学习空间）
  → `seatID`。主页那张统计卡（"座位总数 3226 / 已预约数 / 研讨间 36"）就是这类 stats 的聚合。
- **预约规则**（官方说明，影响 UI 文案）：当晚 19:30 放次日；单次 0.5–4 小时，结束前 60 分钟可续
  0.5–2 小时；签到靠闸机刷卡/刷脸或预约机，**App 代签不可能**；7 天内 3 次违约停权 7 天。
- **座位 ID 会变**：公开整理者原话"座位 ID 已经变了"，说明 `seatID` 不是长期稳定的主键——
  缓存要带版本号，宁可每次现拉楼层座位图，也不要落库当字典。

参考来源（同厂商、可对照字段）：
- `mater1/hpu-lib-api`（HPU 本人整理的野生文档，2021 年抓包）
- 其他部署同款 `libseat` 的学校，如 `seat.xmmc.edu.cn/libseat/#/login`

---

## 4. 研讨间（`roomlib.hpu.edu.cn`）

- 同样校外 302 到 WebVPN，页面上与「座位预约」并列（图书馆主页 `roomlib.hpu.edu.cn` 外链）。
- **接口未测绘**：尚未进入过该系统（需 WebVPN 内打开）。从首页文案看是"研讨间总数 36 / 已预约数"，
  很可能与座位系统同厂商同形状（`/rest/...`），但**没有证据不下结论**。
- 下一步：VPN 内打开 `roomlib.hpu.edu.cn`，F12 看首个 XHR 是否也是 `/rest/auth`。

---

## 5. 待真机验证（按优先级）

| # | 待验证 | 怎么验 | 影响 |
|---|---|---|---|
| 1 | `HuauAuthName` 种到 `seatlib` 域后，`/rest/auth` 是否放行 | App 里登录后直接请求，看 200 还是 302 | 决定"一键查座位"能否不额外登录 |
| 2 | `token` 的有效期 | 连续两天用同一 token | 决定缓存与重登策略 |
| 3 | `token` 放 query 还是 header | 两者各试一次 | 影响客户端实现 |
| 4 | HPU 的 `libraryId` 全表 | `stats2/{id}/{today}` 遍历 1..5 | 选馆下拉的数据源 |
| 5 | 研讨间是否同款 REST | 进系统看首个 XHR | 研讨间模块能否复用一套客户端 |

---

## 6. 与 App 的对接建议

- **不碰预约写操作**（`freeBook`）：签到/签离依赖闸机，代预约只会制造违约记录。层 1「只读」：
  楼层空闲概况 + 我的当前预约 + 历史记录。
- 只读数据适合**短缓存**（座位状态秒级变化）：页面打开时现拉，不做定时轮询。
- 会话：优先走 §1 的 A 路（复用网关票）；A 路不通再退 WebView，并把"图书馆"入口做成
  "有结构化数据就原生渲染、否则整页 WebView"的混合形态。
- 客户端写法照 `SdcxElectricityClient`：独立 OkHttp + 独立 CookieJar（与教务会话隔离，
  避免 `JSESSIONID` 互相覆盖）。
