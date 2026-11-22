# 原生 UI 路线：协议客户端 + Compose（M0–M3）

本文件描述计划，不描述已完成的事实；任何"已完成/已验证"结论以对应版本的
`tests/validation/` 归档为准。制度（矩阵、闭环、review）见
[docs/verification.md](verification.md)；行为约束见 [AGENTS.md](../AGENTS.md)。

## 决策记录（2026-11 确立）

- **保留**：Bionic Node 引擎与前台服务、运行时安装器、SAF 传输、URL 策略、
  屏幕隐私、mobile-shell 插件与全部验证资产。重写的是表示层，不是底座。
- **新建**：Kotlin 协议客户端（`DshApiClient`）+ Compose 原生 UI，走 DSH
  Web API；官方网页版保留为"网页模式"作为 parity 保险。
- **选 Compose 不选 Flutter**：项目定位 Android-only、现有代码是 Java/Kotlin，
  Compose 与现有服务/SAF 直接集成，无桥接层；iOS 不在路线内。
- **依据**：dsh-go（ccch1mneyyy/dsh-go）以 `dsh_client`（52 个协议文件）证明
  DSH Web API 可支撑全功能原生客户端；其"原生/网页一键切换同一对话"模式
  是迁移期保险的标准做法。
- **边界**：不抄 PRoot、不做无障碍手机操控、不做应用内自动更新、不建托管
  分享服务；这些与本项目安全边界承诺或单人维护现实冲突。

## 硬边界（不可协商）

1. **许可门（已决定，2026-11-21）**：维护者选定 **AGPL-3.0**——项目整体以
   AGPL-3.0 发布（LICENSE 已同步），允许阅读/复制 dsh-go 及 `dsh_client`
   源码作为实现参考；协议事实仍只从探针报文进入 notes 与 fixtures
   （AGENTS.md 红线第 4/5 条）。
2. **0.2.0 不跳过**：jniLibs 重打包 + targetSdk 36 与 UI 无关，是发布前提，
   且 UI 必须建在迁移后的服务层形状上，先做避免返工。

## 总览

| 里程碑 | 周数 | 出口标准 |
| --- | --- | --- |
| M0 双轨底座 | 1–4 | targetSdk 36 过全量仪器测试；协议探针在本机引擎收到真实事件流 |
| M1 协议客户端 | 5–8 | 纯 Kotlin 客户端完成"发消息→收流→审批→steering"全链路 |
| M2 核心 UI 四件事 | 9–14 | 日常对话全走原生，其余功能留网页模式入口 |
| M3 日常自用补全 | 15–20 | 终端/文件/通知/网页切换/主题，单人日常可用 |

每两周一个可演示切片；每个里程碑末都是完整可用状态，可停可续。

## M0（第 1–4 周）：双轨底座

**A 线：0.2.0 迁移（第 1–4 周）**——内容见 [ROADMAP.md](../ROADMAP.md) 0.2.0 节：

- 第 1–2 周：`build-runtime.sh` 输出 jniLibs 布局（node/bash/ripgrep/flock/
  dsh_pty_exec 落 nativeLibraryDir），`RuntimeInstaller`/`RuntimeCommit` 适配
- 第 3–4 周：targetSdk 36 强制项（`foregroundServiceType`、`POST_NOTIFICATIONS`）；
  全量回归，归档 `tests/validation/0.2.0/`

**B 线：许可决策 + 协议探针（第 1 / 3–4 周）**：

- 第 1 周：**定许可立场**（填写 AGENTS.md 红线第 4 条与本文末状态表）
- 第 3 周：用桌面 host-preview（`runtime-build/host-preview` 或 `DSH_HOST_BIN`）
  跑官方网页版，DevTools 抓 HTTP + WebSocket 帧，编目四条链路：**会话 CRUD、
  消息流事件、审批请求/响应、steering 注入**
- 第 4 周：Kotlin 最小探针（OkHttp WebSocket）对本机引擎连通、订阅、收事件
- 产出：`docs/protocol-notes.md`——端点清单、信封 schema、每条链路原始报文
  （后续所有 fixture 的唯一来源）

**M0 验收（闸门）**：探针在真机引擎上完整走通"发消息→流式收完→批准工具
调用→中途插话"。**第 4 周末走不通则 M1–M3 全部暂停**——协议是唯一可能
卡死的外部依赖，先证明它走得通。

**探针对象固定为捆绑的 `0.2.1-rc.2`**：M1 的 fixture 只以应用实际捆绑的引擎
为契约基线，不以抓包时的上游最新 rc 为对象；上游新版本统一放到 M1→M2
升级闸门处理（见下文）。

## M1（第 5–8 周）：`DshApiClient` 纯 Kotlin 协议模块

- 独立 `protocol/` 模块，零 Android 依赖，JVM 单测进现有 CI
- 第 5 周：连接层——WebSocket + RPC 信封（mux、心跳、重连、鉴权传递复用
  现有同进程 Binder 方案）
- 第 6 周：会话域——list/activate/create/delete、消息历史拉取
- 第 7 周：消息流域——发送、流式事件、工具调用事件、审批响应、steering 注入
- 第 8 周：收口——类型化错误、`DSH_PROTOCOL_TARGET` 版本常量、探针转
  fixture 自动化

测试策略：抓包 JSON 存 `protocol/src/test/fixtures/`，按 API 域回放断言；
CI 无需模型配置。M1 验收：对 mock server 全链路绿 + 真机真实 API 手动走通。

## M1→M2 升级闸门（2026-12-20）

本文件没有日历周次表，日期由周计划反推，换算依据如下：

