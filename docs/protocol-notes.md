# DSH Web 协议笔记（探针产出）

> **契约来源声明（AGENTS.md 红线 5）**：本文全部协议事实来自本项目探针抓包
> （`protocol/captures/`，原始报文为唯一依据；抓包目录不入库）。页面加载面由
> `protocol/tools/capture_web.py` 采集；第七节会话内四链路
> 由 `protocol/tools/capture_interactive.py` 驱动官方前端采集（脚本入库，
> 凭据只从环境变量读取，不写入脚本或抓包）。不采信任何记忆、猜测或第三方客户端代码（含 dsh-go）。
> 每条事实标注捕获来源；`DSH_PROTOCOL_TARGET` 标注适用版本。

**DSH_PROTOCOL_TARGET: 0.2.1-rc.5（本轮探针对象）**
> 注意：当前应用捆绑引擎为 **0.2.1-rc.2**（UPSTREAM.json）。本文结构事实已
> 同时对 rc.2 与 rc.5 双向探针验证；**fixtures 已钉 rc.2**
> （protocol/fixtures/，manifest 标注 DSH_PROTOCOL_TARGET=0.2.1-rc.2，由
> protocol/tools/make_fixtures.py 从捕获脱敏生成）。
> 第七节（会话内四链路）为 **rc.2 单版本交互捕获**，未做 rc.5 对照，
> 该节标注 `DSH_PROTOCOL_TARGET: 0.2.1-rc.2`。

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

## 五·六、交互捕获第二轮（工作区内写入 + pwsh + 审批路径，rc.5 @3087）

**完整 agent 循环解剖**（工作区内写入，自动执行无审批）：

```
POST /api/session/prompt
→ event agent/inbox/spliced（target next-turn）
→ projection inbox（next-turn 内容）
→ event user/message（×3：用户消息 + runtime-context 快照 + skills 注入）
→ event step/start（turn 1 step 1）
→ event request/header（config:{provider:"agnes-local",model:"agnes1/agnes-3.0-flash"}, tools:[…全工具清单]）
→ assistant-stream：start → block-start(blockType) → text-delta →
   tool-call-delta(id,name,argumentsDelta) → block-end(block:{type:"tool-call",…}) →
   usage(inputTokens…) → finish(reason:{kind:"tool-call"}) → end(outcome:{kind:"committed"})
→ event assistant/message（tool-call content）→ event tool/call（callId,name,arguments）
→ event job/registered（jobId "<tool>-<hash>", kind, owner=sessionId）
→ event job/settled（~18ms 后）→ （工具结果进下一 attempt）
→ assistant-stream attempt :2（带工具结果继续）→ event assistant/message（最终文本）
→ event step/end → projection sessionStats（turns/steps/llmMs/toolMs/ttftMs）
→ event turn/end（reason:{kind:"completed"}）
```

- **pwsh 工具**（Windows shell）：tool/call → job/registered → job/settled（18ms）→
  结果回灌；**默认策略（workspace-write）下无审批卡**，直接执行。
  本轮 pwsh 实际失败：桌面 harness 沙箱在 Windows 上设置工作区写 ACL 出错
  （`SetName…`，README 已知桌面隔离限制）——模型如实报告失败。
- **title 生成是独立 LLM 请求**：`session/title-llm-request`（titleProvider:
  session-title-first-prompt-llm，独立 route）→ `session/title`（fallback → provider 两阶段）。
- **projection 家族**（session/control 流）：inbox、turnOutline、title、
  permissions（`{currentValue:"workspace-write"}`）、sessionStats、subagentTiming。

**审批路径（工作区外写入）**：模型调用 **`ask_user_question` 工具**（questions JSON：
id approve-external-write…）→ 服务端推**新帧类型 `waterfall`**：
`{"type":"waterfall","event":"user-questions/request","eventId":…,"agentId":…,"request":{"questions":[…]}}`
——确认流。回答信封（客户端 → 用户问题的响应）待下一轮捕获（在 UI 中点选项并抓帧）。

## 五·七、审批闭环（rc.5 @3087，终局捕获）

**沙箱提权审批**（工作区外写入 → 引擎请求 danger-full-access）：

