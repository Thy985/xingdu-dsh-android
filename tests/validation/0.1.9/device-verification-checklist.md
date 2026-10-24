# 0.1.9 真机补验清单（骨架）

目标：把 README「仍无逐项验收结论」清单逐项落实为**通过 / 不通过 / 未执行（含原因）**三种结论之一，不允许留空或含糊。完成后将结论汇总进本目录 `summary.json`，并同步修订 README「验证状态」章节。

纪律沿用项目惯例：只用专用测试设备执行；不把"用户整体反馈"记为逐项通过；未执行就是未执行，注明原因。仪器测试与真机操作互不替代。

设备与环境信息（执行时填写）：

| 项 | 值 |
| --- | --- |
| 设备型号 / Android 版本 | 待填 |
| WebView 版本 | 待填 |
| 构建版本 / versionCode | 待填（应为 0.1.9 开发构建） |
| 是否同签名覆盖安装自 0.1.8 | 待填 |

## A. 仪器测试执行（连接设备后运行，结果粘贴或引用日志）

```sh
./gradlew connectedDebugAndroidTest
# 或仅屏幕隐私：
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.zhanxing.dshandroid.ScreenPrivacyTest
```

| 套件 | 数量 | 结论 | 证据文件 |
| --- | --- | --- | --- |
| `ScreenPrivacyTest` | 9 | **已执行（华为 DBR-W00，Android 12）：7 通过 / 2 失败**（失败定性为 androidx ActivityScenario 生命周期机制在 EMUI 平板不可用，非应用缺陷；含跨进程 FLAG_SECURE 同步在内的核心行为通过） | [instrumented-huawei-dbr-w00.md](instrumented-huawei-dbr-w00.md) |
| `MobileLayoutTest` | 7 | **已执行：5 通过 / 2 失败**（IME 缩放两厂商输入法均不触发——用例为模拟器键盘设计，真机 IME 缩放仍记未验证；主文档 HTTP 错误呈现疑似华为 WebView 差异，记待查；旋转用例在开启旋转后通过） | [instrumented-huawei-dbr-w00.md](instrumented-huawei-dbr-w00.md) |
| `SafTransferTest` | 10 | **已执行：10/10 通过** | [instrumented-huawei-dbr-w00.md](instrumented-huawei-dbr-w00.md) |

**2026-10-24 真机三轮尝试记录（Redmi 24117RK2CC，HyperOS / Android 17，arm64-v8a；用户日常机）：**

- **第一轮**（`.instrumented` 后缀并行安装）：仅执行 `imeResizeIsObservedAndRestored` 1 项即"Process crashed"，全套中断。
- **第二轮**（标准包名全新安装，Gradle `connectedDebugAndroidTest`）：执行至 MobileLayoutTest 第 4 项时手机从 USB 掉线（adb 失联），收到 4 项结果后中断。已得结论：
  - `nativeChromeAndWelcomeScreenAreRealAndTouchable` **通过**（首页真实渲染、可触摸——应用在 Android 17 / targetSdk 28 下正常安装启动，兼容性数据点）。
  - `imeResizeIsObservedAndRestored` **失败**：软键盘弹出后 WebView 未缩放（2088→2088）。设备默认输入法为讯飞输入法小米版（`com.iflytek.inputmethod.miui/.FlyIME`），`showSoftInput(SHOW_IMPLICIT)` 未触发窗口缩放；属设备/输入法行为，非应用缺陷判定，留待其他设备复测。
  - `orientationChangeKeepsActivityWebViewAndDraft` **失败**：请求横屏后方向未变。设备 `accelerometer_rotation=0`（竖屏锁定开启）——设备设置约束，需关闭方向锁定后复测。
  - `remoteManifestIsPrivateAndLocalHttpGateRemainsExact` 无结论（恰逢掉线）。
- **第三轮**（仅补跑 ScreenPrivacy/SafTransfer/remoteManifest）：`ScreenPrivacyTest` 第 1 项在 `ActivityScenario.launch` 处无限挂起（约 45 分钟）。JDWP 取证：应用主线程空闲（`MessageQueue.nativePollOnce`）、Activity 已 RESUMED、instrumentation 线程处于无限条件等待——符合 androidx ActivityScenario 启动竞态（快设备上 Activity 在监听器注册前已达 RESUMED、等待无超时）特征，非应用代码阻塞。中止后以 `am instrument` 直跑 `SafTransferTest`：进程约 9 秒即被系统杀死（Binder 死亡，无 Java/原生崩溃、dropbox 无记录 → SIGKILL 类外部终止；当日日志可见 MIUI `SmartPower`/`SwipeUpClean` 清理行为）。后续 `pm uninstall` 测试包触发 MIUI 异常，连带主包一并移除（均为空测试安装，无用户数据）。

**结论**：该日常机受 MIUI 省电/清理机制、输入法与方向锁定影响，不能作为仪器测试平台；上述两处"失败"均定性为设备环境约束。后续执行途径二选一：①专用测试设备（建议关闭该应用的省电限制与后台清理）；②CI 的 `workflow_dispatch` instrumented 任务（macOS x86_64 模拟器）。9 项 `ScreenPrivacyTest` 与 10 项 `SafTransferTest` 在真机执行完毕前保持"未验证"状态。

