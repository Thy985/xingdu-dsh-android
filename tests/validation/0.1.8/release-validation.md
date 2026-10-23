# 0.1.8 开发预发布验证记录

本记录补充构建时的 `summary.json`，不改写历史自动化结果。发布为非官方 Debug 开发预览 `v0.1.8-dev-preview`，不是稳定发行版。

## 用户人工反馈

用户明确报告已在真机完成本地验证。提供的人工界面证据显示本机 DSH、内置插件、工具/文件及终端页面可见。仅记录这一范围，不把页面可见扩写为模型请求成功、终端命令完整通过、完整 ARM64 引擎端到端验收或全功能设备测试。

未有明确实测证据的范围包括 LAN/Tailscale 远程连接、SAF 系统选择器与持久授权/撤销、导出回原文件夹、覆盖升级、系统截图/录屏/最近任务隐私和真实远程进程同步。截图隐私开关有实现和测试代码，不等于其所有设备场景已验收。

## 自动化证据

- 现有构建日志和 XML：应用及 AndroidTest Debug APK 构建成功；JVM 58 项，失败 0、错误 0、跳过 0。
- 新增 `ScreenPrivacyTest` 9 项测试仅编译，执行 0 项。构建时无 adb 设备，模拟器缺少 `/dev/kvm`；未用用户人工反馈替代仪器测试。
- 发布准备时复跑 `node tests/mobile-adapter-regression.cjs` 与 `node tests/mobile-shell-contract.cjs`，均通过。
- 发布 APK 与本地 `app/build/outputs/apk/debug/app-debug.apk` 按字节一致；不因文档更新重编译已验证 APK。APK 内运行时散列与 manifest 一致。

## 公开内容边界

源码和发布附件不包含用户截图、用户认证文件、用户工作区、本机 SDK 路径、构建缓存或签名私钥。保留的 0.1.5 历史图片是仓库原有的主机 Chromium 无害测试图片，并非用户手机截图。

发布前使用 Gitleaks 默认规则扫描源码。扫描中仅对 `apksigner` 的公开公钥指纹和四个公开 Node API 头文件的 SHA-256 基线值设置精确豁免；规则保存在仓库 `.gitleaks.toml`，未以全目录或全文件豁免隐藏凭证。另核对 APK/运行时的私密配置文件名，并验证源码 ZIP 与发布文件清单。密钥扫描属于启发式检查，不能保证识别所有形式的秘密。

APK、完整源码 ZIP 和 SHA-256 文件只作为 Release 附件；Git 不包含 APK 或大型 `runtime.zip`。本次保留远程主分支已有提交，不强制推送。
