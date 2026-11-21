# 0.2.0 真机补验清单（骨架）

目标：0.2.0 发布架构迁移（jniLibs split 布局 + targetSdk 36）后的全量回归。结论只允许
**通过 / 不通过 / 未执行（含原因）** 三种，不允许留空。完成后汇总进本目录 `summary.json`，
并按闭环第 5 步逐字同步 README「验证状态」章节。

纪律沿用项目惯例：只用专用测试设备执行；历史验证结论不自动延续（0.1.9 的结论在本版
必须重跑）；未执行就是未执行，注明原因。仪器测试与真机操作互不替代。

设备与环境信息（执行时填写）：

| 项 | 值 |
| --- | --- |
| 设备型号 / Android 版本 | 待填 |
| WebView 版本 | 待填 |
| 构建版本 / versionCode | 待填（应为 0.2.0 开发构建） |
| 安装路径 | 待填（全新安装 / 覆盖安装自 0.1.9） |

## A. 仪器测试全量（迁移会改变进程与文件行为，0.1.9 结论不延续）

```sh
./gradlew connectedDebugAndroidTest
```

| 套件 | 数量 | 结论 | 证据 |
| --- | --- | --- | --- |
| `SafTransferTest` | 10 | 未执行 | — |
| `ScreenPrivacyTest` | 9 | 未执行 | — |
| `MobileLayoutTest` | 7 | 未执行 | — |

## B. split 布局关键验证（0.2.0 新增；B 线改造的核心未知项集中在这里）

| # | 项 | 预期 | 结论 |
| --- | --- | --- | --- |
| B1 | split 运行时首启安装 | zip 流式校验通过；数据目录无内嵌二进制；`nativeLibraryDir` 五个组件哈希与 manifest 一致 | 未执行 |
| B2 | 引擎启动（exec 走 nativeLibraryDir） | `libnode.so` 启动、web 页面加载、EngineState 就绪 | 未执行 |
| B3 | **dlopen-from-data**（最大未知项） | bash 交互（SONAME 别名 libreadline.so.8 等经 LD_LIBRARY_PATH 从数据目录加载）、flock `system.node`、`libandroid-spawn.so` 均可 dlopen。**若 SELinux 在 targetSdk 36 禁止数据目录 dlopen，需追加这批组件进 jniLibs（机械改动），逐项记录 errno** | 未执行 |
| B4 | ripgrep 经符号链接 | dsh grep 工具实际运行成功（`bin/rg` → `nativeLibraryDir/librg.so`） | 未执行 |
| B5 | PTY 助手（数据树符号链接） | 终端面板可用：engine 沿历史路径解析 `runtime/bin/dsh_pty_exec`，该位置是指向 `nativeLibraryDir/libdsh_pty_exec.so` 的符号链接（PATH 前置 nativeLibraryDir 仅是兜底） | 未执行 |
| B6 | legacy→split 覆盖安装升级 | 0.1.9 版本数据上覆盖安装 0.2.0：旧 runtime 重装、会话/设置保留、引擎可启动 | 未执行 |
| B7 | wrapper 降级记录 | `npm`/`pnpm`/`curl` 直接调用在 split+36 下的实际失败形态记录在案；`bash <runtime>/bin/pnpm` 形态可用性记录 | 未执行 |
| B8 | targetSdk 36 系统行为 | `POST_NOTIFICATIONS` 运行时权限、`foregroundServiceType` 声明生效、通知与前台服务正常 | 未执行 |

## C. 0.1.9 未决项回灌（闭环第 6 步：failed / environment / unverified 逐条进入本版）

| 来源 | 项 | 结论 |
| --- | --- | --- |
| 0.1.9 environment | androidx 生命周期机制在 EMUI 不可用（2 项） | 未执行 |
| 0.1.9 environment | 真机软键盘不触发窗口缩放（IME） | 未执行 |
| 0.1.9 待查 | 华为 WebView 错误呈现差异 | 未执行 |
| 0.1.9 unverified | Tailscale 连通 | 未执行 |
| 0.1.9 unverified | 系统截图 / 录屏 / 最近任务完整行为 | 未执行 |
| 0.1.9 unverified | SAF 授权撤销与限额边界 | 未执行 |
| 0.1.9 unverified | 较大系统字号 | 未执行 |
| 0.1.9 unverified | 后台恢复 / 厂商后台策略下前台服务存活 | 未执行 |

## D. 结果归档

执行完成后：本文件逐项填写 → `summary.json` 更新 → README「验证状态」逐字同步。
