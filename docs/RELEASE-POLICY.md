# Kotlin Release 附件与签名规范

## 2026-11-21 所有者授权

所有者明确要求通过 GitHub Actions 编译安装包并发布 Release；允许使用与 Java 原版不一致的新签名，愿意卸载原版且无需保留数据。

- Kotlin 新签名已保存为本仓库 `SIGNING_BUNDLE`，后续发布必须固定使用，不得反复生成。
- 证书 SHA-256：`d5aa7ea6d604420beba5909e4b6a84b152c79e44d2348fa15f1f0f09267de95a`。
- 原 Java 版无法被此 APK 直接覆盖；首次安装须卸载原版，卸载会删除数据。
- 原 Java 仓库、PR 和 Release 不修改。

## Release 只能上传两个附件

1. `StarTrace.apk`：固定 Kotlin 发布签名、不可调试、仅 `arm64-v8a`，验证签名、哈希、ELF 架构和 16KB ZIP 对齐。
2. `README.md`：对应构建提交的安装与使用说明。

测试报告、Lint、权限/签名/ABI 报告、JSON、合成 KML、截图不上传 Release，保存在 Actions。GitHub 自动附带的 Source code 压缩包不计为上传附件。

只有受信任 main 的 `workflow_dispatch` 构建可读取签名。发布工作流检查同一构建的单元测试、Android 16 模拟器、Lint 和签名验证，再先创建 draft，确认附件及哈希后公开为 Pre-release。真机长期后台/功耗尚未验收，不标为已完成真机验收。
