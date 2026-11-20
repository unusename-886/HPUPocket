# Android Studio 试运行操作指南

> 面向河南理工版（HPUPocket）首次在真机/模拟器上跑起来。按章节顺序操作即可。  
> 生成时间：2026-11-19。

## 0. 先纠正一个说法 & 三个前提

**猫咪图是「应用图标」（launcher icon），不是启动图（splash screen）。** 项目里没有任何启动图配置，  
启动就是普通 Activity 直接进。猫咪图的位置和含义：

| 文件                                                                | 作用                   |
| ----------------------------------------------------------------- | -------------------- |
| `res/drawable-nodpi/ic_launcher_foreground.png`                   | 自适应图标**前景**（猫咪）      |
| `res/drawable-nodpi/ic_launcher_monochrome.png`                   | Android 13+ 主题图标单色剪影 |
| `res/mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml` | 把前景 + 背景色合成应用图标      |
| `res/values/colors.xml` 的 `ic_launcher_background = #C8D7F6`      | 图标背景色（浅蓝）            |
| `AndroidManifest.xml` 的 `android:icon="@mipmap/ic_launcher"`      | 声明应用图标               |

三个前提（决定了下面为什么这么设置）：

1. **Gradle JDK 必须是 21**。项目根 `gradle.properties` 的注释写明：Gradle 8.9 最高支持 JDK 22，  
   而新版 Android Studio 自带的 JBR 可能是 JDK 25，直接用它同步会报  
   `Unsupported class file major version`。**别用默认的 JBR，要手动选 JDK 21**（见 §2）。
2. **AGP 8.7.3 + compileSdk 36 是有意为之，不要升级**。官方表要求 API 36 至少 AGP 8.9.1，  
   但项目用 `gradle.properties` 里的 `android.suppressUnsupportedCompileSdk=36` 压掉了警告并实测可用。  
   擅自升级 AGP/Gradle/Kotlin 会引入一堆连锁改动且本机无法验证，**保持现状**。
3. **仓库已配国内镜像**（`settings.gradle.kts` 阿里云、`gradle-wrapper.properties` 华为云），  
   首次Sync 慢是正常的，不要因为进度条停住就改地址。

---

## 1. 安装与打开项目

|   |   |
| - | - |

1. 装 **Android Studio Meerkat | 2024.3.1 或更新**稳定版（compileSdk 36 / Android 16 要求；  
   更老版本不认识 API 36）。官网：`developer.android.com/studio`。
2. 首次启动向导一路默认即可（SDK 路径如需改，记下位置，§3 要用）。
3. `File → Open…` → 选项目根目录 `ZZUPocket-main`（含 `settings.gradle.kts` 的那一层）→ OK。
4. 弹出 **Trust this project?** 选 **Trust**（不信任 Gradle 不跑）。
5. Android Studio 会自动开始 Gradle Sync；等底部进度条走完、不再转圈。

> 命令行环境说明：本机命令行没有配置 `JAVA_HOME`/`ANDROID_HOME`，也没有 `local.properties`，  
> 所以**不要在外部终端跑 `./gradlew`**，一定在 Android Studio 里操作（它自带并管理这些）。  
> Sync 成功后 Android Studio 会自动生成 `local.properties`，不用手动建。

## 2. 把 Gradle JDK 设为 21（关键，别跳过）

1. `File → Settings…`（macOS 是 `Android Studio → Preferences…`）
2. `Build, Execution, Deployment → Build Tools → Gradle`
3. **Gradle JDK** 下拉框选 **JDK 21**（如 `jbr-21` 或 `OpenJDK 21`）。
   - 列表里没有？点下拉框里的 **Download JDK…** → 版本选 21 → 厂商随便（Oracle/OpenJDK 都行）→ 下载。
4. **Apply → OK**，然后再点一次工具栏的 **Sync Project with Gradle Files**（大象图标）让设置生效。

> 备选（不改 IDE 设置）：在本机 `~/.gradle/gradle.properties`（不是项目里的那个）追加一行  
> `org.gradle.java.home=C:/你的路径/jdk-21`。**不要写进仓库的 `gradle.properties`**——那是机器相关路径，  
> 会污染其他人的环境。

## 3. 安装 Android 16 SDK

1. `Tools → SDK Manager`（或工具栏 SDK 管理器图标）。
2. **SDK Platforms** 标签 → 勾选 **Android 16 (API 36)**（列表里可能显示为 Baklava）。
3. **SDK Tools** 标签 → 勾选 **Android SDK Build-Tools 36.x**（有就勾最新版）。
4. **Apply → OK**，同意许可协议，等下载安装完成。

> 只需要 36 用来编译；运行设备可以是更低版本（见 §4，`minSdk = 31`）。

## 4. 准备运行设备（真机或模拟器二选一）

