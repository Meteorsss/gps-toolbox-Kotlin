# 0.2.4 定位连续性修复与后台权限

## 问题与修复

- 旧版 MainActivity/MapActivity.onPause 对共享 GnssEngine.visible 写 false，onResume 写 true；页面切换即重新订阅并清空位置。
- 本版由 Application.ActivityLifecycleCallbacks 统一管理 STARTED Activity 身份集合，最后一个页面停止后等待 1500ms 交接；新页面启动取消停止请求。指南针仍在页面暂停时停止高频传感器，不将持续 GNSS 与指南针混为一谈。
- GnssEngine 的需求条件为 `(应用可见 || 正在记录 || 用户开启持续定位) && 精确位置权限`，三个使用场景独立。真正重新订阅保留上次位置，fresh()/age() 保持原15秒有效期。
- 新增 LocationHoldService：用户明确开启、前台启动、location 类型、常驻通知与停止按钮、stopWithTask=false、START_NOT_STICKY。只维持实时位置，不写数据库；重启/强制停止后不自启；不请求忽略省电白名单。
- 新增 ACCESS_BACKGROUND_LOCATION 声明与分步授权说明；前台精确位置和后台权限不一起请求。后台权限在系统设置明确选择，可拒绝；它不是持续定位服务本身，不会在授权后自动记录位置。

## 系统边界

Android 11+ 的“始终允许”需在设置页选择。定位前台服务应在 Activity 可见时启动，只有后台权限本身也不能绕过所有后台启动限制。

依据：
- https://developer.android.com/develop/sensors-and-location/location/permissions/background
- https://developer.android.com/develop/background-work/services/fgs/service-types#location

不保证系统杀进程、用户强制停止、厂商省电、权限撤销或系统定位关闭时继续工作。已有数据库断点/中断恢复逻辑与定位真实性检查不变。

## 回归验收（Actions 中运行）

新增 LocationContinuityTest 的8项仪器测试：
1. 仪表打开地图再返回：GNSS注册次数不增长，原位置时间戳不被清空。
2. Activity重建：相同定位会话和缓存位置继续使用。
3. 真正停止再启动：保留旧位置，但过期位置仍不可当作新位置。
4. 实际Home退到桌面再打开：持续定位订阅保持，不创建任何轨迹，指南针传感器后台停止。
5. 未开启持续定位时，真正的TrackService后台继续接受合成位置、写入同一条轨迹。
6. 停止持续定位不停止轨迹；停止轨迹不停止已开启的持续定位。
7. 从常驻通知停止后台定位，返回应用后不会擅自再开启。
8. Manifest包含后台权限；提供可拒绝说明与正确系统设置入口，不静默授予。

全部位置是合成测试输入；不上传用户位置。不声称已完成红米K70长时间熄屏或耗电真机验收。

发布门禁：原111项JVM测试（含4项Java/Kotlin差分）+31项Android16模拟器测试，Lint无错误，原固定发布签名、纯ARM64、APK哈希与16KB ZIP对齐。
