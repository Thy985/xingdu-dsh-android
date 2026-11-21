# 0.2.0 仪器测试记录（华为 DBR-W00）

- 日期：2026-11-21
- 设备：华为 DBR-W00（Android 12 / EMUI，serial USFBB23621200619）——0.1.9 同一台记录在案测试设备
- 被测构建：split 布局（jniLibs 五组件 + 数据 zip）+ **targetSdk 28**（B3/B6/B7/B8 需 targetSdk 36 构建，本版未切换）
- versionCode 11 / versionName 0.2.0-dev（工作树构建）
- 安装前状态：应用未安装（干净设备），无既有工作区数据——红线 6 守卫通过
- 结论：**31 项全部执行，29 通过 / 2 失败**；2 项失败均为 AGENTS.md 已定性的 EMUI 环境因素，与 0.1.9 记录一致

## 结果明细

| 套件 | 数量 | 结论 |
| --- | --- | --- |
| `SplitRuntimeTest`（新增，真引擎） | 5 | **5/5 通过**（B1/B1b/B2/B4/B5 证据，见下） |
| `SafTransferTest` | 10 | 10/10 通过 |
| `ScreenPrivacyTest` | 9 | 9/9 通过（0.1.9 失败的 2 项 androidx 生命周期用例本轮未复现——按"环境因素"定性，不折算为修复） |
| `MobileLayoutTest` | 7 | 5/7；2 项失败与 0.1.9 完全相同：IME 缩放（真机软键盘不触发窗口缩放）、华为 WebView 主文档错误呈现差异 |

## SplitRuntimeTest（B1/B2/B4/B5 证据）

| 用例 | 证明内容 |
| --- | --- |
| `b1_nativeLibsExtractedAndHashVerified` | PackageManager 已把五个 lib\*.so 解压进 nativeLibraryDir，size + 流式 SHA-256 与 runtime-native-manifest.json 逐项一致 |
| `b1b_installCommittedDataTreeWithSymlinks` | RuntimeInstaller 完成 201MB 数据 zip 安装；5 个历史 exec 位置符号链接全部指向正确目标；安装标记在位 |
| `b2_engineServesWebFromNativeLibraryDir` | 真实引擎（node 自 nativeLibraryDir，经数据树符号链接）启动并在 120s 内于 127.0.0.1:3080 服务官方 web UI |
| `b4_ripgrepExecsThroughSymlink` | `bin/rg --version` 经符号链接执行成功，输出含 ripgrep 版本 |
| `b5_ptyHelperResolvesThroughSymlink` | PTY 助手经符号链接可执行、无 ENOENT |

## 调试发现（执行过程中定性）

1. **OPENSSL_CONF 缺失（已修，进 DshService）**：Termux 构建的 node 在编译期烘焙 `OPENSSLDIR=/data/data/com.termux/...`，本应用域 fopen 该路径 Permission denied。0.1.9 的 node/openssl 组合对配置加载失败容忍（非 fatal），本次 Termux 滚动源拉到的新 node 变为 fatal（exit 13）。修复：env 注入 `OPENSSL_CONF=<runtime>/etc/tls/openssl.cnf`（数据树内文件，两代构建内容一致，app 可读）。**这是 0.1.9 一直潜伏的隐患，非 split 布局引入。**
2. **测试环境自纠（非产品缺陷）**：SplitRuntimeTest 首版 b4/b5 的 ProcessBuilder 未注入 LD_LIBRARY_PATH（仪器进程默认环境没有它），导致 rg 报 `CANNOT LINK ... libpcre2-8.so not found`。b2 引擎路径 env 完整即通过，反证 LD_LIBRARY_PATH 在 split 布局下工作正常。

## 未执行 / 待 targetSdk 36 构建

- B3（dlopen-from-data 判定）、B6（legacy→split 覆盖安装）、B7（wrapper 降级形态）、B8（targetSdk 36 系统行为）——待 36 切片
- 0.1.9 回灌项（Tailscale、系统截图/录屏/最近任务、SAF 撤销边界、大字号、后台恢复、OEM 保活）——按闭环第 6 步进入 36 切片清单
