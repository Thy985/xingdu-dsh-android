# 变更日志

按版本记录功能与工程变更。验证证据以 [tests/validation/](tests/validation/) 各版本归档为准；"测试通过"仅指当轮实际执行的测试。0.1.7 之前的版本为本地交付，无发布日期归档，本节依据当版验证记录与 0.1.7 期 README 历史文档整理。

## 0.1.9（开发中）

### 工程与文档

- 新增本变更日志与 [ROADMAP.md](ROADMAP.md)（通往正式发布的版本路线），README 提供链接。
- 建立 `tests/validation/0.1.9/device-verification-checklist.md` 真机补验清单骨架（仪器测试执行 + README 未验证项逐项补验）。
- 新增 GitHub Actions CI：JVM 测试 + lint + 构建，从 GitHub Release 源码包按 SHA-256 恢复 `runtime.zip`；仪器测试以手动触发方式提供。首跑通过（Linux，JVM 测试全绿；修复 `setup-android` 默认安装已下架的 `tools` 包）。
- 首次真机仪器测试尝试（Redmi，Android 17）：仅执行 1/26 即因 IME 用例失败与进程崩溃中断，不计入结论；MIUI「USB 安装」限制待放开后复测，记录见 [tests/validation/0.1.9/device-verification-checklist.md](tests/validation/0.1.9/device-verification-checklist.md)。
- 真机仪器测试完成执行（华为 DBR-W00，Android 12）：26/26 全部执行，22 通过 / 4 失败。`SafTransferTest` 10/10、`ScreenPrivacyTest` 7/9（含跨进程 `FLAG_SECURE` 同步；2 项失败定性为 androidx `ActivityScenario` 生命周期机制在 EMUI 平板不可用）、`MobileLayoutTest` 5/7（IME 缩放与华为 WebView 错误呈现差异，记未验证/待查）。修复执行中发现的测试代码 bug：`readAllBytes()`（API 33+）改为 minSdk 26 兼容读取、`fl=` 旗标解析适配 EMUI 多行符号格式。MIUI 日常机三轮无法完成执行（进程被杀/场景挂起），已归档定性。详见 [0.1.9 仪器测试记录](tests/validation/0.1.9/instrumented-huawei-dbr-w00.md)。
- 重构：`DshService` 快照携带类型化引擎状态（`EngineState`），`HomeStatePolicy` 改按状态判定，不再匹配中文日志字符串；状态与通知相关文案迁入 `strings.xml`。行为不变。Windows 本机 JVM 测试 57/58 通过，唯一失败为依赖 POSIX FIFO 的既有用例（重构前同样失败，非本次回归）；Linux/CI 复跑须确认 58/58。

## 0.1.8-dev（versionCode 9，2026-10-23，`v0.1.8-dev-preview`）

### 功能

- 屏幕隐私：本机与远程 Activity 默认设置 `FLAG_SECURE`；开启"允许截图与录屏"需风险确认；本机与远程共用选择、重启保留；经私有 `ScreenPrivacyProvider` 实现跨进程读取与变更通知。

### 验证

- 新增 9 项屏幕隐私仪器测试：仅编译、执行 0 项（无可用设备）。
- 58 项 JVM 测试通过；移动适配回归 2 组通过；APK v2 签名验证通过。
- 用户真机整体验收完成（反馈"测试未发现问题"），4 张授权截图入 `docs/images/`；不据此认定逐项功能验收。

## 0.1.7-dev（versionCode 8，2026-10-23，`v0.1.7-dev-preview`，首次对外发布）

### 功能

- 「星渡」品牌：应用名、图标（`branding/`，`prepare-branding.py` 生成资源）。
- 远程模式：独立 `:remote` 进程与 WebView 数据目录；完整启动 URL 粘贴入口；HTTPS 默认，私网 HTTP（私有 IPv4、Tailscale `100.64.0.0/10`、本地 IPv6、`.local/.lan/.ts.net` 等）需显式允许并二次确认；拒绝 userinfo URL；SSL 错误不放行；断开清理 Cookie 与网页存储。
- SAF 增强：文件夹导入保存可持久化授权与来源映射；新增"导出回原文件夹"，在原授权树内创建 `xingdu-export-*` 新子目录，不覆盖原文件。

