# L3 仪器测试 — m1-protocol-client（PR #8）

- 日期：2026-11-22
- 构建：`0.2.0-dev`（versionCode 11），debug + debugAndroidTest
- 设备：HUAWEI DBR-W00，Android 12（API 31），arm64-v8a，EMUI WebView（专用测试设备，USB）
- 命令：`./gradlew :app:connectedDebugAndroidTest`（Windows 下经 `GradleWrapperMain` 执行，`--offline`）
- 目的：壳层冒烟——验证 `protocol/` 模块与根构建接入未破坏既有仪器测试；本切片未改 `app/`。

## 汇总

| 指标 | 数量 |
| --- | --- |
| 总计 | 39 |
| 通过 | 36 |
| 失败 | 1 |
| 跳过 | 2 |

## 分套件

| 套件 | 结果 |
| --- | --- |
| `SafTransferTest` | 10/10 pass |
| `ScreenPrivacyTest` | 9/9 pass |
| `TargetSdk36Test` | 6/6 pass |
| `SplitRuntimeTest` | 5/5 pass |
| `MobileLayoutTest` | 6 pass / 1 fail |
| `B6OverInstallTest` | 1 skipped |
| `B6SeedTest` | 1 skipped |

## 失败项（原始，不改写为通过）

`MobileLayoutTest.mainFrameHttpErrorIsNotReportedConnectedAndRetryRecovers`

```
java.lang.AssertionError: pageError presence must become true
    at org.junit.Assert.fail(Assert.java:89)
```

定性：`webview-error-page-huawei`。该失败已在 `tests/validation/0.2.0/summary.json` 的 L3 `environment_factors` 中记录（主框架 HTTP 错误在华为 WebView 上超时内未呈现错误页），本次为同一失败复现，非本切片引入。按验证纪律仍计为 failed，不计入通过。

## 跳过项

- `B6OverInstallTest.b6_dataSurvivesAndRuntimeReinstalls`（需预置状态，默认跳过）
- `B6SeedTest.seedFirstRunAndStartEngine`（需预置状态，默认跳过）

两项与本次变更无关，沿用既有跳过条件。

## 证据位置

机器可读结果（构建产物，不入库）：
`app/build/outputs/androidTest-results/connected/debug/TEST-DBR-W00 - 12-_app-.xml`

HTML 报告（构建产物，不入库）：
`app/build/reports/androidTests/connected/debug/index.html`
