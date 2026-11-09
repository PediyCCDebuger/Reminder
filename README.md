# AddinTimer 复刻版（基于 Reminder fork）

本分支把 `lentikr/Reminder` 改造成 **AddinTimer 2.1G 定时部分的复刻**：保留上游的 Material 3 Expressive 主题与工程骨架，把主界面换成定时器列表 + 原版两页属性编辑器，并新增 `.atk` 备份导入导出、精确闹钟调度与通知。

原版 AddinTimer 2015 年停更，只声明了 `minSdkVersion=4`、没有 `targetSdkVersion`，Android 14+ 直接拒绝安装；菜单依赖硬件 MENU 键；也不会申请 Android 12 引入的 `SCHEDULE_EXACT_ALARM`。这个复刻版按当前系统重新实现了调度层，数据格式则与原版完全一致。

## 和原版一致的界面

每条定时器点「编辑设置」进入两页属性：

**时间设置**
- **常规设置**：任务名称、开始时间（时/分/秒）、循环规则（六种模式）、任务类型、激活开关
- **执行策略 · 具体设置**（随模式切换）：
  - 按周循环 → 选择星期
  - 指定日期 → 年/月/日
  - 按天循环 → 每几天、开始日期
  - 按年月循环 → 选择月份、月内循环（按天计算 / 指周计算）、选择天数或出现顺序 + 选择星期、排除几号、排除星期几
  - 按阴历循环 → 选择阴历月（正月…腊月）、选择阴历天（初一…三十/最后一天）、包含闰月
  - 倒计时 → 从现在开始、开始日期、倒计时分钟
  - 结束日期、排除几号、排除星期几
- **时循环设置**：时循环（不启用/启用）、每几分钟、结束时间（时/分）
- **其他设置**：执行前通知、执行后动作、桌面小部件
- 底部实时显示规则描述与「下次触发」时间

**参数设置**（以「定时提醒」为例，27 项逐项对应原版 `GSW/AddinTimer/ad`）
- **通知设置**：播报时间、播报位置、声音模式、延迟时间、间隔时间、铃声类型、选择路径、重复次数、音量大小、渐响模式、强制外放、振动类型、播报内容（多选）
- **贪睡设置**：预设时间、自动贪睡、手动贪睡
- **界面设置**：屏幕模式、终止确认、光线感应器、翻转感应、算术题解锁、暂停方式、电源键按下、界面样式
- **其他设置**：提醒备注、内部计数/时间戳

所有下拉选项的中文文案、顺序与数量均取自原版 `resources.arsc` 的 77 个 array 资源，不是凭印象写的。

## 数据格式（100% 兼容 `.atk`）

`.atk` 就是 SQLite 数据库原样拷贝，核心表 `timer(id, param)`。本工程直接沿用这个表结构：

```
<KIND>|<14 个基础字段，用 $ 分隔>|<动作参数，用 | 分隔>
```

- 基础字段第 5 项是 `ko.a()` 生成的 **28 项 `#` 分隔规则串**；第 2/3/4 项是名称/时/分；第 10 项是激活标志；第 8/11/13 项分别是执行前通知/桌面小部件/执行后动作。
- `ALARM` 有 **27 个动作参数**，顺序来自 `ad.a(String)` 反汇编；例如 `[0]` 提醒备注、`[7]` 铃声路径、`[23]` 播报内容多选、`[24]` 手动贪睡。
- 六种模式编号：`0=按周 1=指定日期 2=按天 3=按年月 4=按阴历 5=倒计时`。月份/日期**列表是 0-based**，DatePicker 的年月日字段是 1-based。
- 农历表 1900–2099 内置离线数据，与 `lunardate` 交叉验证 398 个样本 0 差异。

已用 `20261109182226.atk`（18 条：ALARM 14 / FM 2 / SCENE 2）做无损往返验证：**18 条 0 差异**。

## 使用

1. 安装后打开 App → 点「**权限自检**」，逐项开启：精确闹钟（闹钟和提醒）、通知权限、忽略电池优化、全屏通知。另外到系统「应用管理」开启「允许自动启动 / 允许后台运行」，一加 ColorOS 上这一步是漏响的头号原因。
2. 点「**导入 .atk**」选备份文件 → 选「替换导入」或「追加」。
3. 核对列表里每条的「下次」时间；原版普通版只让同时激活 5 条，导入后可用开关逐条开启。
4. 不想被「进入飞行 / 情景模式」等任务类打扰，点「**停用任务类**」（只改激活标志，数据保留）。
5. 「**导出**」会写出一份标准 `.atk`，原版 AddinTimer 也能打开。

