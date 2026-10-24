# 项目路线图

星渡 DSH Android 的版本路线与发布策略。当前定位（2026-10 确定）：**中期目标为正式对外发布**；在此之前先补全验证、补齐工程基础，再进行发布架构迁移。历史变更见 [CHANGELOG.md](CHANGELOG.md)，各版本验证证据见 [tests/validation/](tests/validation/)。

本文件描述计划，不描述已完成的事实；任何"已验证"结论以对应版本的验证记录为准。

## 总原则

- 延续"已验证 / 未验证"文档纪律：每个版本留下 `tests/validation/<版本>/` 归档，不把计划或用户整体反馈记为逐项通过。
- 先在现有架构（targetSdk 28）上验证扎实并建好 CI，再做大架构迁移；迁移后按同一清单重新验证。
- 不改变安全边界承诺：不绕过审批、不加无约束 shell、不放宽 WebView 与 URL 策略。

## 发布渠道约束（2026-10 调研结论）

这些硬约束决定了架构升级的必要性与顺序：

| 渠道 | 约束 | 对本项目的影响 |
| --- | --- | --- |
| GitHub Releases | 无体积限制 | 正式发布的主渠道；正式签名后即为稳定版分发通道 |
| Google Play | 2026-08-31 起新应用/更新须 target API 36+（现有应用须 35+ 才可被发现） | targetSdk 28 依赖"应用数据目录可 exec"，API 29+ 被 W^X 禁止；必须把二进制迁移到 nativeLibraryDir（jniLibs 打包）才可能升级 |
| IzzyOnDroid | 单 APK 惯例约 30 MB | 内置约 211 MiB `runtime.zip` 的全量包不可能；只有"瘦包 + 首启下载运行时"方案才可能达标 |
| F-Droid 官方库 | 原则上禁止预编译二进制，须从源码构建 | 捆绑的 Termux 编译 Node 闭包无法过审；列为长期调研项（需源内构建 Node 或上游协作） |

结论：主渠道为正式签名的 GitHub Releases；Play / IzzyOnDroid 在 0.3.0 阶段按瘦包可行性评估。

## 版本路线

### 0.1.9 — 验证补全 + 工程基础（不改变架构）

目标：把 README 中"仍无逐项验收结论"的清单清零或明确降级，并为后续两个版本加厚测试保护网。

1. **执行全部仪器测试**：重点是 9 项从未运行的 `ScreenPrivacyTest`，连同 `MobileLayoutTest`（7 项）、`SafTransferTest`（10 项）；结果归档 `tests/validation/0.1.9/`。
2. **真机补验清单**：LAN / Tailscale 远程连通、SAF 系统选择器与持久授权/撤销、回原文件夹导出、覆盖安装升级、截图/录屏/最近任务完整系统行为、跨进程 `FLAG_SECURE` 同步、中文 IME / 旋转 / 后台恢复、配置自有 API 后的完整模型会话与受限工具流程。清单骨架见 [tests/validation/0.1.9/device-verification-checklist.md](tests/validation/0.1.9/device-verification-checklist.md)。
3. **GitHub Actions CI**：JVM 测试 + lint + 构建；从 GitHub Release 源码包按 SHA-256 恢复 `runtime.zip`（Git 仓库不含该资产）；仪器测试先以 `workflow_dispatch` 手动触发。
4. **文档基础**：建立本路线图与 `CHANGELOG.md`，README 提供链接。
5. **低风险解耦重构**：`DshService` 输出类型化引擎状态，`HomeStatePolicy` 不再匹配中文日志字符串；状态相关文案迁入 `strings.xml`；其余 UI 字符串逐步迁移。全程保持 58 项 JVM 测试通过。

### 0.2.0 — 发布架构迁移（关键路径）

目标：摆脱 targetSdk 28 折中，使应用符合现代发布要求。这是通往正式发布的最大单项工程。

1. **运行时重打包**：可执行文件（node、bash、ripgrep、flock、dsh_pty_exec）改经 `jniLibs` 落 nativeLibraryDir；JS / 脚本 / 数据仍在 assets；`scripts/runtime/build-runtime.sh` 输出新布局。此为 targetSdk 升级的前提（API 29+ 禁止 exec 应用数据目录文件）。
2. **targetSdk 28 → 36** 一步到位，同步处理强制项：`DshService` 声明 `foregroundServiceType`（如 dataSync）与对应 `FOREGROUND_SERVICE_*` 权限；`POST_NOTIFICATIONS` 运行时权限（API 33+）；`RuntimeInstaller` / `RuntimeCommit` 适配新布局（二进制不再 chmod，哈希流式校验保留）。
3. **全量回归**：JVM + 仪器测试，并按 0.1.9 清单模板做真机复验——迁移会改变进程与文件行为，旧验证结论不自动延续。
4. **分发架构落地**：全量包（GitHub Release）与可选瘦包（首启下载 runtime，复用现有流式 SHA-256 校验）双轨。瘦包会改变"完全离线首启"属性，需在 README 明示。

### 0.2.1 — 功能补齐

目标：补上文档已承认的缺口。

1. **远程下载**：`RemoteActivity` 的 DownloadListener 落地为 SAF 另存，复用 `WorkspaceTransfers` 的限额与路径校验模式（`RemoteActivity.java` 中当前仅 toast 提示）。
2. **运行时原子发布**：staging 目录 + 重命名提交；`runtime.staging-*` / `runtime.previous-*` 残留清理策略。
3. **进程树回收**：引擎停止时对脱离的插件子进程做进程组回收（PTY helper 协作）。
4. **上游评估**：DSH 上游发布 0.2.1 正式版或更新时，评估升级捆绑版本并重跑回归。

### 0.3.0 — 正式发布

目标：从 Debug 开发预览转为正式对外版本。

1. **正式签名**：建立发布密钥与发布流程（当前仅 Debug 签名）；评估开启 R8/minify。
2. **渠道执行**：GitHub Releases 正式版为主渠道；IzzyOnDroid（仅当瘦包体积达标且政策允许）；Google Play（AAB + Play Asset Delivery 的不可执行限制需实测验证）；F-Droid 官方库维持长期调研。
3. **合规**：GPL-3 组件（bash / readline）分发义务与对应源码提供方式、隐私政策、`THIRD_PARTY_NOTICES.md` 更新。
4. **文档口径切换**：README 从"开发者预览"改为"正式版"，重写验证状态章节。

## 不在当前路线内

以下方向曾被讨论但不排期，避免范围蔓延；如需纳入须先修订本文件：

- 相机、语音、附件选择等桌面能力的逐项移植。
- 第三方插件与原生扩展支持。
- SAF 实时挂载 / 双向同步（与现有"快照 + 显式导出"模型冲突）。
- 应用内自动更新。

## 状态记录

| 版本 | 状态 | 备注 |
| --- | --- | --- |
| 0.1.8-dev | 已发布（`v0.1.8-dev-preview`） | 屏幕隐私；用户真机整体验收完成 |
| 0.1.9-dev | 已发布（`v0.1.9-dev-preview`） | CI 建立并全绿；仪器测试 26/26 真机执行（22 过，4 项失败定性为环境/测试框架因素）；用户人工验收覆盖 LAN 远程、SAF、覆盖安装、中文 IME、真实 API 会话；Tailscale、屏幕隐私系统实操、字号/后台恢复按"未执行"记录 |
| 0.2.0 / 0.2.1 / 0.3.0 | 计划 | 下一步：0.2.0 发布架构迁移（jniLibs 重打包 + targetSdk 36） |
