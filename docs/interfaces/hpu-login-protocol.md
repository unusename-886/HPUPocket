# 河南理工大学 统一认证 / 教务 登录协议（真机实测）

> 实测时间：2026-11-19。使用测试账号完成**统一认证登录全流程并成功**（拿到 TGT），
> 教务 EAMS 登录页的加密逻辑已从页面脚本完整还原。
> ⚠️ 本文只记录**协议结构**，不含任何账号、密码、令牌值。凭据绝不可写进代码或仓库。

---

## 1. 总体链路（已验证）

```
应用启动
  └─ GET  https://uia.hpu.edu.cn/cas/login?service=<目标>
           → 表单: username / password / captcha / token / lt / pid / execution / _eventId / source
           → Cookie: X_CAPTCHA=true（存在即强制图形验证码）
  ├─ GET  https://uia.hpu.edu.cn/sso/apis/v2/open/captcha?type=EASY|ALPHABET[&captchaSize=4]
           → JSON { img: <base64 jpeg>, token: "<32位hex>" }
           → 图片是**算术题**（如 "5+8=?"）或字母，需识别；token 必须随登录表单提交（name="token"）
  └─ POST https://uia.hpu.edu.cn/cas/login  (application/x-www-form-urlencoded)
           → 成功: Set-Cookie CASTGC=TGT-xxx; TENANT_TICKET=xxx; 无错误
           → 失败: 页面里的 #errormes 给出原因（验证码输入有误 / 密码错误 等）
```

**关键结论（实测）**：
- 密码**明文 POST**（依赖 HTTPS 保护），没有 RSA/AES 加密（登录 JS 的 `transform()` 只搬运隐藏字段）。
- **必须有图形验证码**：Cookie `X_CAPTCHA=true` 时 `ishasCode=true`，验证码字段出现。
  EASY 类型是算术题，ALPHABET 是字母（可带 `captchaSize=4`）。
- `<input name="token" id="captchatoken">` = 验证码接口返回的 token，**必须一起提交**，否则必判"验证码输入有误"。
- `lt`/`pid`/`execution` 从登录页隐藏字段取，`execution` 实测恒为 `e1s1`。

### App 登录入口：必须从官网教务链接起步（2026-11-19 真机踩坑修正）

**现象**：App 早期用 `https://uia.hpu.edu.cn/cas/login?service=https://webvpn.hpu.edu.cn/huauauth/caslogin`
直接起步，真机登录后**卡在 WebVPN 的空白/资源页**，不会自动跳教务；而手机浏览器从
`https://www.hpu.edu.cn` 点「教务管理系统」能正常进课表。

**原因**：WebVPN 的 `huauauth/caslogin` 回调页需要 `redirect_authurl` 才知道登录后往哪跳。
直接用 CAS 起步时 service 没带这个参数 → 兑票成功后 WebVPN 不知道目标是教务，停在自身页面。

**正确入口**（取自官网首页 `www.hpu.edu.cn` 的「教务管理系统」链接）：

```
https://zhjw.hpu.edu.cn/eams/login.action
  → 302 https://webvpn.hpu.edu.cn/login/index?redirect_authurl=<上面这个教务URL>
  → 302 /huauauth/caslogin → uia CAS 登录页（用户输学号/密码/验证码）
  → 登录成功带 ST 回 WebVPN → 凭 redirect_authurl 跳回 zhjw/eams/
```

WebView 侧配套（缺一会在 WebView 里卡住）：
- `setAcceptThirdPartyCookies(true)` + `setAcceptCookie(true)`：多子域来回跳要放行第三方 Cookie；
- UA 去掉 `;wv`（避免 WebVPN 把 WebView 当异常客户端）；
- 捕获落地放宽为两种：`zhjw.../eams/` **或** `webvpn...ticket=ST`（兑票成功即收 CASTGC，
  不必等 WebView 完成最后的教务跳转，那一步在 WebView 里可能不完成）。

代码落点：`HpuCasClient.ENTRY_JW`、`ui/login/HpuLoginScreen.kt`。

### TGT 穿过 WebVPN（已验证）

```
GET https://zhjw.hpu.edu.cn/
  → 302 webvpn.hpu.edu.cn/login/index?redirect_authurl=...
  → 302 /huauauth/caslogin
  → 302 uia.hpu.edu.cn/cas/login?service=https://webvpn.hpu.edu.cn/huauauth/caslogin
  → 302（已带 CASTGC，自动兑票）uia.hpu.edu.cn/sso/redirect?pid=...
  → 302 webvpn.hpu.edu.cn/huauauth/caslogin?ticket=ST-xxx
  → 302 zhjw.hpu.edu.cn/huauauth?cookie_HuauAuthName=<加密串>&auth_url=...
  → 落地 eams/home.action
```

**这就是原项目 `NoxGatewayClient` 那类"网关自动认证"的同构场景**：登录过 UIA 后，
访问教务会被自动带着兑票，无需用户再次输入账号密码。实现时可照搬"探测 → 兑票 → 存会话"的编排。

---

## 2. 教务 EAMS（树维，已还原加密）