调度用 `AlarmManager.setAlarmClock()` + 高优先级通知渠道 + 全屏提醒页 + 贪睡/终止操作，开机、时间变更、应用更新后自动重排。

## 与原版的差异（如实说明）

- 只做**提醒**。FM / SCENE 等任务类在原版会切换飞行模式、音量等，本版只弹提醒，参数按原样读写以免数据丢失。
- 原版的 TTS 语音播报、ROOT 操作、状态栏图标、桌面小部件、算术题解锁等交互未复刻；设置项保留并可编辑，但只有与 Android 当前权限模型兼容的部分会实际生效。
- 原版普通版 5 个 / 增强版 10 个激活数量限制**已移除**。
- 原版铃声是绝对路径，Android 11+ 分区存储下不可读，请在「选择路径」里重新选一次文件。
- 模式 5「倒计时」按目标日期一次性提醒实现，原版的执行前等待倒计时未复刻。

## 构建与 Release

```bash
./gradlew assembleRelease
```

`app/build.gradle.kts` 配置了 ABI splits，会产出三个 APK：

| 文件 | 架构 |
|---|---|
| `AddinTimerRevive-<版本>-arm64-v8a.apk` | 64 位 ARM（一加 13 用这个） |
| `AddinTimerRevive-<版本>-armeabi-v7a.apk` | 32 位 ARM |
| `AddinTimerRevive-<版本>-universal.apk` | 通用（模拟器 / x86） |

推送 `v*` tag 或在 Actions 里手动运行 **Release** 工作流即自动构建、验签、发布 Release 并生成 `SHA256SUMS`。仓库没有配置 `REMINDER_KEYSTORE_BASE64` 等 secrets 时，工作流会自己生成一份签名密钥，保证产出的是**已签名** APK；如需固定签名，配置这几个 secrets 即可覆盖。

上游 Reminder 的纪念日代码仍保留在仓库中，主入口已切到定时器界面，两套代码互不干扰。

---

## 上游项目说明：Reminder

Reminder 是一款安卓纪念日应用，记录倒数日和正数日，支持农历、重复周期、分组置顶，并能把纪念日生成为图片分享。

<p align="center">
  <img src="docs/screenshots/home.jpg" alt="主界面" width="22%" />
  <img src="docs/screenshots/edit.jpg" alt="编辑" width="22%" />
  <img src="docs/screenshots/setting.jpg" alt="设置" width="22%" />
  <img src="docs/screenshots/share.jpg" alt="分享" width="22%" />
</p>

## 功能

- **倒数日与正数日**：倒数日显示“还有 N 天”，过期的一次性日期显示“已过”；正数日从开始当天算第 1 天。
- **农历**：可以直接用农历年/月/日滚轮选择日期，包括闰月。按农历重复时，闰月在没有该闰月的年份落到同名月份，三十在小月落到廿九。
- **重复周期**：每 N 天、周、月、年。下一次日期总是从原始日期推算，不会因为经过小月而漂移（例如 1 月 31 日按月重复，3 月仍是 31 日）。
- **分组与置顶**：置顶的纪念日排在最前，其余按分类分组；组内按最近的日期排序。
- **多选**：长按进入多选，可批量置顶或删除，删除后可在提示条中撤销。
- **分享与保存图片**：分享图沿用 1.x 的台历页样式，有木纹、花园、夜空三种背景，导出 1440×1920 的 PNG。保存到相册不需要存储权限。
- **外观**：跟随系统 / 浅色 / 深色；深色下可开纯黑背景；Android 12 以上可用壁纸动态取色；卡片和列表两种视图。
- **备份与恢复**：导出为带版本号的 JSON 文件，也能导入 1.x 导出的备份。
- **大屏**：平板和横屏下列表与详情并排显示。

## 技术选型

