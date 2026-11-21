# 验证体系：矩阵、闭环与 Review

本文件定义"什么样的变更算验证过"。规则生效于所有代理与贡献者；行为约束见
[AGENTS.md](../AGENTS.md)。`tests/validation/<版本>/` 只放证据，规则只在本文件。

本文件描述制度；各版本实际验证结果以对应 `tests/validation/` 归档为准。

## 一、六层验证矩阵

按成本从低到高排列。每一层只能证明"证明什么"列的内容，**超出该列的结论
不允许从该层得出**（这是 README"已验证/未验证"口径的执行标准）。

| 层 | 入口 | 证明什么 | 证明不了什么 | 何时跑 |
| --- | --- | --- | --- | --- |
| **L0 契约** | `node tests/mobile-shell-contract.cjs`；将来 `protocol/` 的 fixture 契约检查 | 架构不变量：插件身份、插槽所有权、禁止 DOM 手术/原生桥、fixture schema | 任何运行时行为 | 每次提交，CI 强制 |
| **L1 单测** | `./gradlew testDebugUnitTest`；`protocol/` fixture 回放 + mock server | 策略类（URL/Zip/State）、状态机、协议客户端逻辑 | Android 框架、WebView、真机 | 每次提交，CI 强制 |
| **L2 主机回归** | `PLAYWRIGHT_MODULE=… python3 scripts/test-mobile-ui.py`（host-preview 官方实例） | 网页模式移动适配 vs 真实官方前端 | Android WebView/IME 特有行为 | PR、发布前 |
| **L3 仪器** | `./gradlew connectedDebugAndroidTest`（专用设备） | SAF、FLAG_SECURE 跨进程、布局旋转、原生 UI 关键流 vs fake engine | OEM 例外、系统级截图/录屏实操 | 重大变更、发布前 |
| **L4 人工清单** | `tests/validation/<版本>/device-verification-checklist.md` | 系统级行为：Tailscale、截图/录屏/最近任务、SAF 撤销、大字号、后台恢复、OEM 保活 | 可重复性 | 每发布 |
| **L5 用户验收** | 归档的人工验收记录 | 整体可用性 | 任何自动化断言 | 每发布 |

### 一键本地快速层

`scripts/verify-local.sh` = L0 + L1 + lint，供代理每次改完自检、维护者每次
合入前确认。它不是发布门禁——发布门禁以 CI 与矩阵全层为准。

## 二、变更类型 → 最低验证门槛

"完成"的定义 = 下表对应层全部绿 + 归档更新。低于门槛不得合入。

| 变更类型 | 最低要求 |
| --- | --- |
| 纯逻辑/策略（Java/Kotlin，无 UI/协议） | L1 |
| mobile-shell / 网页模式适配 | L0 + L2；行为可见变化加 L3 对应类 |
| 协议客户端变更 | L1（fixture 回放）+ L3 冒烟 |
| 引擎版本升级 | 探针重跑 → fixture diff → diff review（人眼）→ L1 → L3 → L2 |
| 原生 Compose UI | Robolectric/Compose 测试（计入 L1）+ L3 关键流 + L4 新增清单项 |
| 运行时/构建/targetSdk | L1 + L3 全量 + L4 全清单 + 构建门禁（runtime.zip 校验） |
| 版本发布 | 全层 + tag + `tests/validation/<版本>/` 归档 + summary.json |

## 三、验证闭环六步

"闭环"区别于"清单"的是第 6 步：结论必须流回下一次计划。

1. **计划**：新版本开始时先建 `tests/validation/<版本>/` 骨架与 checklist 模板。
2. **门禁**：L0/L1 在 CI 强制；不合层的提交不入库。
3. **执行**：L2–L5 按上表触发，不按心情触发。
4. **归档**：结果写入 `tests/validation/<版本>/summary.json`，schema 见下节。
5. **口径**：README 验证状态表逐字取自 summary.json；"未执行/未验证"按实记录。
6. **回灌**：`failed`、`environment_factors`、`unverified` 逐条进入下一版本
   checklist 骨架。0.1.9 的 4 项 EMUI 环境因素即按此模式延续；此条是硬规则，
   不是习惯。

### summary.json schema

```json
{
  "version": "0.1.9-dev",
  "layers": {
    "L0": {"status": "pass", "evidence": "ci-job-link"},
    "L1": {"status": "pass", "evidence": "58/58 ci; 57/58 windows-known"},
    "L3": {"status": "partial", "passed": 22, "failed": 4,
            "environment_factors": ["androidx-lifecycle-emui", "ime-windowresize", "webview-error-page"]}
  },
  "unverified": ["tailscale", "system-screenshot-recents", "saf-revocation-edge"],
  "user_acceptance": {"date": "", "scope": [], "note": ""}
}
```

字段固定：`layers`、`unverified`、`user_acceptance`；新增字段可以，删减固定
字段不行。`status` 取值 `pass / partial / fail / not-run`。

## 四、协议特有闭环（M1 起最高优先级）

```
bundled 引擎（UPSTREAM.json 版本）
  → 协议探针抓包（M0 产出 docs/protocol-notes.md + 原始报文）
  → protocol/fixtures/（版本化，唯一契约）
  → fixture 回放单测（L1）→ DshApiClient 实现
  → 引擎升级时：重跑探针 → diff fixtures → diff review（人眼，门 3）
     → 更新 fixture → 全链路重放 → UI 回归
```

两条铁律：

- **协议事实只进系统一次**：只允许从探针报文进入 `protocol-notes.md` 与
  fixtures，之后人和代理都不许凭记忆/猜测/第三方代码写协议处理。
- **fixture diff 必须人眼 review**：这是人机契约点，机器门不能替代。

## 五、Review 四道门

单人 + AI 协作下，review 的目的不是"多一双眼睛"，而是**把人的注意力配额
只花在机器证明不了的地方**。

| 门 | 执行者 | 内容 | 不做什么 |
| --- | --- | --- | --- |
| 1 机器门 | CI | L0 + L1 绿 | 人不看红着的 PR |
| 2 对抗复审 | 未参与变更的干净上下文代理 | 按 AGENTS.md 红线 + 本矩阵查：红线违规、fail-closed 绕过、happy-path 测试、凭记忆写协议 | 写代码的代理不自审 |
| 3 人工检查 | 维护者 | 仅四处：红线区（审批/URL 策略/FLAG_SECURE/exec 边界）、协议 fixture diff、意图与范围、真机抽查 | 其余代码不逐行读 |
| 4 合并 | 维护者本人 | merge 决定权 | 代理永不 merge |

## 六、环境雷区（引用）

Windows 本地 57/58、EMUI 已定性环境因素等，见
[AGENTS.md 环境备注](../AGENTS.md#环境备注防止把已知差异当-bug修)。
这些条目在矩阵中按"已知差异"处理，不计入回归，修复前必须先重新定性。
