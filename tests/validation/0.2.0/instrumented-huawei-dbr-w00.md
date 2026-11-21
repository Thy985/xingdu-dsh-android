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

---

# 第二轮：targetSdk 36 切片（2026-11-21 同日）

- 被测构建：split 布局 + **targetSdk 36**（compileSdk 36，versionCode 11 / versionName 0.2.0-dev）
- Manifest 变更：`DshService` 增加 `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`（dataSync 类型有 6h/24h 运行时限，长驻引擎不适用）；新增 `FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS` 权限
- UI 变更：edge-to-edge 在 targetSdk 35+ 强制（36 不可 opt-out）——`MainActivity.applySystemBarInsets` 按 systemBars+cutout+IME 补 padding，RootActivity/RemoteActivity 同步
- 结论：**37 项全部执行，33 通过 / 4 失败**；4 项失败全部为 0.1.9 已归档环境因素（签名逐字一致），零新增回归

## TargetSdk36Test（B3/B7/B8 证据）

| 用例 | 证明内容 |
| --- | --- |
| `b3_bashDlopensSonameChainFromDataDir` | **B3 判定**：targetSdk 36 的 untrusted_app 域仍允许数据目录 dlopen——bash 的 libreadline.so.8 / libncursesw.so.6 / libtinfo.so.6 SONAME 链全部从数据树加载成功 |
| `b3_flockAddonDlopensFromDataDir` | 编译版 N-API addon system.node 从数据树 dlopen，tryLock 导出可用 |
| `b3_spawnShimMapsFromDataDir` | libandroid-spawn.so（非 addon）在数据树映射成功——失败模式为 "did not self-register"（注册检查），而非 SELinux 拒绝 |
| `b7_wrapperDirectExecIsDenied` | 直接 execve 数据目录 wrapper 在 36 被拒（IOException Permission denied）——记录为预期降级形态 |
| `b7_wrapperRunsViaBash` | `bash bin/corepack --version` 全链路可用（bash 读脚本 → 脚本 exec bin/node 符号链接 → nativeLibraryDir）；附加记录：corepack 按需下载的 pnpm 二进制 exec 自数据缓存被拒（EACCES） |
| `b8_foregroundServiceStartsWithNotification` | 真实 DshService 路径（startForegroundService + binder）下 specialUse FGS 启动、引擎到达 URL_READY——startForeground(42) 在 5s 死限内执行的间接证明（否则 Android 12 会杀进程） |

## 调试发现（第二轮）

1. **OPENSSL_CONF 修复在 36 上同样生效**（见第一轮发现 1）——引擎经真实服务路径启动即依赖它。
2. **EMUI 过滤 `getActiveNotifications()`**：FGS 活跃期间该查询返回空列表（框架记录存在、引擎存活），通知状态栏可见性转 L4 人工项。
3. **ScreenPrivacyTest 生命周期用例跨轮波动**：androidx ActivityScenario 在 EMUI 的不稳定（0.1.9 失败 → 28 轮通过 → 36 轮再失败），签名与 0.1.9 逐字一致，维持"环境因素"定性。

## 仍待执行（L4 人工清单）

- B6（0.1.9→0.2.0 真实覆盖安装，需真机 UI 首启流程；机制层已由 RuntimeCommit 事务 + 签名兼容 + 标记驱动重装覆盖）
- B8 补充：状态栏通知可见性人工确认、POST_NOTIFICATIONS 运行时授予实操
- 0.1.9 回灌项（Tailscale、系统截图/录屏/最近任务、SAF 撤销边界、大字号、后台恢复、OEM 保活）
