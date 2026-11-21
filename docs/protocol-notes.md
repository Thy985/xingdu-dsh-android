# DSH Web 协议笔记（探针产出）

> **契约来源声明（AGENTS.md 红线 5）**：本文全部协议事实来自本项目探针抓包
> （`protocol/captures/`，由 `protocol/tools/capture_web.py` 采集），原始报文
> 为唯一依据。不采信任何记忆、猜测或第三方客户端代码（含 dsh-go）。
> 每条事实标注捕获来源；`DSH_PROTOCOL_TARGET` 标注适用版本。

**DSH_PROTOCOL_TARGET: 0.2.1-rc.5（本轮探针对象）**
> 注意：当前应用捆绑引擎为 **0.2.1-rc.2**（UPSTREAM.json）。本文结构事实已
> 同时对 rc.2 与 rc.5 双向探针验证；**fixtures 已钉 rc.2**
> （protocol/fixtures/，manifest 标注 DSH_PROTOCOL_TARGET=0.2.1-rc.2，由
> protocol/tools/make_fixtures.py 从捕获脱敏生成）。

## 版本漂移 diff（rc.2 ↔ rc.5，2026-11-21）

页面加载面（两实例同机对照，isolated DSH_HOME）：

- 端点集：完全一致（`$events`、`remoteAccess/follow`、`account/watch`、
  `workspace/follow`、`session/control`）。rc.5 多出的 `session/follow` 为
  前端自动建会话后的跟随行为，非协议差异。
- 流形态：rc.5 独有的 `emit`/`projection`/`snapshot` 均为会话生命周期效果；
  rc.2 侧无任何独有形态。HTTP 面 rc.5 多 `commands/list`、`skills/list`
  （同为会话存在后的前端行为）。
- **结论：mux 信封与首屏端点在 0.2.1-rc.2 ↔ rc.5 间零漂移**——以 rc.2 建立的
  契约对两者都成立。会话内消息流面（M1 真正的重量级部分）仍需交互捕获。

捕获环境：Windows 原生 Node，`dsh web --no-open --host 127.0.0.1 --port 3085`，
无模型配置、隔离 DSH_HOME；Playwright chromium 无头浏览器 + 请求/WS 监听。
原始捕获：`protocol/captures/rc5-page-load/`（token 已脱敏；含宿主路径，**不进 git**）。

## 一、传输层

- 单条多路复用 WebSocket：`ws://<host>:<port>/api/remote.mux`（页面加载即建）。
- HTTP 侧为一组 POST JSON 端点（见第三节），前端首屏同时发起 WS 与 HTTP。

## 二、WS 信封（mux envelope）

客户端 → 服务端（open 一个命名端点流）：

```json
{"type":"open","streamId":"<uuid>","endpoint":"<名称>","payload":{"args":{...}}}
```

服务端 → 客户端（在同一 streamId 上推 item）：

```json
{"type":"item","streamId":"<uuid>","value":{...}}
```

`value` 的形态按端点语义分为：`baseline`（初始全量）、`snapshot`（会话跟随快照，
含 `header.version/id/createdAt`）、`projection`（按 sessionId 的键值状态投递，
观察到的 key 如 `permissions`）、`emit`（事件广播，观察到的 event 如
`api-session/added`）、`upsert`/`order`（工作区列表增改与排序）。

## 三、页面加载时观察到的端点（open 顺序即前端引导顺序）

| endpoint | 服务端首帧 value 语义 | 捕获行参考 |
| --- | --- | --- |
| `$events` | `{"type":"ready","clientId":…,"host":{"home":…}}` | ws.jsonl L6 |
| `remoteAccess/follow` | `{"type":"baseline","owner":true}` | ws.jsonl L9 |
| `account/watch` | 登录状态（观察到 `{"status":"signed-out",…,"links":{usageUrl,topUpUrl}}`） | ws.jsonl L10 |
| `workspace/follow` | `{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":[]}}` → 后续 `upsert`/`order` | ws.jsonl L11/13-14 |
| `session/control` | `{"type":"baseline","value":{"projections":{}}}` → 后续按 sessionId 的 `projection` | ws.jsonl L12/17 |
| `session/follow` | 请求体含 `{"args":{"request":{"address":{"kind":"session","sessionId":…}}}}`；服务端回 `snapshot`（header.version=4） | ws.jsonl L19-20 |

## 四、HTTP POST 端点（首屏观察到的 12 个，全 POST）

`/api/settings/describe`、`/api/credentials/describe`、`/api/session/modelCatalog`、
`/api/agentPresets/list`、`/api/permissionPresets/catalog`、`/api/session/list`、
`/api/workspace/initializeDefault`、`/api/session/create`、`/api/commands/list`、
`/api/skills/list`、`/api/dynamicCordisRunner/{syncInspectManifest,inventory}`

注意：**页面加载即自动 `session/create`**（对应 WS `api-session/added` emit ×2）——
前端行为，非协议要求。

## 五、鉴权

- WS 与 HTTP 均受 URL token 保护：启动输出 `?token=<random>`；无 token 访问
  返回 401（与 Android 端 `DshService` 观察一致）。
- token 出现在 URL query；捕获已脱敏。

## 五·五、交互捕获（首轮，rc.5 @3086，配置模型后）

> 模型调用本身失败（TRANSPORT → 550，6 次自动重试后 turn/end）——凭据已确认
> 配置（credentials/describe: `configured:true, source:"file"`），失败为引擎到
> provider 的传输问题，待与维护者核实其全局 dsh 当前是否可用。失败不影响
> 以下协议事实的成立（均为捕获实证）。

- **消息发送端点**：`POST /api/session/prompt`，body 为 RPC 信封
  `{"type":"client-request","rpcId":"<uuid>","method":"session/prompt","payload":{"args":{"request":{…requestId…}}}}`
- **HTTP RPC 信封（通用）**：请求 `{"type":"client-request","rpcId":…,"method":"<method>","payload":{"args":…}}`；
  响应 `{"type":"server-response","rpcId":…,"result":{"ok":true,"value":…}}`（rpcId 关联）
- **会话转录事件流**（session/follow 流上的 `value.type=event` 事件）：
  `system/message`（含 skills 注入）、`user/message`（seq 递增、content 数组）、
  `assistant/attempt`（turn/step + stream 数组）、`turn/end`（含 error reason）
- **assistant-stream 帧**：`frame.type=start|chunk`，`attemptId="<sessionId>:<n>"`、
  `revision`、`index`、chunk 类型 `finish`（reason.kind=error 时含 code
  TRANSPORT/SERVER + status）
- **自动重试梯子**：失败后 attemptId :1→:6，间隔递增（~2s→4s→6s→8s→15s），
  每次尝试 revision 递增；最终 `emit api-session/error`（args=[sessionId, 消息]）+ `turn/end`
- **凭据查询**：`POST /api/credentials/describe`，refs 数组（如
  `["DEEPSEEK_API_KEY"]`），响应 per-ref `{configured, source:"file", writable}`

## 六、待捕获（M1 探针清单）

1. ~~rc.2 契约抓取~~ ✅ 已完成：fixtures 8 份 + manifest 钉 rc.2（见上）。
2. ~~会话内消息流~~ 首轮完成（发送/流式/事件分类学/重试梯子已捕获，见上）；
   **待模型调用修复后补齐**：工具调用事件、审批请求/响应、steering 注入、正常
   finish 的流完成形态。
3. steering 注入信封（回复中途插话）。
4. 会话 CRUD 全域（list/activate/delete/archive）与 workspace 切换。
5. 终端（`terminal` 类端点）与文件 API 形态。
