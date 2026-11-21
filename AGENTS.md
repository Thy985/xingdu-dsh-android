# AGENTS.md — 星渡 DSH Android 工程守则

本文件约束所有在本仓库工作的 AI 代理与贡献者。规则与测试冲突时以测试为准，
规则与文档冲突时以本文件为准。任何规则修改必须显式提交并说明原因，不允许
在普通功能提交中"顺手"改守则。

## 项目边界

- 非官方 DSH Android 载体；单维护者项目；不做产品差异化竞争。
- 路线以 [docs/native-ui-plan.md](docs/native-ui-plan.md)（原生 UI，M0–M3）与
  [ROADMAP.md](ROADMAP.md) 为准，不自行扩大范围。
- 上游以 [UPSTREAM.json](UPSTREAM.json) 为准。升级引擎版本 = 先重跑协议探针、
  diff fixture，再动任何代码（见 [docs/verification.md](docs/verification.md) 协议闭环）。

## 红线（违反即回滚，不讨论）

1. **不绕过审批、不添加无约束 shell、不放宽 WebView/URL 策略。**
   `LocalOriginPolicy` / `RemoteUrlPolicy` / `UrlPolicy` / `ZipPathPolicy`
   必须 fail-closed：策略缺失、输入异常时拒绝，不放行。
2. **mobile-shell 只用官方插槽**（`shell.overlay` / `settings.action`）与布局服务；
   禁止 DOM 重挂、原生桥（`addJavascriptInterface`）、合成点击、`innerHTML` 写入。
   `tests/mobile-shell-contract.cjs` 强制此约束。
3. **`app/src/main/assets/runtime.zip` 不进 git。** Gradle 对运行时缺失 /
   SHA-256 不匹配的构建失败是产品门禁，不许注释掉、跳过或降级为警告。
4. **许可立场：AGPL-3.0（2026-11-21 维护者决定，LICENSE 已同步）。** 本项目
   以 AGPL-3.0 发布；允许阅读/复制 dsh-go 及其 `dsh_client` 等源码作为实现
   参考，引用处必须保留版权与许可声明。协议事实仍只允许从探针抓包进入
   `docs/protocol-notes.md` 与 fixtures（与红线第 5 条一致）：第三方源码可作
   实现参考，不得作为协议契约的来源。
5. **协议锁定。** 客户端与 fixture 标注 `DSH_PROTOCOL_TARGET`；协议细节只从
   探针报文（`docs/protocol-notes.md`，M0 产出）进入代码，不从猜测、记忆或
   第三方代码进入。
6. **仪器测试只在专用测试设备执行。** 不在承载真实数据、真实引擎或真实
   凭证的设备上运行 androidTest。

## Review 四道门

变更合入按顺序过四道门，详细定义见 [docs/verification.md](docs/verification.md)：

1. **机器门**：L0 契约 + L1 单测在 PR 上绿；红了不进入人的视野。
2. **对抗性复审**：由未参与本次变更的干净上下文代理会话执行，输入只有
   diff、本文件与 verification 矩阵；findings 贴到 PR。
3. **人工检查**：只看四处——红线区（审批/URL 策略/FLAG_SECURE/exec 边界）、
   协议 fixture diff、意图与范围、真机行为抽查。其余代码不逐行读。
4. **合并权**：只有维护者本人 merge。代理永远不执行 merge。

