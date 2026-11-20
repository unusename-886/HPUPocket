# 第三方声明与来源说明

本项目 **HPUPocket**（河南理工大学非官方校园助手）是对开源项目的**适配实现**，
不是原项目的官方分支，也不代表原作者参与、认可或背书。

---

## 一、ZZUPocket（上游项目，本仓库代码的主要来源）

| 项 | 内容 |
| --- | --- |
| 项目 | <https://github.com/3beuml/ZZUPocket> |
| 作者 | **3beuml** |
| 许可 | MIT |
| 与本项目的关系 | 本仓库在其代码基础上做河南理工适配 |

**复用范围**：应用整体架构与绝大部分业务代码——课表、今天、专注（番茄钟）、电费、
校园卡、图书馆、桌面小组件、主题与导航；以及部分构建配置与 Gradle Wrapper。

**本项目的改动**：应用名 / 包名（`com.hpupocket.app`）/ 配色 / 文案切换为河南理工版本；
教务、电费、图书馆、空闲教室等学校接口按河南理工重新对接与实现；新增第 11 节课修复、
全应用字号缩放、清除缓存、启动图标调色盘、周末延长等功能。

上游以 MIT 许可开放，MIT 要求保留的版权与许可声明如下：

```
MIT License

Copyright (c) 2026 3beuml

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 二、其他参考项目（仅参考协议形状与设计，未拷贝源代码）

| 项目 | 许可 | 参考内容 |
| --- | --- | --- |
| [Illustar0/ZZU.Py](https://github.com/Illustar0/ZZU.Py) | MIT | 郑州大学 CAS / 教务接口的协议形状（上游已实现，本项目仅作对照） |
| [Elykia093/ZZU-Electricity-Monitor](https://github.com/Elykia093/ZZU-Electricity-Monitor) | MIT | 宿舍房间表（`assets/rooms.json` 由其精简而来）、采样节奏与低电量阈值 |
| [rimrose/ZZU-Dorm-Electricity-Balance-Base](https://github.com/rimrose/ZZU-Dorm-Electricity-Balance-Base) | MIT | 宿舍用电记录 |
| [ShiGuangSchedule/shiguangschedule](https://github.com/ShiGuangSchedule/shiguangschedule) | Apache-2.0 | 桌面小组件的版式思路（仅设计参考） |

---

## 三、构建工具与运行时依赖

以下依赖均通过 Gradle 以二进制形式引入，未修改、未内联其源码：

| 组件 | 许可 |
| --- | --- |
| Gradle Wrapper | Apache License 2.0 |
| Kotlin / Kotlin Coroutines | Apache License 2.0 |
| AndroidX / Jetpack Compose / Material 3 | Apache License 2.0 |
| OkHttp | Apache License 2.0 |
| kotlinx.serialization | Apache License 2.0 |
| Room / DataStore / WorkManager | Apache License 2.0 |
| Tink（令牌加密） | Apache License 2.0 |

Apache License 2.0 全文：<https://www.apache.org/licenses/LICENSE-2.0>

---

## 四、学校名称、校徽与商标

- 「河南理工大学」名称与校徽（`res/drawable-nodpi/ic_school_badge.png`）版权归**河南理工大学**所有，
  本项目**非官方**，仅出于识别与说明目的在「关于」页展示，不主张任何权利，也不暗示学校认可或背书。
- 启动器图标（猫咪图）由项目维护者提供，仅用于本项目。
- 若校方认为使用不当，联系后即会移除。

---

## 五、凭据与数据说明

本仓库**不包含**任何真实账号、密码、Cookie、令牌、学号、手机号，或某位具体住户的用电实测值。

- 接口文档中的服务器地址与实测数值已做脱敏（IP 显示为 `***`，电量显示为 `XXX.XX`）；
- 抓包原始文件（`.har`、cookie 文件）与模拟器截图**不入库**，也不随源码分发；
- 教务登录密码只在学校官方页面填写，不被原生代码读取或存储。
