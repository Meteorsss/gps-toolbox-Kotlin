# 将星迹轨迹导入 Google Earth / Google 我的地图

## 导出

1. 结束并保存轨迹，进入“记录”，打开该轨迹的操作菜单或详情页。
2. 选择“导出 KML（Google Earth / 我的地图）”。
3. 在系统文件保存器中选择位置，保存 `.kml` 文件。文件包含精确位置，请勿公开分享真实轨迹。

KML 使用 WGS84，经度在前、纬度在后，UTF-8 与标准 KML 2.2 命名空间。每个连续片段分别保存，不连接暂停、无效/模拟点、缺失超过 30 秒或日期变更线两侧的点。单点段为地标；多点段为线条。0.2.2-pr 起额外导出首/末有效点的“起点”和“终点”地标；仅一个有效点时使用“起点 / 终点”。没有有效点时不造地标。旧版文件需要重新导出，Earth 不会替它自动添加端点。为兼容普通地图与地球，轨迹贴地显示，不把 GNSS 椭球高伪装为海拔。逐点时间/原始测量数据请另导出 GPX / CSV。空有效轨迹仍生成合法空文档，但没有可显示的地标。

## Google Earth

- Android 版可在首页“本地（Local）”中，选择“新建”→“本地 KML 编辑器”→“从设备打开 KML/Z”；也可在项目中选择“导入文件”，上传设备上的 `.kml`。
- 网页版 / 桌面版可使用项目导入或打开 KML 文件功能；具体名称可能随版本变化，以官方导入文档为准。
- 放大检查轨迹位置、起止范围、各断点和单点地标。长文件也受接收端功能/顶点/文件大小限制，不能承诺无限导入。

## Google 地图 / 我的地图（My Maps）

普通 Google Maps App 不是本次 KML 文件导入入口。需要：
1. 在电脑浏览器打开 <https://www.google.com/mymaps> 并登录 Google 账号。
2. 创建或打开地图，点击“添加图层”→该图层中的“导入”。
3. 上传 `.kml` 文件，核对显示结果；之后可通过同一账号在 Google 地图支持的“我的地图”入口查看，具体客户端入口由 Google 决定。

My Maps 官方限制：解压后的 KML / KMZ 不超过 **5 MB**；一次导入的地图项等也受平台限制。星迹不为满足限制而静默抽样或丢弃轨迹点，大文件建议用 Google Earth 或按记录分开导出。本应用不直接调用 Google API、不连接 Google 账号，也不自动把轨迹上传 Google。

## 官方资料（核对日期：2026-11-21）

- [Google KML Reference](https://developers.google.com/kml/documentation/kmlreference)：KML 2.2 命名空间、Point / LineString / coordinates / altitudeMode。
- [Google 我的地图：从文件导入地图项](https://support.google.com/mymaps/answer/3024836?hl=zh-Hans)：支持 KML 与 5 MB 限制。
- [Google Earth：导入数据与打开本地 KML](https://support.google.com/earth/answer/7365595)：各平台项目导入和 Android 本地 KML 入口。

云端测试会验证 XML 结构、坐标顺序、分段、文件保存流程和原始数据不变；不等同于已在用户 Google 账号或红米 K70 真机中完成实际导入。