## B. 远程连通（需要用户自有主机与网络）

### B1. 局域网远程

**结论（2026-10-24）：用户人工验收通过，反馈未发现问题（用户整体报告，未逐步记录明细与设备信息）。**

- 前置：自有电脑按官方要求启动 DSH 并监听可达地址；手机与主机同一局域网。
- 步骤：粘贴完整启动 URL → 连接 → 会话加载 → 断开 → 重新连接。
- 预期：能加载会话；断开后 Cookie/网页存储被清理；再次连接需重新粘贴完整认证地址。
- 证据：无（用户口头/文字反馈，未附截图或日志）。

### B2. Tailscale 远程

- 前置：两端已加入同一 Tailnet；主机监听 Tailscale 地址（`100.64.0.0/10`）。
- 步骤：同 B1，URL 为主机 Tailscale 地址。
- 额外检查：私网 HTTP 的"允许 + 二次确认"流程；HTTPS 证书错误不放行。
- 结论 / 证据：待填。

## C. SAF 系统选择器与回原文件夹导出

**结论（2026-10-24）：用户人工验收通过，反馈未发现问题（用户整体报告，未逐项记录 C1–C6 明细；持久授权撤销、限额触发等边界未单独确认）。**

| # | 检查项 | 结论 | 备注 |
| --- | --- | --- | --- |
| C1 | 系统选择器实际弹出（文件 / 文件夹 / 另存为三种入口） | 并入上方整体验收 | 厂商实现差异记录在此 |
| C2 | 文件夹导入后持久化授权生效：重启应用再"导出回原文件夹"无需重新选择 | 并入上方整体验收 | |
| C3 | 系统设置中撤销授权后：导出回原文件夹被拒绝并提示重新选择 | 并入上方整体验收（边界未单独确认） | |
| C4 | 导出在原授权树内创建 `xingdu-export-*` 新子目录，不覆盖原文件 | 并入上方整体验收 | |
| C5 | 限额触发提示（>128 MiB 单文件 / >200 条目录浏览） | 并入上方整体验收（边界未单独确认） | 可用大文件构造 |
| C6 | 取消 / 中断后的残留说明与界面提示 | 并入上方整体验收（边界未单独确认） | 记录是否留下 `pending-*` |

## D. 覆盖安装升级

**结论（2026-10-24）：用户人工验收通过，反馈未发现问题（用户整体报告；基线版本、保留项明细未记录）。**

- 步骤：安装 0.1.8（versionCode 9）→ 启动一次并导入文件 → 同签名覆盖安装 0.1.9 构建 → 检查工作区、会话、屏幕隐私选择、远程记录是否保留。
- 预期：App 私有数据与默认工作区保留；`FLAG_SECURE` 选择保留。
- 证据：无（用户反馈，未附截图）。

## E. 屏幕隐私完整系统行为（`FLAG_SECURE` 两态）

| # | 检查项 | 结论 |
| --- | --- | --- |
| E1 | 默认态：系统截图被阻止、录屏黑屏、最近任务无预览 | 待执行 |
| E2 | 风险确认开启后：截图 / 录屏 / 最近任务可见 | 待执行 |
| E3 | 关闭恢复保护后：再次阻止，且不重载 WebView、不停止引擎 | 待执行 |
| E4 | 跨进程同步：本机修改选择后，远程进程（`:remote`）即时生效，反之亦然 | 待执行 |
| E5 | 重启应用后选择保留 | 待执行 |

## F. 中文 IME、旋转与后台恢复

| # | 检查项 | 结论 |
| --- | --- | --- |
| F1 | 中文输入法组合输入（候选词、回车上屏）不丢字、不误发送 | **用户人工验收通过（2026-10-24，整体报告）**；仪器级缩放断言在两厂商输入法上不适用（见仪器测试记录） |
| F2 | 旋转横竖屏：Activity/WebView 保留，草稿不丢 | **仪器测试通过**（华为 DBR-W00，`orientationChangeKeepsActivityWebViewAndDraft`，开启旋转后） |
| F3 | 较大系统字号下首页与设置页可用 | 待执行 |
| F4 | 后台切走再返回（含引擎运行中）：页面恢复，无白屏或状态错乱 | 待执行 |
| F5 | 引擎前台服务在后台不被系统杀死（记录厂商后台策略差异） | 待执行 |

## G. 真实模型会话与受限工具（需要用户自有 API）

**结论（2026-10-24）：用户人工验收通过，反馈未发现问题（用户整体报告；受限工具是否报告 `SANDBOX_UNAVAILABLE`、终端命令执行明细未记录）。**

- 前置：在 DSH 设置配置自有模型 API。
- 步骤：新建会话 → 多轮中文对话 → 让模型调用文件读写工具 → 观察受限工具是否报告 `SANDBOX_UNAVAILABLE` → 终端面板执行一条无害命令（如 `ls`）。
- 预期：对话正常；工具行为与 README「安全与已知限制」声明一致；终端可用。
- 证据：无（用户反馈，截图脱敏需求不适用）。

## 汇总规则

全部条目有结论后：生成 `tests/validation/0.1.9/summary.json`（沿用 0.1.8 结构，逐套件/逐项记录），修订 README「验证状态」，并在 CHANGELOG 0.1.9 小节记录结果。