落地后 `eams/home.action` 会跳到 `eams/loginExt.action`——WebVPN 的 `HuauAuthName` Cookie
**不足以直接登录 EAMS**，EAMS 有自己的账号体系（页面带"忘记密码?/从平台登录"）。

### 登录表单（`/eams/loginExt.action` POST）

| 字段 | 生成方式 |
| --- | --- |
| `username` | `base64( AES-128-ECB-PKCS7(学号, key="d864eb66e6343885") )` |
| `usernameKey` / `usernameKey2` | 固定 `72a478b5af1623a2` |
| `password` | `SHA1("d864eb66e6343885-" + 明文密码)`（40 位 hex） |
| `passwordKey` / `passwordKey2` | 固定 `d7f0e9b3-8b98-4b82-95bb-137923715fd6` |
| `captcha_response` | 图形验证码，`GET /eams/captcha/image.action` |
| `session_locale` | `zh_CN` |
| `eams_login` | `eams_login` |

- 页面 JS 原文（`encrypt`）：
  ```js
  function encrypt(content, key){
    var sKey = AesJS.enc.Utf8.parse(key);
    var sContent = AesJS.enc.Utf8.parse(content);
    var encrypted = AesJS.AES.encrypt(sContent, sKey, {mode:AesJS.mode.ECB, padding:AesJS.pad.Pkcs7});
    return encrypted.toString();
  }
  ```
  即 CryptoJS 的 AES-128-ECB / Pkcs7，输出 OpenSSL base64。
- `SHA1` 来自页面 `/eams/static/scripts/sha1.js`。
- 验证码 4 位数字，接口会 `Set-Cookie: HuauAuthName=...`，**GET 与 POST 必须同一 Cookie 会话**。
- ⚠️ 页面明确警告："连续输错 5 次后账号自动锁定 24 小时"。**调试时严控失败次数**。

### 与郑大版的同源性（重要）

`zhjw.hpu.edu.cn/eams/...` 与郑州大学 `jwxt.zzu.edu.cn/eams/...` **同为树维 EAMS**。
原项目 `data/eas/EasClient.kt` 的课表/教室/学期接口路径（`eams/courseTableForStd.action`、
`eams/home.action` 等）大概率可直接复用，主要差异：
1. 域名不同（`zhjw.hpu.edu.cn`）；
2. 多一层 WebVPN/HuauAuth 兑票；
3. 登录加密的 key 可能不同（需在真机页面确认，本文 key 来自当前线上页面）。

> ⚠️ 本次实测中 EAMS 登录页**验证码识别未通过**（验证码答案识别/会话绑定有偏差），
> 未拿到 EAMS 的 JSESSIONID，因此**课表接口尚未实际取到数据**。
> 但"UIA 登录成功 + TGT 穿 WebVPN 到 EAMS 登录页"这两步已 100% 证实。

---

## 3. 结论与实现建议

> ⚠️ 第 5 节有 App 真机日志的**最新修正**：实际登录不落 CASTGC/TGT，而靠 `HuauAuthName`+`JSESSIONID`；
> 校外直连教务会被 WebVPN 拦截。以下 1/2 条为实现思路，具体以第 5 节为准。

1. **P1 登录**：按第 1 节实现 UIA CAS 登录。验证码需要识别方案：
   - 方案 A（推荐先做）：App 内 WebView 打开学校登录页，用户自己看验证码输入；登录成功后只取 Cookie/TGT，
     不存密码（与郑大版"研究生教务 WebView 登录"同款做法，`GmsCookieJar` 可直接借鉴）。
   - 方案 B：原生表单 + 本地 OCR 识别算术/字母验证码（不稳定，验证码样式一变就废，不推荐）。
2. **P2 课表**：优先复用 `EasClient`，把 domain 换成 `zhjw.hpu.edu.cn`，并在 `HttpClients` 里加 WebVPN
   兑票逻辑（复用 UIA 的 TGT）。需再确认 EAMS 登录的验证码与账号密码是否同一套。
3. **安全**：验证码接口返回 base64 图片；密码在 UIA 侧明文 POST（走 HTTPS），在 EAMS 侧 SHA1。
   无论哪种都**不得在客户端持久化密码**，只保存 TGT/会话 Cookie。
4. **合规**：连续错误会锁号 24 小时，自动化测试必须限次、失败即停，不要写循环重试。

---

## 5. ★ App WebView 登录真机日志修正（2026-11-19 晚，权威）

App 内 WebView 走官网入口完成登录，导出的 `nav:` URL 链证实完整跳转：

```
zhjw.hpu.edu.cn/eams/login.action
  → 302 webvpn.hpu.edu.cn/login/index?redirect_authurl=<教务URL>
  → webvpn.hpu.edu.cn/huauauth/caslogin
  → uia.hpu.edu.cn/cas/login?service=<webvpn回调>
  →（用户输学号/密码/验证码）
  → uia.hpu.edu.cn/sso/redirect?pid=xxx
  → webvpn.hpu.edu.cn/huauauth/caslogin?ticket=ST-xxx
  → zhjw.hpu.edu.cn/huauauth?cookie_HuauAuthName=<密文>&auth_url=<教务URL>
  → zhjw.hpu.edu.cn/eams/login.action?ticket=ST-xxx
  → zhjw.hpu.edu.cn/eams/loginExt.action
  → zhjw.hpu.edu.cn/eams/homeExt.action   ← 登录成功落地
```