```
event approval/asked（id, toolName pwsh, callId, reason "escalate sandbox to danger-full-access"）
→ waterfall 帧（event:"approval/request"，eventId, toolName, callId, reason）
→ [UI 审批卡：允许一次 / 拒绝]
→ POST /api/$events/result：{"args":{"clientId":…,"eventId":<waterfall eventId>,
     "outcome":{"kind":"result","value":"allowed-once"}}}
→ event job/registered（pwsh 以 danger-full-access 重执行）→ rows 帧（jobs 表）
→ event job/settled → event tool/result → attempt :3 流 → assistant/message
  （"已创建文件 D:\Projects\Archivepproval-probe.txt"——工作区外落盘成功）
→ stream end committed → event turn/end（reason:{kind:"completed"}）
```

**两种确认流的回答信封对比**（均走 `POST /api/$events/result`）：
- ask_user_question：`outcome.value = {answers:[{id, selected:[…]}]}`（数组形态）
- 沙箱提权审批：`outcome.value = "allowed-once"`（字符串形态）

UI 审批卡文案：等待审批 / 允许本次操作使用 danger-full-access 权限 / 按钮
[拒绝, 允许一次]。**端到端实证：审批 → 提权 → 工作区外文件创建成功**。

> 注：本轮交互捕获在 rc.5 上完成；rc.2 交互面重跑（预期零漂移，首屏面已证）
> 作为 M1 客户端编码前的最后一个确认项。

## 六、待捕获（M1 探针清单）

1. ~~rc.2 契约抓取~~ ✅ 已完成：fixtures 8 份 + manifest 钉 rc.2（见上）。
2. ~~会话内消息流：发送 → 流式响应 → 工具调用事件 → 审批请求/响应~~ ✅
   已完成：见第七节 7.1–7.3（捆绑 rc.2 + OpenAI 兼容模型后端，官方 Web 前端驱动）。
   另有三轮 rc.5 交互实证（工作区写入全链路、pwsh job 模式、waterfall 审批 +
   allowed-once 回答信封 + 提权重执行），见第五·五–五·七节。
3. ~~steering 注入信封（回复中途插话）~~ ✅ 已完成：见第七节 7.4。
4. 会话 CRUD 全域（list/activate/delete/archive）与 workspace 切换。
5. 终端（`terminal` 类端点）与文件 API 形态。

## 七、会话内四链路（DSH_PROTOCOL_TARGET: 0.2.1-rc.2）

> 本节全部事实来自捆绑引擎 **0.2.1-rc.2** 的交互抓包（官方 Web 前端驱动），
> 未做 rc.5 对照。原始报文：`protocol/captures/rc2-stream-message/`、
> `rc2-tool-approval/`、`rc2-steering/`（token/key 已脱敏，目录不进 git）；
> 脱敏 fixture：`protocol/fixtures/{http-session-*,ws-session-*,http-events-result-*}.json`
> （manifest 标注 DSH_PROTOCOL_TARGET=0.2.1-rc.2）。fixture 为完整 WS/HTTP
> 信封（含 `seq`、`usage`、`stream` 等字段），仅 UUID 与时间戳替换为占位符，
> 不是裁剪快照。

捕获环境：

- Windows 原生 Node，`dsh web --no-open --host 127.0.0.1`（端口 3099 / 3103），
  独立 `DSH_HOME`；Playwright 无头 Chromium 操作官方前端，非 mock。
- 模型后端：OpenAI 兼容端点，由 `llm-pi-ai` 插件手工声明路由
  （`api: "openai-completions"`、provider `qiniu`、model `qiniu/conatus-free`），
  密钥仅从环境变量 `QINIU_API_KEY` 读取，**未写入任何被跟踪文件**。
  引擎版本未改动；端点、鉴权、模型名均被接受，且实测支持 `tool_calls`。
- 审批触发方式：本机 Windows 沙箱无法完成 ACL 授予
  （`SetNamedSecurityInfoW … Win32 错误 5`），故由模型按指令在工具参数中携带
  `sandbox_permissions: "danger-full-access"` + justification 触发升级审批。
  审批信封与决策通路与普通审批完全一致，仅审批原因文案不同。