| 方面     | 选择                                                                  | 说明                                                                                                                       |
| -------- | --------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| UI       | Jetpack Compose + Material 3 Expressive（`material3` 1.5.0-beta02）   | ButtonGroup、FloatingToolbar、SegmentedListItem、形状变形和 expressive 动效都只在 1.5 系列里，所以单独固定了版本，领先 BOM |
| 导航     | Navigation 3 + `material3-adaptive` 的 `ListDetailSceneStrategy`      | 返回栈由应用持有；同一套代码在手机上单栏、大屏上双栏                                                                       |
| 依赖注入 | Hilt，带参数的 ViewModel 用 assisted injection                        |                                                                                                                            |
| 存储     | Room 3 存纪念日，DataStore 存设置                                     | 数据库导出 schema，便于以后写迁移                                                                                          |
| 农历     | [tyme4kt](https://github.com/6tail/tyme4kt)                           | 只在 `domain/LunarCalendar.kt` 里使用，其他代码不直接依赖它                                                                |
| 序列化   | kotlinx.serialization                                                 | 备份文件和编辑草稿                                                                                                         |
| 文件     | 备份用存储访问框架（SAF），保存图片用 MediaStore，分享用 FileProvider | 全程不申请存储权限                                                                                                         |
| 系统集成 | Edge-to-edge、预测性返回、SplashScreen API                            | 启动页等设置读取完成才消失，避免主题闪烁                                                                                   |

最低支持 Android 10（API 29），目标 API 37。

## 构建

需要 JDK 17 和 Android SDK 37。

```bash
./gradlew assembleDebug        # 调试版
./gradlew testDebugUnitTest    # 单元测试
./gradlew assembleRelease      # 发布版（开启 R8 压缩和资源压缩）
```

包名是 `app.lentikr.reminder`，调试版会加 `.debug` 后缀，两者可以装在同一台设备上。每种构建都输出两个 APK，位于 `app/build/outputs/apk/<debug|release>/`：

- `app-arm64-v8a-*.apk`：只含 arm64 原生库，适用于现在绝大多数手机
- `app-universal-*.apk`：包含全部架构，适用于模拟器、x86 设备和旧的 32 位手机

发布签名从项目根目录的 `keystore.properties` 读取，CI 上也可以用环境变量。这个文件和 `release.jks` 都已加入 `.gitignore`，不要提交。请另外备份 `release.jks` 和密码：丢失后无法再发布能覆盖安装的更新。

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

对应的环境变量是 `REMINDER_STORE_FILE`、`REMINDER_STORE_PASSWORD`、`REMINDER_KEY_ALIAS`、`REMINDER_KEY_PASSWORD`。没有配置签名时，`assembleRelease` 输出未签名的 APK。

## 自动构建与发布

- `.github/workflows/ci.yml`：推送到 `main` 和每个 Pull Request 都会跑单元测试、lint 和调试版构建。
- `.github/workflows/release.yml`：推送 `v*` 标签时构建签名的发布版，创建 GitHub Release 并附上两个 APK 和 `SHA256SUMS`。标签必须和 `app/build.gradle.kts` 里的 `versionName` 一致，带 `-` 的标签（如 `v2.1.0-beta1`）会标成预发布。

发布工作流需要在仓库的 Settings → Secrets and variables → Actions 里配置三个 secret：

| Secret                     | 内容                         |
| -------------------------- | ---------------------------- |
| `REMINDER_KEYSTORE_BASE64` | `release.jks` 的 base64 编码 |
| `REMINDER_STORE_PASSWORD`  | keystore 密码                |
| `REMINDER_KEY_PASSWORD`    | 密钥密码                     |

## 测试

单元测试覆盖最容易出错的逻辑：

- `DayCounterTest`：天数计算、各种重复周期、月末与闰日、农历重复中的三十和闰月
- `LunarCalendarTest`：已知节日的公农历换算、全年往返换算、闰月和月份天数
- `BackupCodecTest`：备份往返、未知字段、新版本文件、损坏文件、1.x 格式导入
- `BuildSectionsTest`：置顶、分类分组和排序
- `CaptionTest`：卡片和列表下方的日期说明，包括农历、闰月和跨年的腊月
- `ReadAtMostTest`：恢复备份时的文件大小上限

## 项目结构

```
app/src/main/kotlin/com/lentikr/reminder/
├── domain/      纪念日模型、天数计算、农历封装（纯 Kotlin，可单测）
├── data/        Room、DataStore、备份、图片导出
├── di/          Hilt 模块
└── ui/
    ├── home/        列表、卡片、多选
    ├── detail/      详情、分享图
    ├── edit/        新建/编辑、日期和重复选择器
    ├── settings/    设置
    ├── navigation/  Navigation 3 路由
    ├── common/      台历卡片等共用组件
    └── theme/       配色、动态取色、纯黑主题
```
