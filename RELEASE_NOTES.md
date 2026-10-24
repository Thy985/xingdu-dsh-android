# 星渡 · DSH 0.1.9-dev — Debug 开发预览

**非官方、非稳定版，仅供开发测试。** 这不是 DeepSeek 官方安卓客户端。发布标签：`v0.1.9-dev-preview`，应用版本：`0.1.9-dev` / versionCode 10。运行时不变：Android Bionic Node.js 24.18.0 与 DeepSeek Harness `0.2.1-rc.2`；targetSdk 仍为 28，Debug 签名不是正式发行签名。

## 本版更新

工程与验证补全版：面向最终对外发布路线（见 [ROADMAP.md](ROADMAP.md)）完成 0.1.9 里程碑。

- **重构**：引擎状态改为类型化 `EngineState` 传递，界面不再通过匹配中文日志字符串判断状态；状态与通知文案迁入资源文件。行为不变，为后续架构迁移铺路。
- **仪器测试首次真机全量执行**（华为 DBR-W00，Android 12）：26 项全部执行、22 项通过。含 `:remote` 进程跨进程 `FLAG_SECURE` 同步实证；执行中发现并修复两处测试代码缺陷（API 33 方法、EMUI dumpsys 解析）。
- **用户真机验收**：局域网远程连通、SAF 实操、覆盖安装、中文输入法、真实 API 会话，用户反馈未发现问题。
- **CI**：GitHub Actions（Linux JVM 测试 + lint + 构建 + 插件契约）；建立 CHANGELOG 与路线图。

## 已有验证与未测范围

- JVM 单测 58 项：Linux CI 在发布提交上执行（见 Actions 记录）；本机 Windows 57/58，唯一差异为依赖 POSIX FIFO 的既有用例（与本版改动无关，重构前同样失败）。
- 仪器测试 4 项失败均已定性：2 项为 androidx `ActivityScenario` 生命周期机制在 EMUI 平板无法到达 RESUMED（对应应用逻辑保留"未验证"口径）；真机软键盘窗口缩放（两厂商输入法均不触发）与华为 WebView 主文档错误呈现（记待查）。
- 用户验收按项目惯例记录为用户人工确认，不折算为逐项自动化验证。
- **仍无逐项验收结论**：Tailscale 连通、系统截图/录屏/最近任务完整行为、较大字号、后台恢复、前台服务存活、SAF 授权撤销等边界。详见 [验证清单](tests/validation/0.1.9/device-verification-checklist.md) 与 [仪器测试记录](tests/validation/0.1.9/instrumented-huawei-dbr-w00.md)。

## 下载与校验

- `Xingdu-DSH-0.1.9-dev-arm64.apk`：ARM64 Debug 侧载 APK；本机 Android 8.0+，远程 Android 9+。
- `Xingdu-DSH-0.1.9-dev-source.zip`：源码、文档、测试与完整内嵌运行时，可按 README 构建。
- `SHA256SUMS.txt`：两个附件的 SHA-256。下载到同一目录执行 `sha256sum -c SHA256SUMS.txt`。

Git 仓库与 GitHub 自动源码归档不含约 211 MiB 的 `runtime.zip`；完整工程请使用上方明确上传的源码 ZIP。没有附带大模型，模型请求仍需自行配置 API；请先备份重要数据，仅用非敏感文件测试，不要对敏感内容开启截图或录屏。