## 仓库地图

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/.../dshandroid/` | Java 壳层：DshService、策略类、SAF 传输、屏幕隐私、运行时安装 |
| `app/src/test/` | JVM 单测（CI 强制层，见 verification 矩阵 L1） |
| `app/src/androidTest/` | 真机仪器测试（专用设备专用） |
| `mobile-shell/src/` | 移动适配插件源码；`lib/` 是构建产物，改 `src/` 后必须同步 assets（契约测试校验） |
| `app/src/main/assets/mobile/mobile.js` | 原生注解适配器，fail-closed：上游 shell 契约变化即停用 |
| `protocol/`（规划中，M1 建） | DshApiClient 纯 Kotlin 模块 + `fixtures/` |
| `scripts/runtime/` | 运行时构建与校验 |
| `tests/validation/<版本>/` | 证据归档（只放证据，不放规则） |
| `docs/verification.md` | 六层验证矩阵、闭环、review 门、summary.json schema |
| `docs/native-ui-plan.md` | 原生 UI 路线（M0–M3 按周计划） |

## 构建与测试命令

从 git checkout 构建**必须先恢复 runtime.zip**（仓库不含该资产）：

```sh
unzip -p ../Xingdu-DSH-<版本>-dev-source.zip \
  "*/app/src/main/assets/runtime.zip" \
  > app/src/main/assets/runtime.zip
python3 scripts/runtime/verify-runtime.py app/src/main/assets/runtime.zip --arch aarch64
```

<!-- 注意：发布源码包的顶层前缀不固定（曾有 dsh-android-native/ 与
     xingdu-dsh-android/ 两种），按后缀通配匹配，不要写死前缀。 -->

一键本地快速验证（L0 + L1 + lint）：

```sh
scripts/verify-local.sh            # 全量快速层
scripts/verify-local.sh --no-lint  # 只跑契约 + 单测
```

分层入口（何时用哪一层见 docs/verification.md 矩阵）：

```sh
# L0 契约：插件身份、插槽所有权、禁止 DOM 手术
node tests/mobile-shell-contract.cjs

# L1 JVM 单测 + lint + 构建
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest

# L3 仪器测试（仅专用测试设备）
./gradlew connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.zhanxing.dshandroid.ScreenPrivacyTest

# L2 主机端 UI 回归（需临时官方 DSH 实例与 Playwright，见 README）
PLAYWRIGHT_MODULE=/path/to/playwright python3 scripts/test-mobile-ui.py
```

## 提交与 PR

- 提交信息沿用现有前缀风格：`feat:` / `fix:` / `refactor:` / `docs:` /
  `test:` / `ci:` / `test+docs:`；版本发布用 `Publish X.Y.Z-dev ...`。
- 小改动（≤ 一两个文件的修复、文档）可直接提交 main；里程碑切片
  （M0 迁移、M1 协议客户端、M2 UI）必须走 PR，模板见
  [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md)。
- 代理完成工作后运行 `scripts/verify-local.sh` 自检，再交付 diff 与
  高风险区说明；不自行 merge。

## 验证纪律

- 任何行为变更至少落到 verification 矩阵的一层；**"完成"的定义 = 对应层绿
  + 归档更新**，不承认其他含义的"完成"。
- README 验证状态表只从当版 `tests/validation/<版本>/summary.json` 抄写，
  不手写新结论；未执行/未验证项不得写成通过；**历史验证结论不自动延续到
  新版本**。
- 每里程碑打 tag + 归档 `tests/validation/<版本>/`；failed / environment /
  unverified 逐条回灌进下一版本 checklist 骨架（闭环第 6 步，见
  docs/verification.md）。

## 环境备注（防止把已知差异当 bug"修"）

- 开发机 Windows + Git Bash；JDK 17+、Android SDK 36（compileSdk/targetSdk 0.2.0 起为 36）、Python 3；
  `local.properties` 配置 `sdk.dir`。
- **Windows 本地 JVM 单测 57/58 是已知状态**：差异为依赖 POSIX FIFO 的
  既有用例，CI（Linux）58/58。不要为让本地全绿而改该用例。
- **真机华为 DBR-W00（Android 12 / EMUI）已知环境因素**：androidx 生命周期
  测试框架 2 项不可用、软键盘不触发窗口缩放、WebView 错误页呈现差异。
  这些已定性为环境/测试框架因素（见 tests/validation/0.1.9/），不是待修
  缺陷，除非上游框架行为变化并重新定性。
