# Kotlin 迁移说明

## 精确基线

- 原仓库：Meteorsss/gps-toolbox（公开）。
- main：be9c60289fac7e07329784c6680db0494ca13e09。
- PR #1：feature/openfreemap-track-insights，最新 defe34f40e192fb8ceb1b2c28cdf16a0cc4f0855。
- 本仓库合并提交：9ff496f；未修改原仓库 main、PR 或已有 Release。
- 不遗漏 PR 中地图、统计、KML 导出、起终点、记录间隔、主题与独立测速的后续提交。

## 迁移方法与不变约束

一次性转换工作流已停用，仅保留方法记录，日常构建不下载 IDE。使用 JetBrains IntelliJ 263.8311.30 内置 J2K 做语法树转换，再人工修正 Kotlin 空值类型、嵌套可见性、SAM 回调标签、Android override 签名等语言差异。不使用其他在线 AI API，不改变算法、不引入协程、不替换 UI 框架。

27 个应用 Kotlin 文件，原有 12 个 JVM 测试类和 5 个 Android 测试类全部迁移。额外增加 MigrationParityTest，与 git 历史中的精确 Java 基线进行差分测试；基线 Java 仅在测试临时目录编译，**不打包进 APK**。

保持：

- 原 View/Canvas、Choreographer 指南针、原线程/Handler/执行器及 GNSS 订阅策略。
- 包名、组件名、权限、数据库名称和 v2 表结构、v1→v2 增量升级、SharedPreferences 键。
- 中文 UI 文案、颜色、尺寸、图标、主题资源、隐私提示与地图署名。
- MapLibre 13.5.4、地图 URL、统计/过滤口径、导出 MIME/扩展名/XML 命名空间。
- release 不混淆与 ARM64 配置；未以换签名方式假冒兼容升级。

## 自动验证

`python scripts/verify-migration.py` 检查零 Java 源文件、Manifest/res/assets 对基线无修改，以及原中文界面字符串全部保留。

`gradle --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest` 执行编译、单测和 Lint。Actions checkout 必须 fetch-depth=0，以便差分测试读取保留的历史提交。

新增 Java/Kotlin 差分覆盖：10000 组随机坐标/方位/格式化/GCJ02、5000 个含断点/拒绝点的过滤统计和测速会话、1000 点 GPX/KML 逐字节导出对照、10000 帧指南针/采样间隔。浮点结果按 IEEE-754 位比较（NaN 规范化），不是只比较肉眼近似结果。

Android 16 / Pixel 6 x86_64 模拟器继续运行原有 23 项测试，包括深浅主题、数据库迁移、前台记录、卫星多频、地图离线与联网、KML 导出、主题跟随、测速生命周期、指南针帧回调。

## 签名与升级

本仓库未配置原 SIGNING_BUNDLE；调试 APK 不能覆盖原发布签名 APK。请保留旧版和本地记录，不要为测试卸载。只有把同一份原签名安全配置到新仓库后，才可手动构建并验证正式覆盖升级。原密钥不会被替换、打印或提交。

## 验证边界

资源一致、算法对照和模拟器测试并不等于所有真机性能完全相同。未声称完成红米 K70 实机、澎湃 OS 熄屏记录、电池消耗、GNSS 实际精度和所有网络条件的验收。Kotlin 引入编译器生成代码和标准库，APK 体积与二进制不可能逐字节相同。签名迁移前也未声称完成旧发布 APK 的覆盖安装测试。


## 已完成的验证（2026-11-21）

验证代码提交：`462cf5ada0027bbcc23285d579f3f5b7ee3c2ff4`。

