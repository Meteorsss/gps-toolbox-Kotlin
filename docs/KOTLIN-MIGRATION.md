# Kotlin 迁移说明

## 精确基线

- 原仓库：Meteorsss/gps-toolbox（公开）。
- main：be9c60289fac7e07329784c6680db0494ca13e09。
- PR #1：feature/openfreemap-track-insights，最新 defe34f40e192fb8ceb1b2c28cdf16a0cc4f0855。
- 本仓库合并提交：9ff496f；未修改原仓库 main、PR 或已有 Release。
- 不遗漏 PR 中地图、统计、KML 导出、起终点、记录间隔、主题与独立测速的后续提交。

## 迁移方法与不变约束

使用 JetBrains IntelliJ 263.8311.30 内置 J2K 做语法树转换，再人工修正 Kotlin 空值类型、嵌套可见性、SAM 回调标签、Android override 签名等语言差异。不使用其他在线 AI API，不改变算法、不引入协程、不替换 UI 框架。

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

最终运行结果和截图对比将在验证完成后记录于此。
