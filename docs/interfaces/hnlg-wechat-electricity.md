# 河南理工 微后勤「宿舍用电」H5 逆向（2026-11-20）

> 用户给定入口：`https://whgfwdt.hpu.edu.cn/hnlg/index.html#/home/index`（微信内网页）。
> 限制：微信内无法 F12、无法看 Network。
> **绕过办法**：JS 包是公开静态资源，直接用 `curl` 下载 bundle 做静态逆向 —— 完全不需要 DevTools/抓包/令牌。
> 本次所有结论均来自下载后的 JS（主包 1.2MB + 若干懒加载 chunk），未使用任何登录态。

---

## 1. 结论先行（决定实现方案）

| 问题 | 结论 | 依据 |
|---|---|---|
| 能否像旧 `sdcx.hpu.edu.cn` 那样纯 OkHttp 查电量？ | **不能** | 所有 `/fapi` 数据接口无令牌一律 `401 {"code":401,"message":"no_session"}`，实测确认 |
| 令牌从哪来？ | **微信 OAuth**（`snsapi_base`，appid `wx3fcea4626809c787`），或本站 PC 扫码登录 | `oiGet` / `hnlgOpenid` 302 到 `open.weixin.qq.com/oauth2/authorize` |
| 令牌存哪？ | 浏览器 `localStorage["token"]`，作为 `Authorization` 头发到 `/fapi` | 请求拦截器 `n.headers.Authorization=localStorage.getItem("token")` |
| 所以 App 该怎么接？ | **用应用内 WebView 承载这个 H5**，让站点自己完成 OAuth/扫码登录；不做原生 HTTP 客户端 | 见 §5 |
| 是否有微信外的兜底登录？ | 有 PC 扫码登录（`oiGet?token&url=` 出二维码 + `getWebLoginScanStatus` 轮询），可在 WebView 中用微信扫码完成 | §4 |

⚠️ 与另一个电费源 `sdcx.hpu.edu.cn`（ASP.NET WebForms、**免登录**、纯 HTTP 可查、协议见 [hpu-electricity-protocol.md](hpu-electricity-protocol.md)）**不是同一套系统**，不要混用：
- `sdcx`：北校区、楼栋→楼层→房间三级联动、剩余电量写死在 HTML 文本里、无需登录。
- `hnlg`（本文）：微后勤数字服务大厅，南/北校区，Vue SPA，全接口要微信会话，电费用量记录走 `water_electricity_fee`。

---

## 2. 站点结构（Vite + Vue3 SPA）

```
GET https://whgfwdt.hpu.edu.cn/hnlg/index.html
  → <script type="module" src="./assets/index-COebV7Ox.js">   (主包, ~1.2MB)
  → <link href="./assets/index-BZJ51RP4.css">
所有接口：baseURL = /fapi        （axios 实例 Pc()/v7()）
静态图片：/publicImage/hnlg/<name>.png
```

懒加载 chunk（`__vite__mapDeps` 里列出全部文件名），与用电直接相关的：

| chunk | 作用 |
|---|---|
| `choose-*.js` | **三级联动选楼栋/楼层/房间** → `/utility_bill/choose`、`/utility_bill/id_set` |
| `index-Dylv3JkM.js` | 用电「首页/结果页」 → `/utility_bill/index` |
| `electricFeePayment-*.js` | 购电入口：查 `register` 状态，未登记要填学号+姓名，最后**跳转一卡通** |
| `water_electricity_fee-*.js` | **水电用量/费用历史记录**页 |
| `utilityBillArea-*.js` | 南/北校区 area 状态（localStorage `hnlgUtilityBillArea`，默认 north） |
| `oauthRelay-*.js` | `/oauth/completeRelay?job=` 换取跳转 URL |
| `login-*.js` | PC 扫码登录（二维码 + 轮询登录态） |
| `wechatUtil-*.js` | 判断 MicroMessenger、`oiGet` 相关 |