**真机（推荐）**

1. 手机：设置 → 关于手机 → 连点「版本号」7 次打开开发者选项。
2. 开发者选项里打开 **USB 调试**；有的机型还要开「USB 调试（安全设置）」并允许通过 USB 安装应用。
3. USB 线连电脑，弹出的「允许 USB 调试」点确定。
4. Android Studio 顶部设备下拉框应出现你的机型。

**模拟器**

1. `Tools → Device Manager → Create Device` → 选一个手机（如 Pixel 7）→ Next。
2. 系统镜像选 **API 36**（或任意 ≥31 且已下载的）→ 没有就点 Download → 完成向导。
3. 启动模拟器（Device Manager 里的 ▶）。

## 5. 跑单元测试（不需要设备、不联网）

60+ 个单测，本轮 HPU 相关的是 `HpuCasClientTest`、`HpuScheduleParserTest`、`HpuPeriodsTest`，  
以及基于两个 HTML 样本的解析测试。

- **图形方式**：右侧 **Gradle** 面板 → `app` → `tasks` → `verification` → 双击 **test**。
- **命令行方式**：只能在 Android Studio 内置 **Terminal** 里跑（外部终端缺环境会失败）：


  ```
  gradlew.bat test
  ```
- **看结果**：底部 **Build** 窗口；详细报告在  
  `app/build/reports/tests/testDebugUnitTest/index.html`（浏览器打开，逐条看红/绿）。

> 单测全绿说明纯逻辑层没问题；任何红了先把堆栈发我，别急着往下走。

## 6. 把 App 装到设备上

1. 顶部设备下拉框选中你的真机/模拟器。
2. 点工具栏 **Run 'app'**（绿色 ▶，快捷键 **Shift+F10**）。
3. 等编译 → 安装 → 自动启动。装的是 `com.hpupocket.app`，与郑大版不同包名，**可同机共存**。
4. 首次启动会申请通知权限（番茄钟用），按需给；拒绝也不影响课表和登录。

## 7. 试运行验证清单（按顺序勾）

- [ ] **应用图标**：桌面上是猫咪图标（浅蓝底），不是旧版双色块 —— 验证资源替换生效。
- [ ] **关于页校徽**：更多 → 关于，顶部显示校徽（512×512 去白底图）。
- [ ] **登录**：更多 → 登录 → 走 UIA/CAS 网页 → 登录成功后跳到 `zhjw.hpu.edu.cn/eams/`，  
  App 自动收集 Cookie 并返回，**不报错、不卡在浏览器**。
- [ ] **课表**：登录后进课表，能看到学期、切换教学周、课程格子有数据。
- [ ] **未适配模块留空**：教室占用 / 图书馆 / 电费 / 校园卡入口**整组不显示**（被  
  `SchoolConfig.Integration` 守卫隐藏）。看到这些入口能点进去说明配置没生效，回来告诉我。
- [ ] **研究生入口关闭**：不应有研究生模式入口。

## 8. 失败时导出日志（崩溃/异常都靠它）

App 装了全局崩溃捕获，日志脱敏后写到私有目录。任何一步崩了或表现异常：

1. 进 App → **更多 → 诊断 → 运行日志** 卡片。
2. 点 **「导出日志（分享给开发者）」**。
3. 选微信/邮件等把文件发出去（令牌、手机号已自动打码，只有你主动分享时才发出）。
4. 把收到的文件发我，我按堆栈定位。

> 打不开 App（启动即崩）怎么办：用 `adb logcat` 抓，或告诉我崩溃发生在哪一步，  
> 我会给针对性的抓法。

## 9. 常见问题

| 现象                                        | 处理                                              |
| ----------------------------------------- | ----------------------------------------------- |
| `Unsupported class file major version`    | Gradle JDK 不是 21，回 §2 重设                        |
| `compileSdk is not yet supported (36)` 警告 | 正常，`gradle.properties` 已 suppress；若报错而非警告，把原文发我 |
| Sync 卡住 / 依赖下载超时                          | 国内镜像已配，等；仍失败把 Build 窗口的报错原文发我，不要改镜像地址           |
| 设备下拉框看不到手机                                | 重插 USB、确认 USB 调试已开、弹窗点了允许；或改用模拟器                |
| 安装失败提示签名冲突                                | 同包名的旧调试版卸载重装（本版是 `com.hpupocket.app`，一般不会有）     |
| 登录页白屏 / 一直转圈                              | 网络能访问 `zhjw.hpu.edu.cn` 吗？导出日志发我                |

---

## 需要你反馈的结果

跑完把这几条告诉我，我据此判断下一步：

1. 单测是否全绿（红了发堆栈）；
2. Sync / 编译是否一次过（报错发原文）；
3. §7 清单哪几项打勾、哪几项没过（附现象或日志）。