**关键修正（推翻第 1 节的 CASTGC 假设）**：
- 登录成功后 CookieManager 里**没有 CASTGC/TGT**。实际有效会话 Cookie（zhjw/webvpn/uia 域合并）：
  `JSESSIONID`、`GSESSIONID`、`srv_id`、`HuauAuthName`、`HuauAuthNameLogin`、`CAS_TICKET`、`PHPSESSID`、`X_CAPTCHA`。
- 学校改用**WebVPN 网关票据 `HuauAuthName`**（`zhjw/huauauth?cookie_HuauAuthName=...` 植入）+ EAMS 的 `JSESSIONID/GSESSIONID`
  维持会话，不是标准 CAS TGT。故 `HpuSessionStore.adoptFromWebView` 只要见到这些 cookie 任一即采纳，
  不再强制 CASTGC。
- `HuauAuthName` 同名 cookie 在 uia/webvpn/zhjw/hpu.edu.cn 多域都存在且**值可能不同**；
  收集时必须让 **zhjw 子域最后写入**（课表抓的是 zhjw），否则被父域同名值覆盖会导致网关不认。

**校外 WebVPN 坑（课表无数据根因）**：
- 校外网络直连 `zhjw.hpu.edu.cn` 会先被 302 到 `webvpn.hpu.edu.cn/login/index`。
- 若 `HpuSessionStore.isValid()` 用 OkHttp 直连 `login.action` 并把"被弹回"判为失效，
  即使 WebView 已成功登录也会误判 → `sync()` 直接返回 NotLoggedIn → **课表根本不抓**（表现"登录成功无数据"）。
- 修复：`isValid()` 改为**本地会话标志判断**（有 JSESSIONID/GSESSIONID/HuauAuthName/CAS_TICKET 即有效），
  网络探测仅写诊断日志、不参与判定；真正有效性由 `fetchSemesterPage/fetchCourseTable` 抓取结果决定。
- `HpuEamsClient` 已加 GET 响应码/最终URL/bodyLen + 是否弹回登录页的日志，下次可从日志直接判断。

### ★★ 决定性证据：OkHttp 直连必被弹回，课表必须走 WebView（2026-11-19 晚）

带新埋点的真机日志（17:01）给出了铁证：

```
[session] isValid(local)=true                       ← 会话判断已修好
[eas] GET https://zhjw.hpu.edu.cn/eams/courseTableForStd.action
      -> code=200 finalUrl=https://zhjw.hpu.edu.cn/eams/loginExt.action bodyLen=9783
[eas] fetchSemesterPage: bounced=true parsed=false
```

- `isValid(local)=true` 证明会话保存与本地判断都已正确，上一步的修复生效；
- 但 OkHttp 请求课表页被 **302 弹回 `loginExt.action`**（code=200 但 body 是 9783 字节的登录页），
  说明 **WebVPN 网关的会话上下文只在 WebView 里完整**，OkHttp 即使带着导出的 Cookie 也模拟不出来。
- 另外登录链里 CAS 回跳用明文 `http://zhjw.hpu.edu.cn/eams/login.action?ticket=...`，
  OkHttp 探测报 `CLEARTEXT communication not permitted`；已在 network_security_config 放行 hpu.edu.cn。

**修复（架构级）**：新增 `HpuEamsWebFetcher`，用一个常驻 WebView 抓教务页面——
它与登录 WebView 共享进程级 `CookieManager`，会话天然可用；`evaluateJavascript(outerHTML)`
取回 HTML 后交给纯 Kotlin 的 `HpuScheduleParser`（解析器不变）。
`HpuEamsClient.fetchSemesterPage()/fetchCourseTable()` 优先走 WebFetcher，OkHttp 仅在无 WebView 的
纯 JVM 单测里兜底。这样等于重放"用户在浏览器里能进教务"的那条路，是唯一可靠的抓取通道。

## 6. 待确认（下次继续）

- [ ] EAMS 登录验证码的正确识别（建议在真机浏览器完成一次，抓 JSESSIONID）；
- [ ] EAMS 账号密码是否与 UIA 统一认证相同（本次用的是 UIA 密码，EAMS 侧提示"密码错误"，可能两套）；
- [x] `eams/courseTableForStd.action` 学期页结构（semesterCalendar value + addInput ids）；
      `courseTableForStd!courseTable.action` POST 返回内联 JS（HpuScheduleParser 已解析，单测全绿）；
- [ ] 真机验证 WebFetcher 方案能抓到课表数据（本轮交付待用户装包实测，重点看新日志
      `GET(WebView)` / `webview onPageFinished` / `fetchSemesterPage: parsed=true` 三行）；
- [ ] 节次时间表（第一到十一节起止时刻）。