### 验证

- 58 项 JVM 测试（新增 `RemoteUrlPolicyTest`、`SnapshotExportPlanTest`）；17 项仪器测试仅编译、执行 0 项；lint 0 错误 19 警告；ARM64 运行时静态校验通过；与 0.1.6 同 Debug 签名证书。

## 0.1.6-dev（versionCode 7，本地交付）

### 功能

- SAF 手机文件传输（无需 Root、不加存储权限）：`ACTION_OPEN_DOCUMENT` 导入文件副本至工作区 `import-<UUID>/`；`ACTION_OPEN_DOCUMENT_TREE` 导入文件夹快照（保留空目录）；`ACTION_CREATE_DOCUMENT` 单文件另存为。
- 限额与安全边界：单文件 128 MiB、每批 256 MiB / 1000 文件 / 2000 条目 / 16 层；导出浏览器每目录 200 条；NFC 规范化与非法名称拒绝；拒绝符号链接、越界、循环/重复条目。此版 URI 授权不持久保存。

### 验证

- 39 项 JVM 测试通过（新增 `WorkspaceTransfersTest`）；14 项仪器测试仅编译；lint 0 错误 7 警告；运行时静态校验通过；系统选择器 UI 未在真机验证。

## 0.1.5-dev（versionCode 6，本地交付）

### 功能

- 原生首页工作台：本机引擎状态卡、随包 DSH 版本与安装标记、设备/ABI 信息、启动/停止/重试、脱敏日志、只读诊断与使用指引；服务快照增量更新。
- 修复真实页面两处问题：空会话输入栈恢复垂直居中（原被强制推底）；无已挂载会话时工具导航被静默禁用，改为显示工作区/会话指引。

### 验证

- 25 项 JVM 测试通过（新增 `HomeStatePolicyTest`）；主机 Chromium 四组共 68 项检查通过（`mobile-feedback` 等新套件）；6 项仪器测试仅编译。

## 0.1.4-dev（versionCode 5，本地交付）

修复优先版本，未更换官方 DSH、未扩大 shell 权限、未增加原生 JS 桥。

### 修复

- 日志脱敏遗漏：`Authorization: Bearer` 原只隐藏"Bearer"仍留值；改为对 Authorization、Cookie 整行及常见 API key / password / Bearer 值保守隐藏。
- 快速启停与进程归属：停止后立即启动不再被旧状态吞掉；旧停止命令只清理自己之前的进程，不杀更新进程。
- 首次安装取消与回滚：散列与单文件读取期间均可取消；提交失败恢复旧版本。
- 页面恢复：本机就绪探测最多 4 次；主文档 HTTP 错误与渲染进程退出明确提示可重试；仅主页面真实加载完成才显示"已加载"。
- 手机适配初始化：脚本早于 body 注入时等待 DOMContentLoaded，避免 MutationObserver 空目标错误。

### 验证

- 23 项 JVM 测试通过（新增 `ConnectionAttemptTest` 等回归）；57 项主机 Chromium 检查；5 项仪器测试仅编译；`package-deliverables.py` 交付流程建立。

## 0.1.3-dev（versionCode 未归档，本地交付）

### 功能

- 手机设置适配：分类 → 单列详情 → 返回分类导航；模型编辑（提供商标题、模型字段、推理行）拆为单列；内置插件页单列浏览。沿用官方表单与校验逻辑，不搬动 DOM。

### 验证

- 新增 `tests/mobile-settings.cjs` 真实官方页面回归 25 项通过；4 项 Android 仪器测试未执行（无设备）。

## 0.1.2-dev（versionCode 未归档，本地交付，历史基线）

### 功能

- 手机组合插件 `mobile-shell/`：经官方 `shell.overlay` / `settings.action` 插槽提供底部「会话 / 对话 / 工具」导航、会话抽屉；共用官方 React，不复制编辑器。
- 基础手机布局适配（`assets/mobile/` CSS/JS，窄屏注入）；内嵌 ARM64 运行时随包分发，Gradle `verifyBundledRuntime` 拒绝空壳构建。

### 验证

- 5 项 JVM 测试通过；主机 Chromium 组合主流程 17 项、基础布局 15 项通过；4 项仪器测试未执行（模拟器 watchdog 故障）。