---

## 3. 鉴权模型（为什么原生 HTTP 走不通）

请求拦截器（`Pc()`）逻辑：
1. `token = localStorage.getItem("token")`，有则加 `Authorization: <token>`；
2. 部分下单类接口另加签名（`reqAction/reqNonce/reqTimestamp/reqToken`，来自 `/orderFood/requestToken`）——**查询类接口不需要签名**；
3. 响应 `code===401` → 清会话 + 跳 `/home/index`。

令牌获取：
- 微信内：访问 `/fapi/wxApi/oiGet` 或 `/fapi/openid/hnlgOpenid` → 302 到
  `https://open.weixin.qq.com/connect/oauth2/authorize?appid=wx3fcea4626809c787&...&scope=snsapi_base#wechat_redirect`
  → 回调带 `code` → 服务端换 openid/令牌 → 落到 localStorage。
- 微信外（PC/本 App WebView）：站点检测到非微信会显示「请在微信客户端打开链接」；
  但 `login-*.js` 的扫码路径可绕开：`oiGet?instanceToken+url=` 生成二维码，微信扫后
  `POST /index/getWebLoginScanStatus {tokenParam}` 轮询到 `status=success`，
  再 `getUserInfo()` 拿到用户信息即算登录成功（实测未登录轮询返回 `invalid_token`，协议成立）。
- 跨站嵌入：`?hnlgOpenidCache=<key>` + `POST /openid/borrowedSession {cacheKey}` 可借用已登录 openid 会话
  （`hnlgOpenid` 在跨域壳页里用 `?ref=<hash>` 传参）。

**实测（无令牌，curl）**：
```
POST /fapi/utility_bill/choose            → 401 no_session
POST /fapi/utility_bill/index             → 401 no_session
POST /fapi/utility_bill/electricFeePayment→ 401 no_session
GET  /fapi/community_service/water_electricity_fee?ref=test → 401 no_session
GET  /fapi/index/getUserInfo              → 200 {data:null}   （不校验）
POST /fapi/index/init                     → 200 {name:"游客"} （不校验）
```

---

## 4. 用电相关接口清单（供将来原生化参考，当前不可直接调用）

| 接口 | 方法/参数 | 返回 | 出处 chunk |
|---|---|---|---|
| `/utility_bill/choose` | POST `{area, cho_index, cho_data, view_state}` | `{value_list:[[id,label]...], view_state}` | choose |
| `/utility_bill/id_set` | POST `{data:{building,floor,room}, area}` | 保存所选房间 | choose |
| `/utility_bill/index` | POST | 电量/余额结果页数据 | index-Dylv3JkM |
| `/utility_bill/electricFeePayment` | GET `{}` | `"register"`/`"register1"`/其他；register 时需登记 | electricFeePayment |
| `/utility_bill/studentRegister` | POST `{student_number, name}` | `code=1/2` 成功 | electricFeePayment |
| `/community_service/water_electricity_fee` | GET `?ref=<suite_address>` | `{data:[{beginTime,endTime,wPreScore,wNowScore,wNum,wFee,ePreScore,eNowScore,eNum,eFee}...]}` | water_electricity_fee |
| `/community_service/water_electricity_fee` 的 `suite_address` | 来源：路由参数 `route.params`，形如 `["e"<或"w">, "房号"]` | — | water_electricity_fee |

**购电跳转目标**（`electricFeePayment-*.js` 内硬编码）：
```
http://ykt.hpu.edu.cn/server/auth/host/open?host=27&org=2&mer=0&app=electric&s_host=13
```
即一卡通(electric)支付页 —— WebView 内 `tel:` 与外链需放行。

**水电记录字段语义**（`water_electricity_fee`）：
- `type=w` 水费：读数 `wPreScore/wNowScore`、用量 `wNum`、费用 `wFee`，单位「吨」
- `type=e` 电费：读数 `ePreScore/eNowScore`、用量 `eNum`、费用 `eFee`，单位「度」
- 时间 `beginTime/endTime`，兼容下划线字段名；`0/0.0/0.00/无` 视为空。