完整成功构建：[Android APK #11 / 44890987584](https://github.com/Meteorsss/gps-toolbox-Kotlin/actions/runs/44890987584)。

| 检查 | 结果 |
|---|---|
| 原 107 项单元测试 + 4 项 Java/Kotlin 差分 | 111 通过，0 失败/跳过 |
| Android 16 模拟器 | 23 通过，0 失败 |
| Debug APK / androidTest APK | 编译成功 |
| Release Lint | 0 errors、70 warnings、8 hints |
| 应用源码 | 27 个 Kotlin 文件，0 Java |
| Manifest、res、assets | 对 Java 基线保持不变 |
| 原中文 UI 字符串 | 全部保留 |
| 打包后权限列表 | 与原 Java 构建逐字节一致 |
| MapLibre 等原生库 | 全部逐字节一致 |
| 截图 | 19 对，排除顶部系统状态栏后 11 对逐像素一致 |

Lint 警告未被隐藏：包含保留旧目标 SDK/依赖版本、纯 ARM64 release、程序式 View 构造以及字符串拼接等；本次为了避免改变用户行为，不同时升级 Android/地图依赖或重构布局。

### 截图审查

完全一致的 11 对包括深色四个主页、深色居中速度卡、定位诊断、在线地图、收藏空列表、卫星多频示例、大字测速、三主题设置。浅色主页、浅色居中速度卡、独立测速仅有 8–17 个像素、最大单通道差 1。离线地图最大单通道差 4；指南针差异集中于绘制边缘。收藏编辑截图的差异为临时 Toast 显示时机，GCJ02 仪表的差异包含首次定位耗时，设置页为滚动条显示时机。没有声称 19 对截图全部像素一致。

### 安装包体积

同为原模拟器调试构建：Java 57,669,484 bytes；Kotlin 57,817,244 bytes，增加 147,760 bytes（约 0.256%）。原生库总大小同为 48,865,476 bytes。此对比不是 ARM64 发布包体积，也不能当作 CPU/耗电性能基准。

详细机器可读结果位于 `docs/verification/`，原始 XML、Lint、截图、合成导出样例保存在上面的 Actions 产物，不上传 GitHub Release。

### 未完成、不能冒充完成的事项

- 原发布签名尚未安全配置到新仓库，因此未生成可覆盖旧发布版的签名 APK，也未进行真实旧版覆盖升级测试。
- 未连接红米 K70 真机；熄屏长时记录、电池/内存/启动耗时、真实 GNSS 和全部网络条件尚需设备验收。
- 未承诺二进制体积完全一致或所有设备性能绝对一致。现有算法、线程模型、传感器订阅和界面行为测试未发现回归。

## GitHub 首页语言统计核查（2026-11-21）

用户反馈首页仍显示 Java 98%。直接核对 GitHub 远端默认分支 `main` 的完整 Git tree（`c88379055a75d0872748d64b6946f960440ae798`，结果未截断）后确认：

- 当前远端有 45 个 `.kt` 文件，共 332,756 bytes；其中应用 27 个、JVM 测试 13 个、Android 测试 5 个。
- 当前远端 `.java` 文件为 0；没有 `.gitattributes` 语言覆盖规则。
- GitHub `/languages` 仍返回 Java 275,857 bytes、Python 4,695 bytes、Shell 750 bytes。
- 上述三个值与首次推送的、迁移前基线 `6df56b8` 完全一致；当前 Python 文件实际已为 6,376 bytes。说明该语言统计尚未反映后续提交，而不是 Kotlin 源码被 Java 文件占比压过。

GitHub 官方说明：语言统计由 Linguist 生成，并在向默认分支推送后更新；结果存在缓存。本次推送核查记录以尝试触发重新计算，不修改语言识别标签、不删除历史、不把 Java 标记成 Kotlin。不保证 GitHub 后台立即刷新，提交本说明不代表首页已经更新。

官方依据：
- https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-repository-languages
- https://github.com/github-linguist/linguist/blob/main/docs/troubleshooting.md

原始远端核查结果见 `docs/verification/github-language-audit.json`。本次仅添加说明与核查数据，不改变应用及构建代码。
