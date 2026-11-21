# Release 附件规范

所有者于 2026-11-21 明确要求，从 0.2.3-pr 开始，每次 GitHub Release 只上传：

1. `StarTrace.apk`：固定个人发布签名、仅 `arm64-v8a` 原生库，须验证证书、哈希和原生架构。
2. `README.md`：安装、功能、数据安全和使用说明。

不得把签名/权限/ABI/单元测试/设备测试/Lint/JSON报告、合成KML、截图ZIP或其他文件上传到Release。这些保留在GitHub Actions对应产物和源码文档中。更新说明、测试摘要和构建链接直接写入Release正文。

工作流受信任功能分支手动构建生成 `StarTrace-release-files` 产物，内含上述且仅上述两个文件；调试包和验证报告不混入该产物。发布时明确列出两个附件，不枚举整个报告目录。

保持 Pre-release、公开仓库、原发布签名和纯ARM64，不自动合并main。之前已发布的附件不因这项未来规范自动删除。GitHub自带的Source code压缩包不是手工上传的Release asset，用户可忽略。
