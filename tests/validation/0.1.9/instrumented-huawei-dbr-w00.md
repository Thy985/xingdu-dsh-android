# 0.1.9 仪器测试真机执行记录 — 华为 DBR-W00（Android 12）

2026-10-24，设备：HUAWEI DBR-W00 平板，Android 12（API 31），arm64-v8a，EMUI WebView。执行方式：`adb shell am instrument -w -e class <类> io.github.zhanxing.dshandroid.test/androidx.test.runner.AndroidJUnitRunner`（绕开 Gradle 以便逐套件超时控制）；构建为本仓库 `5069c46`+ 的 debug 包（targetSdk 28）。测试前临时开启 `accelerometer_rotation=1`（测后恢复 0）。

## 结果总表（26/26 全部执行）

| 套件 | 执行 | 通过 | 失败 | 说明 |
| --- | --- | --- | --- | --- |
| `SafTransferTest` | 10 | **10** | 0 | 7.4 秒全过 |
| `ScreenPrivacyTest` | 9 | **7** | 2 | 失败为 androidx 场景机制问题，见下 |
| `MobileLayoutTest` | 7 | **5** | 2 | 失败为设备行为差异，见下 |
| 合计 | 26 | 22 | 4 | — |

## ScreenPrivacyTest 细目

通过（7）：`defaultIsFalseAndActivityStartsSecure`、`explicitEnableRemovesSecureFlag`、`disablingRestoresSecureFlag`、`unrelatedWindowFlagsRemainUntouched`、`providerIsNotExported`、`multipleControllersAndObserversSynchronize`、**`remoteActivityWindowSynchronizesSecureFlag`**（`:remote` 进程 FLAG_SECURE 默认保护、跨进程开启/关闭同步、经 dumpsys 窗口旗标实证——0.1.8 核心特性首次真机验证）。

失败（2，三次复跑结果一致）：

- `resumedLifecycleRepairsTamperedSecureFlag`：`scenario.moveToState(RESUMED)` 报 "Activity never becomes requested state [RESUMED] (last lifecycle transition = STARTED)"，@After 的 `scenario.close()` 同样失败。
- `preferenceSurvivesMainActivityRecreate`：`scenario.recreate()` 后停在 PAUSED，无法 RESUMED。

定性：androidx.test `ActivityScenario` 的 recreate/moveToState 生命周期驱动在 EMUI 12 平板上无法把 Activity 推回 RESUMED（隔离单跑亦复现，与用例顺序无关）；被测的"篡改旗标后恢复保护""重建后保留选择"逻辑因此未获验证，但同类保护逻辑已由其他 7 项覆盖。**这不是应用代码缺陷的证据**，保留"未验证"口径，待其他设备/模拟器复测这两项。

执行中发现并修复的测试代码 bug（本提交一并入库）：

1. `windowDump` 使用 `InputStream.readAllBytes()`（API 33+），在 API<33 设备直接 `NoSuchMethodError`——改为手写缓冲读取（minSdk 26 兼容）。
2. `remoteSecureFlag` 解析器假定 `fl=` 与 `mAttrs=` 同行且单 token——EMUI 格式为独立行的空格分隔符号列表（`fl=LAYOUT_IN_SCREEN SECURE …`）。改为窗口段内任意行匹配 `fl=` 并按空格/竖线拆分 token（`\bfl=` 不误配 `pfl=`/`hwFlags=`）。

## MobileLayoutTest 细目

通过（5）：`nativeChromeAndWelcomeScreenAreRealAndTouchable`、`nativeHomeStatusUpdatesKeepScrollAndConservativeControls`（类型化状态重构后的行为回归）、`orientationChangeKeepsActivityWebViewAndDraft`（开启旋转后通过）、`secureWebViewLoadsFixtureAndAdapterTogglePreservesDraft`、`remoteManifestIsPrivateAndLocalHttpGateRemainsExact`。

失败（2）：

- `imeResizeIsObservedAndRestored`：软键盘弹出后 WebView 未缩放（1400→1400）。两台设备、两厂商输入法（讯飞小米版、华为 LatinIME）均复现同一行为；该用例以 `showSoftInput(SHOW_IMPLICIT)` 驱动，编写时面向模拟器键盘。**本机 `adjustResize` 的真机 IME 缩放仍记"未验证"**，留待能触发真键盘缩放的设备。
- `mainFrameHttpErrorIsNotReportedConnectedAndRetryRecovers`：注入的主文档 HTTP 错误未在预期时间内反映到页面错误状态（`waitForNativeState` 超时）。疑似华为 WebView 对主文档错误的回调时序/行为差异，**应用错误呈现路径在华为 WebView 上记"待查"**（0.1.9 后续项），当前不判定为应用缺陷。

## 环境备注

- 期间出现一次系统级 `permissioncontroller.GrantPermissionsActivity` 窗口与输入法窗口，为瞬态系统噪音，未影响后续执行。
- 未发生进程被杀、挂起或 adb 失联（对照：MIUI 日常机三轮均无法完成）。
- 原始 console 输出未逐行归档，以上计数与失败栈摘自执行输出与 logcat（`TestRunner` 标签）。
