<!--
模板字段定义见 docs/verification.md「Review 四道门」；红线见 AGENTS.md。
小改动（≤ 一两个文件的修复、文档）可直接提交 main，不必走本模板。
-->

## 目的与关联里程碑

<!-- M0 / M1 / M2 / M3 哪一段？对应 docs/native-ui-plan.md 的哪个条目；修复类写明根因 -->

## 变更区域

<!-- 文件/模块级清单，例如：app/src/main 策略类、mobile-shell/src、protocol/fixtures -->

## 已跑矩阵层

<!-- 见 docs/verification.md。L0/L1 必填；按变更类型补齐"最低验证门槛"表要求的其他层 -->

- [ ] L0 契约测试通过（`node tests/mobile-shell-contract.cjs` 或 CI 链接：）
- [ ] L1 JVM 单测通过（CI 链接：）
- [ ] 其他层（L2 / L3 / L4，按矩阵要求注明）

## 证据

<!-- fixture diff / tests/validation/<版本> 归档更新 / summary.json 变更 / 真机截图 -->

## 高风险区（供人眼检查）

<!-- 红线区命中情况：审批流、URL/Origin 策略、FLAG_SECURE、exec 边界、协议 fixture diff。
     没有命中也要写"无"，说明为什么。 -->

## 对抗性复审

<!-- 由未参与本次变更的干净上下文代理执行（输入：diff + AGENTS.md + docs/verification.md）。
     摘录 findings；未执行则写明原因。 -->

## 回滚方式

<!-- revert 提交范围；涉及 fixture/归档的说明恢复来源 -->