- 观察：pi-ai 适配器在上游返回“completed response with no content”时会发
  `llm/retry` → `llm/retry-started` 并重开 `assistant-stream`；审批与 steering
  捕获中均出现过。协议外形不变，属模型/适配器层行为。

### 7.1 发送消息（HTTP，链路一上段）

前端 → 服务端：`POST /api/session/prompt`，`content-type: application/json`，
body 为 `client-request` 信封（fixture `http-session-prompt-queue-request`）：

```json
{"type":"client-request","rpcId":"<uuid>","method":"session/prompt",
 "payload":{"args":{"request":{"requestId":"<uuid>","sessionId":"session-<uuid>",
 "mode":"queue","content":[{"type":"text","text":"…"}],"clientTimeZone":"Asia/Shanghai"}}}}
```

| 字段 | 语义 | 捕获参考 |
| --- | --- | --- |
| `method: "session/prompt"` | 唯一的发送方法名 | 7.1 请求 fixture |
| `request.requestId` | 客户端生成；后续 `user/message.source.rpcId` 与之一致 | `ws-session-user-message` |
| `mode: "queue"` | 普通发送（空闲态）；繁忙态 Ctrl+Enter 为 `"steer"` | 7.1 / 7.4 |
| `clientTimeZone` | 前端时区，原样回显到 `user/message` | `ws-session-user-message` |
| 响应 `result.value.accepted: true` | 仅表示已受理入队，**不代表模型已应答** | `http-session-prompt-response` |

### 7.2 流式接收完毕（WS，链路一下段）

最小事件序列（seq 为捕获中的会话事件序号，供对照）：

| 形态 | 语义 | 捕获参考 |
| --- | --- | --- |
| `emit api-session/status` `args:[sessionId,true]` | 会话进入 busy | stream capture（emit 帧） |
| `event turn/start` `{turn:1}` | 轮开始 | seq 4 |
| `event step/start` `{turn:1,step:1}` | 步开始 | seq 6 |
| `item assistant-stream` `frame.type=start` | 打开流式尝试：`attemptId="session-<sessionId>:<n>"`、`revision`、`turn/step`、`startedAfterSeq`（捕获为 13） | `ws-session-assistant-stream-start` |
| `assistant-stream` `chunk` `block-start` | 文本块开始 `{index,blockType:"text"}` | stream capture |
| `assistant-stream` `chunk` `text-delta` | 逐 token 增量 `{chunk:{type:"text-delta",index,text}}`，`revision` 递增 | `ws-session-assistant-stream-text-delta` |
| `assistant-stream` `chunk` `block-end` | 整块完成 `{block:{type:"text",text}}` | stream capture |
| `assistant-stream` `chunk` `usage` / `finish` | 用量与结束原因（`reason.kind:"stop"`、`replayState`） | stream capture |
| `event assistant/message` | 提交的助手消息（`surfaceOp:"append"`），含 `usage` 与 `source.replayState.response`：`kind:"pi-ai"`、`version:2`、`api:"openai-completions"`、`provider`、`model`、`stopReason`；`blocks` 列块类型 | `ws-session-assistant-message`（seq 16） |
| `item assistant-stream` `frame.type=end` | 尝试结束 `outcome:{kind:"committed",eventType:"assistant/message",seq}` | `ws-session-assistant-stream-end` |
| `event step/end` → `event turn/end` | 步/轮结束，`turn/end.reason.kind="completed"` | `ws-session-turn-end`（seq 18） |
| `emit api-session/status` `args:[sessionId,false]` | 会话回到空闲 | stream capture |

伴随帧（非最小契约）：`session/title`、`session/title-llm-request`、
`request/header`、`request/context`、`agent/inbox/spliced`、`api-session/activity`，
以及 `projection`（`inbox`、`turnOutline`、`contextBreakdown` 等）。
`attemptId` 的序号 `n` 含 retry 重开次数，正常单次为 `:1`。

### 7.3 批准工具调用（链路二）