---

## 5. App 接入方案（已实施）

> **2026-11-20 真机验证修正**：应用内 WebView 打开 H5 会立刻跳
> `open.weixin.qq.com/oauth2/authorize?scope=snsapi_base` 并卡死 —— 微信 OAuth 靠
> JSBridge 校验真实微信客户端，普通 WebView（伪造 UA 也不行）根本过不去。
> **结论：微后勤 H5 在任何非微信客户端都无法登录，WebView 仅作"看页面"用途，不是查询主路径。**
>
> 因此最终落地为**双入口**：
> 1. **主路径（原生、免登录）**：`ui/electricity/` 走 `sdcx.hpu.edu.cn` 免登录站，
>    楼栋→楼层→房间三级联动查剩余电量（协议见 `hpu-electricity-protocol.md`），
>    App 内直接出结果，不依赖微信。2026-11-20 实测拿到 `7#201剩余电量：XXX.XX 度`。
> 2. **补充入口（WebView）**：`ui/web/HnlgWebScreen.kt` 承载本 H5，供"看完整用电明细/缴费"，
>    需用户在微信环境完成验证（或 PC 扫码）。
>
> 用户点「更多 → 校园服务 → 宿舍用电」进主路径；页内「去微后勤看完整信息/缴费」进补充入口。
> 2026-11-20 再修：主路径支持**南/北校区切换**（南 `183.169.***.***:8080` / 北 `sdcx.hpu.edu.cn`），
> 解析兼容南校区中文房号；查询结果缓存在 `ElectricityPrefsStore`，**首页课表下方**显示上次电量卡（不联网）。

微后勤 WebView 页的实现（保留作补充入口）：

- 入口：`更多 → 校园服务 → 宿舍用电`（`CampusScreen` + `AppNav` 的 `ROUTE_ELECTRICITY`）。
- 加载 `SchoolConfig.URL_HNLG`，即用户给定的 hash 路由。
- WebView 配置：`javaScriptEnabled`、`domStorageEnabled`(localStorage 存 token)、
  `CookieManager` 接受第三方 Cookie、`userAgent` 去掉 `;wv`（与可登录的移动端环境一致）。
- 注入 `WebViewVhFix`（本工程 WebView 的 `vh=0` 老问题，见 `图书馆WebView灰屏.md`）。
- `shouldOverrideUrlLoading`：`tel:` → 系统拨号；`http(s)` 站内继续 WebView 内跳转；
  微信 OAuth (`open.weixin.qq.com`) 允许加载以便完成登录。
- 顶栏提供「在微信中打开」兜底按钮（用微信扫码登录更稳妥）。

**取舍**：不做原生查询客户端 —— 令牌只有微信 OAuth/扫码能拿，App 里硬编码或让用户粘贴令牌既不安全也易失效；
WebView 让站点自己管登录态与接口变更，维护成本最低。若将来学校提供免登录的公开查询 API，
可按 §4 接口清单原生化。

---

## 6. 逆向方法（无需 F12，可复用）

1. `curl https://whgfwdt.hpu.edu.cn/hnlg/index.html` 取到 bundle 文件名；
2. `curl .../assets/index-COebV7Ox.js` 下载主包；从 `__vite__mapDeps` 拿到全部 chunk 名；
3. 按名字挑用电/登录相关 chunk 下载（`choose`/`electricFeePayment`/`water_electricity_fee`/`login`/`oauthRelay`）；
4. `grep -oE` 提取路径字符串、接口调用、中文文案，拼出协议。

抓下来的 JS 存 `captures/hnlg/`（`.gitignore` 已忽略 `/captures`），**不入库**。
本文件只固化结构与结论，不含任何会话数据。