- **锚点**：M0 第 3–4 周的出口是 `tests/validation/0.2.0/` 全量回归归档，归档
  日期 2026-11-21。B 线许可决策实际也发生在 2026-11-21，晚于其标称第 1 周，
  故不以 B 线作周次锚点。
- **口径**：周一为一周首日。含 2026-11-21 的是第 4 周（2026-11-16 ~ 11-22），
  反推第 1 周自 2026-10-26 起；M1 占第 5–8 周，即 2026-11-23 ~ 12-20。
- **闸门日期**：**2026-12-20**（M1 第 8 周最后一天）。升级须不晚于该日完成并归档；
  M2 第 9 周自 2026-12-21 起，不得在上游升级归档前开工。

闸门内容（方向已定）：

1. **基线探针打在 rc.2**：M0 会话内四链路探针以捆绑的 `0.2.1-rc.2` 为对象，
   先建立 fixture 契约基线。
2. **升级点**：上游版本升级固定在 M1 验收之后、M2 开工之前（不晚于 2026-12-20），
   目标 **DSH 0.2.2 稳定版**，一次做完；M1 期间不追 rc。
3. **执行顺序**：按 [verification.md](verification.md) 协议闭环——探针重跑 →
   fixture diff（人眼 review）→ 运行时重建与补丁锚点核验 → 全量回归 → 归档；
   缺任一环视为未完成。
4. **可选漂移记录**：同一套抓包脚本可先对新版本跑一遍，只留 drift 记录，
   不产出 fixture、不改 manifest。

若 M1 提前验收，闸门可提前执行，但不早于 M1 验收；若 M1 延期，闸门随之后移，
但 M2 不得在升级归档前开工。

## M2（第 9–14 周）：Compose 核心 UI 四件事

- 第 9–10 周：**壳 + 会话列表**。新 Compose Activity 为默认；现有 WebView
  Activity 降级为"网页模式"入口；`DshService`/`EngineState`/`HomeStatePolicy`
  集成；首页引擎启停卡保留
- 第 11–12 周：**消息流**。渲染选型决策（受限子集自渲染 vs markdown 库，
  DSH 消息元素有限，倾向受限子集）；流式增量渲染（长会话不卡）；composer
  + 发送；steering（排队"立即发送"、回复中插话）
- 第 13–14 周：**审批卡**。工具调用展示、批准/拒绝、超时降级；对 fake
  engine 的仪器测试；真机回归

`mobile-shell` 插件与 `mobile.js` 一行不动，继续服务网页模式。

## M3（第 15–20 周）：日常自用补全

- 第 15–16 周：文件面板（`files_api` + 桥接现有 `WorkspaceTransfers`/SAF）；
  设置初期网页模式透传
- 第 17 周：终端（`terminal_api`，先只读输出视图）
- 第 18 周：审批 pending 通知（点击直达审批页）；后台被杀检测 + OEM 放行引导
- 第 19 周：网页模式一键切换（同对话原生/网页互跳）
- 第 20 周：暗色主题、IME、横屏/平板两栏；发布 0.3.0-dev

## 贯穿纪律与风险止损

- 每里程碑打 tag + `tests/validation/<版本>/` 归档（见 verification 闭环）。
- 协议 fixture 全部版本化；升级引擎 = 重跑探针 diff fixture，diff 决定
  客户端改动。
- 范围冻结：发现"顺手可做"的相邻功能一律记入本文末"不在路线内"，不进
  当前里程碑。

| 风险 | 止损动作 |
| --- | --- |
| 协议信封复杂/加密解析不动 | M0 验收就是闸门：走不通停在网页模式，不硬闯 |
| 上游发新版改协议 | 冻结改为有边界：M1 期间不追 rc；到 M1→M2 升级闸门（2026-12-20）一次升到 0.2.2 稳定版并按协议闭环重跑。原先的"UI v1 前不追"由此收窄为"M2 开工前不追"，不再无限期顺延。 |
| 单人节奏中断 | 架构保证每个里程碑末可用，中断不报废 |
| 想抄 `dsh_client` 走捷径 | 等于选 AGPL：回到许可门重新决策并改 AGENTS.md，不允许默默抄 |

### 不在路线内（范围蔓延收容所）

- iOS / 鸿蒙客户端；PRoot 或 glibc 用户态；无障碍手机操控；应用内自动更新；
  托管分享服务；桌宠/Live2D；第三方插件生态。
- Tailscale 连通性验证（2026-11-21 维护者决策暂缓：本地优先——M1/M2 的协议
  客户端与原生 UI 全部面向本机引擎；远程入口保持 LAN 现状，Tailscale 暂缓
  而非永久排除）。

## 状态记录

| 里程碑 | 状态 | 备注 |
| --- | --- | --- |
| 许可立场 | **已决定：AGPL-3.0** | 2026-11-21 维护者决定；LICENSE、README 已同步 |
| M0 | 计划 | A 线 = ROADMAP 0.2.0；B 线 = 许可 + 探针 |
| M1 | **进行中（第一切片）** | `protocol/` 模块 + 31 项 fixture 回放（L1）；四链路捕获见 protocol-notes 第七节；L3 冒烟与证据见 `tests/validation/m1-protocol-client/` |
| M1→M2 升级闸门 | 计划：2026-12-20 | 基线 rc.2 四链路已捕获（protocol-notes 第七节）；目标 DSH 0.2.2 稳定版 |
| M2 | 计划 | Compose 四件事 |
| M3 | 计划 | 日常自用补全，发布 0.3.0-dev |