| 步骤 | 形态 | 语义 | 捕获参考 |
| --- | --- | --- | --- |
| 1 | `assistant-stream` `chunk` `tool-call-delta` → `event assistant/message` | 助手消息 `content:[{type:"tool-call",id,name,arguments}]`，`arguments` 为 JSON 字符串，`stopReason:"toolUse"` | `ws-session-assistant-tool-call-message`（seq 23） |
| 2 | `event tool/call` | `{turn,step,callId,name,arguments}`，`callId` 来自模型工具调用 id | `ws-session-tool-call`（seq 24） |
| 3 | `event approval/asked` | `{id,toolName,callId,reason}`；UI 弹审批卡「拒绝 / 允许一次」 | `ws-session-approval-asked`（seq 25） |
| 4 | WS `waterfall` `event:"approval/request"` | `{eventId,agentId,request:{toolName,callId,reason,displayReason:{en,zh}}}`；`eventId` 用于回执 | `ws-session-approval-request` |
| 5 | HTTP `POST /api/$events/result` | `client-request`，`payload.args:{clientId,eventId,outcome:{kind:"result",value:"allowed-once"}}`；`clientId` 取自 `$events` ready 帧 | `http-events-result-request` / `-response` |
| 6 | `event approval/decided` | `{id,outcome:"allowed-once"}` | `ws-session-approval-decided`（seq 26） |
| 7 | `event job/registered` → `job/settled` | 工具作业登记与结算（`status:"completed"`） | approval capture（seq 27/28） |
| 8 | `event tool/result` | 工具消息 `{role:"tool",toolCallId,content,isError:false}`，`meta.cwd`，`sourceEventSeqs:[24]`，`surfaceOp:"append"` | `ws-session-tool-result`（seq 29） |
| 9 | 继续第 2 步 | 新的 `step/start` → 流式 → `assistant/message` → `step/end` → `turn/end` | approval capture（seq 31–34） |

要点：

- `approval/asked.id` 是审批记录 id；`$events/result` 用的是 waterfall 的
  `eventId` + `clientId`，二者不可混用（fixture 中分别脱敏为两个占位符）。
- 审批结果值观察到 `"allowed-once"`；UI 取消/拒绝通路本次未捕获。
- 审批不新开 turn：批准后在同一 turn 内进入下一 step。

### 7.4 中途插话 steering（链路三）

| 步骤 | 形态 | 语义 | 捕获参考 |
| --- | --- | --- | --- |
| 1 | HTTP `POST /api/session/prompt` | 与 7.1 同信封，`mode:"steer"`、新的 `requestId`；立即返回 `accepted:true`，**不取消在飞 step** | `http-session-prompt-steer-request` / `-response` |
| 2 | `event agent/inbox/spliced` | `{target:"next-step",start:0,inserted:[{role:"user",content,source:{kind:"user",rpcId:<requestId>,clientTimeZone},id}]}`；消息先暂存，未进会话记录 | `ws-session-inbox-spliced`（seq 22） |
| 3 | 当前流式继续 | 在飞 step 正常提交 `assistant/message` → `step/end` | steering capture（seq 23/24） |
| 4 | `event agent/inbox/spliced`（再次） | `{target:"next-step",start:0,removedCount:1,inserted:[]}`：在 step 边界把暂存消息取出 | steering capture（seq 25） |
| 5 | `event step/start` `{turn:1,step:2}` | 新 step 开始 | `ws-session-step-start`（seq 26） |
| 6 | `event user/message` | 同一 `id`/`rpcId` 正式落盘，`surfaceOp:"append"` | `ws-session-steer-user-message`（seq 27） |
| 7 | `assistant-stream start` → 流式 → `assistant/message` → `step/end` | 新 step 的流式应答，`attemptId` 序号与 `revision` 继续递增 | steering capture（seq 28/29） |
| 8 | `event turn/end` | 全轮**只有一个** `turn/end`（`reason.kind:"completed"`） | `ws-session-steer-turn-end`（seq 30） |

要点：

- 投递发生在 **step 边界**：steer 消息先 `spliced` 到 `next-step`，当前 step
  完成后取出并作为新的 `user/message` 交付，再开 step 2。
- 同一个 `turn` 内完成，`turn/end` 不重复；`user/message.source.rpcId`
  等于 steer 请求的 `requestId`。
- 繁忙态 Enter=queue、Ctrl+Enter=steer 是前端默认策略；协议层只区分
  `mode` 字段，服务端行为与按键无关。
