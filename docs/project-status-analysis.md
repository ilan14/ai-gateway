# AI Gateway 已实现功能与后续演进讨论

> 复核日期：2026-10-10
> 分析基线：当前 `main` 工作区的生产代码、配置和测试。
> 本文替换旧版现状分析；未来功能属于讨论建议，不代表已确认的开发需求。未调用真实模型、MySQL 或 Redis 验证生产环境。

## 1. 项目定位与结论

项目已经形成轻量 AI 网关的核心链路：统一文本聊天接口、多厂商适配、流式输出、会话保存、服务端上下文和基础调用监控。适合继续作为接入与联调基础，但完整的协议兼容、访问控制、故障治理和多实例治理尚未完成。

建议近期优先完善接口正确性、超时和会话可靠性；需要向多人或外部系统开放时，认证与会话隔离应作为开放前置条件。后续再根据实际调用数据决定是否投入智能路由、成本管理和更多模型能力。

旧文档中的部分结论已不适用：当前 Springdoc 已声明版本，CI 位于正确目录，Actuator/Prometheus 已接入，助手消息保存已纳入响应链路。旧文档提到的 `RoutingPolicy`、`ProviderGuard`、`CallerRateLimiter` 及 Resilience4j 在当前源码和 POM 中不存在，不能计入已实现功能。

## 2. 技术结构与请求链路

| 层次 | 当前实现 |
| --- | --- |
| 构建 | Maven Wrapper、Java 25、Spring Boot 4.1.1 |
| HTTP | Spring WebFlux、WebClient、Reactor |
| 业务 | ChatService 编排聊天；SessionService 管理会话；ModelService 生成模型列表 |
| 模型接入 | ModelProvider 接口；OpenAI 兼容适配基类；DeepSeek、Qwen、Stub、Mock |
| 存储 | MyBatis + MySQL；StringRedisTemplate 缓存历史消息 |
| 文档与监控 | Springdoc、Actuator、Micrometer、Prometheus |
| 测试 | JUnit、Mockito、Reactor 测试工具、H2 |

正常聊天链路：`ChatController → ChatService → ModelRouter → ModelProvider`。ChatService 调用 SessionService 维护会话和消息，阻塞存储操作通过 `boundedElastic` 调度。

Mock 链路：`ChatController → ChatService → MockModelProvider → mock-http-server`，绕过会话和存储操作，但应用启动仍装配存储相关组件。

## 3. 已实现功能及边界

### 3.1 对外接口

| 接口 | 已实现行为 | 边界 |
| --- | --- | --- |
| `POST /v1/chat/completions` | 非流式 JSON；流式 SSE；返回会话 ID | 当前主要支持文本 Chat Completions 的部分字段 |
| `GET /v1/models` | 返回 stub、mock 和配置中的厂商模型 | 来源于配置，不代表密钥有效、Provider 已启用或上游健康 |
| `GET /v1/sessions` | 按最近活跃时间倒序列出会话 | 无分页、用户筛选或权限隔离 |
| `GET /v1/sessions/{sessionId}` | 返回会话信息及完整消息历史；不存在返回 404 | 全量读取 |
| `PUT /v1/sessions/{sessionId}/title` | 修改标题；成功 204，不存在 404 | 缺少完整标题校验 |
| `DELETE /v1/sessions/{sessionId}` | 删除消息、缓存和会话；数据库删除使用事务 | Redis 操作不属于数据库事务 |
| `/actuator/health`、`/actuator/prometheus` | 配置暴露健康和指标端点 | 未验证真实部署的健康状态、采集和告警 |

ChatRequest 显式建模 `model`、`messages`、`stream`、`temperature`、`max_tokens` 和网关扩展字段 `context_mode`。Message 的 content 是字符串；尚无工具调用、多模态内容、结构化输出和 Responses 等接口实现，不能称为完整 OpenAI API 兼容。

### 3.2 厂商接入和模型路由

- DeepSeek 和 Qwen 复用 `AbstractOpenAiCompatibleProvider`，通过 WebClient 发起请求，支持 JSON 和 SSE；模型名、地址和密钥来自配置。
- 配置包含 deepseek-chat、deepseek-reasoner、qwen-flash、qwen-plus、qwen-max；这些是配置声明，未实测上游可用性。推理模型名称存在不代表推理内容字段已经完整适配。
- ModelRouter 根据模型名寻找第一个支持它的 Provider。未指定模型时使用配置中的默认模型；找不到匹配 Provider 时返回 Stub。
- Stub 本地返回固定 `ok`，支持流式和非流式，用于连通性验证。
- Mock 显式通过 `model=mock` 选择，转发至独立 mock-http-server。延迟、错误和响应内容由上游 Mock 服务管理，网关没有场景管理接口。

当前不包含权重路由、熔断、限流、自动重试和故障转移。路由器注释提到默认模型回退，但显式未知模型的实际代码直接回退 Stub。

### 3.3 会话与上下文

- 普通聊天未传或传空 `X-Session-Id` 时生成 UUID，并在响应头返回；传入非空值时要求规范 UUID 且会话已存在，分别以 400/404 拒绝非法值或不存在的会话。
- `context_mode` 省略或为 `client` 时，使用客户端提交的 messages，不自动拼接历史。
- `context_mode=server` 时，只接受一条非空 user 消息，从存储读取历史并拼接本轮输入；网关扩展字段不传给真实 Provider。
- 保存本轮最后一条 user 消息和第一条 choice 的助手文本，不完整归档客户端提交的所有角色和消息；system 等信息也不会自动保留到后续服务端上下文。
- 首条 user 内容用于生成标题，超过 20 个 Java 字符单位截断并加省略号；每次追加消息刷新会话活跃时间。
- 非流式在助手保存结束后返回响应。普通流式累积首条 choice 的 delta.content，收到 `[DONE]` 后停止上游，保存助手内容，再向客户端发送 `[DONE]`。
- 普通流式遇到非法 JSON、错误内容或缺少 `[DONE]` 会失败；取消或失败不会保存当前部分助手文本，空白助手内容也不保存。
- Mock 忽略会话头且不返回该响应头，不执行上述历史拼接、完成标志校验和持久化流程。

### 3.4 数据存储和缓存

MySQL 保存 sessions/messages；消息按 created_at、id 升序读取，会话业务 ID 有唯一索引。数据库初始化 SQL 作为参考文件，配置禁止自动初始化。

历史查询使用 cache-aside：Redis 命中直接返回，未命中查询 MySQL 并缓存两小时；新增或删除消息后清除缓存。当前没有 Redis 故障降级、并发回填一致性保障、消息分页和数据库迁移机制。

### 3.5 监控、联调与工程化

- 真实 Provider 已统计开始次数、结束结果、在途调用数、总耗时、流式首帧耗时，按 provider/model/stream 区分；结果区分 success/error/cancelled。
- 模型指标使用配置内模型或 unknown 标签，避免任意模型输入产生无限标签；总耗时不包含历史读取和回复落库，首帧不等于首次有效文本。
- Mock 有独立调用指标；Stub 不产生真实下游调用指标。详细查询见 [接口与模型调用监控](模型调用监控.md)。
- 静态页面支持加载模型、输入消息、流式/非流式回复以及服务端上下文续聊；不是完整会话管理后台。
- 已有 OpenAPI 配置、Docker Compose 和 GitHub Actions 工作流。Compose 的 MySQL/Redis 使用外部服务，部署是否能正常启动未在本次验证。
- 测试涉及 ChatService、ChatController、SessionService、Provider 指标、Mock Provider 和 Mapper；Mapper 使用 H2。测试覆盖不等于已经通过真实厂商或完整生产环境验证。

## 4. 近期需要优先补齐的问题

| 优先级 | 当前缺口 | 影响和建议 |
| --- | --- | --- |
| P0（对外开放前） | 没有认证与会话归属 | 持有 ID 即可访问会话，列表返回所有会话；至少建立网关 API Key、身份归属和查询权限 |
| P1 | 未知模型静默回退 Stub | 真模型拼写错误可能仍返回成功；显式未知模型应报错，Stub 只允许显式选择或开发配置 |
| P1 | 默认模型语义不一致 | 路由使用配置默认值，保存会话时 null 模型写 stub，上游仍接收原 model；在 Service 统一解析最终模型并用于路由、持久化和转发 |
| P1 | 校验和错误协议不足 | client 模式 messages、role/content、temperature/max_tokens、标题缺少完整校验；增加统一错误结构和上游状态映射 |
| P1 | 缺少明确上游超时 | 配置连接、首帧、流间隔和整体预算；响应开始后用明确的 SSE 错误约定通知客户端 |
| P1 | 模型列表与启用状态不一致 | 从实际 Provider 和模型配置交集生成列表；明确未设置/空密钥时的装配行为 |
| P1 | 会话并发与失败状态不明确 | getOrCreate 是先查后插；同会话并发可能乱序；请求失败会留下用户消息，需要轮次、状态或顺序控制 |
| P1 | 缓存影响核心可用性 | Redis 故障可能直接阻断读写；增加降级，并处理失效后旧查询回填造成的过期历史 |
| P2 | 无限历史增长 | 全量查会话/消息、全量拼上下文，成本和内存随历史增长；增加分页及上下文预算 |
| P2 | 工程验证范围不足 | 补充 ModelRouter、ModelService、Repository 和 HTTP 级错误测试；完善启动配置验证 |

这里的 P0/P1 是建议推进顺序，不代表已确认开发范围，也不代表未经运行验证的故障已经发生。

## 5. 未来可考虑实现的功能

### 5.1 接口可靠性与故障治理（建议优先）

**目标：** 让调用方明确区分参数错误、上游失败和成功回复。

**实现思路：** Controller 保留基本校验，Service 统一模型解析；增加统一异常映射、请求 ID、Provider 超时预算。先支持单 Provider 的可预测行为，再引入并发上限、限流和熔断。

**验收：** 未知模型不返回 ok；缺失 messages 返回明确 400；上游 429/5xx 和超时有约定结果；流开始前后错误分别有测试；取消后在途计数恢复。

**讨论：** 流式重试只能在尚未输出内容时考虑，输出后切换 Provider 可能重复或混合回答。不要为获得更高成功率无条件重试，避免额外费用。

### 5.2 认证、会话隔离与配额（开放前必需）

**目标：** 多人使用时隔离数据并控制滥用。

**实现思路：** 从网关 API Key 和用户/项目归属开始，Provider 密钥仅在服务端管理；全部会话查询和写操作校验归属。增加请求速率和并发限制，需要多实例后再考虑共享额度。

**验收：** 未认证请求被拒绝；用户不能列出、读取、修改或删除其他用户会话；越权失败不泄露内容；限流有稳定响应。

**讨论：** 自用网关可先做单用户身份，团队服务再扩展租户。Token 配额需区分请求预估与最终结算，不能直接把 QPS 当成成本控制。

### 5.3 会话产品能力与上下文预算（建议第二阶段）

**目标：** 支持长期、多轮对话，避免历史无限增长。

**实现思路：** 会话和消息分页、搜索、归档、导出；为每轮建立顺序和生成状态，限制同会话并发。服务端上下文保存 system 指令，按模型预算保留最近消息；必要时再增加摘要。

**验收：** 分页稳定无重复；长会话上下文不会超过设定预算；中断/失败状态可查询；并发不会污染轮次；上下文裁剪行为可复现。

**讨论：** 先做确定性裁剪，摘要会额外调用模型并可能丢信息；继续讨论是否保存失败回复、是否允许切换会话模型和恢复生成。

### 5.4 Token、成本和告警（按实际运营需求）

**目标：** 解释费用来源，发现模型性能下降。

**实现思路：** 在现有指标上增加有效内容首帧、Token 用量、错误分类、缓存命中率和持久化耗时；形成仪表盘及错误率/延迟告警。记录价格版本，区分上游真实 usage 与估算值。

**验收：** 每个请求可关联 Provider、结果和用量；缺失 usage 不伪装为零费用；计费明细可核对；监控标签不包含用户 ID 或会话 ID 等高基数字段。

**讨论：** 流式 usage 需先完善协议字段支持。技术监控与账务记录分开，Prometheus 计数不作为精确账单。

### 5.5 多 Provider 路由与故障转移（有冗余供应商后）

**目标：** 在成本、延迟和可用性之间选择合适上游。

**实现思路：** 先做逻辑模型别名、显式候选顺序和健康过滤，再根据指标增加权重或成本策略。逻辑模型映射到各 Provider 的真实模型名，能力不兼容时不得自动替换。

**验收：** 路由选择可追溯；真实模型名正确转发；无可用候选返回明确错误；流已输出后不自动切换；不会回退 Stub 掩盖故障。

**讨论：** 当前按名称取第一个 Provider 无法表达稳定优先级。只有存在可替代供应商及实际收益时再抽象策略接口。

### 5.6 更完整的模型能力（业务驱动）

| 候选能力 | 价值 | 前置条件与取舍 |
| --- | --- | --- |
| 工具调用 | 支持 Agent 和外部操作 | 扩展 tool_calls/tool 消息及流式增量；先透传协议，工具执行需要独立权限边界 |
| JSON/结构化输出 | 支持信息提取和业务集成 | 定义 response_format/schema；验证各 Provider 差异 |
| 多模态输入 | 图片理解等场景 | content 改为文本/内容块模型；增加大小、类型和资源访问限制 |
| Embeddings | 支持语义检索基础能力 | 独立接口、响应和模型目录；不必同时引入完整 RAG 平台 |
| Responses 接口 | 扩展客户端接入范围 | 作为独立协议适配，明确与现有会话管理的边界 |
| 更多 Provider | 扩大可选模型范围 | 先完善协议契约和模拟测试，避免仅增加配置即声称完整支持 |

这些能力可以分开落地，无需一次性完成。RAG、工作流编排、Agent 执行平台和商业计费后台暂不建议并入核心网关，除非已经有明确业务需求。

### 5.7 运维和配置管理（部署规模驱动）

可考虑开发/测试/生产 Profile、配置启动校验、数据库版本迁移、脱敏审计和数据保留策略。Provider 配置后台与热更新先确认运维频率，低频变更继续使用配置文件即可。

验收应包括：缺少密钥时启动行为明确；升级与回滚可演练；测试只访问 H2/mock；日志不记录密钥和完整敏感消息；备份恢复经过验证。

## 6. 建议推进路线与下一次讨论

1. **阶段一：接口和可靠性。** 统一模型语义、模型列表、输入校验与错误协议，补齐超时和故障测试。对外开放则同时完成认证和会话隔离。
2. **阶段二：会话体验。** 实现轮次状态、并发顺序、分页、上下文预算与 Redis 降级。
3. **阶段三：运营治理。** 按数据补充用量统计、告警、配额；有替代供应商后实现故障转移。
4. **阶段四：业务扩展。** 选择工具调用、结构化输出、多模态或新接口中真正需要的一项。

建议下一次需求讨论先确定：网关服务自用还是团队/公网；是否默认由服务端管理上下文；最近最突出的问题是稳定性、费用还是功能缺失；是否有可替代的真实 Provider。确认后为选定功能另写技术方案，再实现代码，避免把候选清单直接当成开发承诺。

## 7. 本次验证与证据索引

本文仅更新分析文档，不修改业务代码、配置或 README。运行现有单元测试用于复核基线，不新增与文档文字绑定的测试。存储测试使用现有 H2 配置，服务测试使用 mock。

主要证据：

- 接口：`src/main/java/com/lava/ai_gateway/controller/`
- 编排与上下文：`src/main/java/com/lava/ai_gateway/service/ChatService.java`
- 会话行为：`src/main/java/com/lava/ai_gateway/service/SessionService.java`
- 模型列表与路由：`ModelService.java`、`router/ModelRouter.java`
- 协议及监控：`provider/`、`model/`
- 缓存：`repository/impl/MessageRepositoryImpl.java`
- 数据结构：`src/main/resources/schema.sql`、`mapper/`
- 配置与工程：`pom.xml`、`application.yaml`、`compose.yaml`、`.github/workflows/ci.yml`
- 测试：`src/test/java/com/lava/ai_gateway/`、`src/test/resources/application-mapper-test.yaml`

首次执行 `./mvnw test`：42 项测试，0 项断言失败、16 项错误，构建失败；错误来自 Mockito inline mock maker 无法动态挂载 Agent。该结果是本次环境的实测，不能沿用旧文档的 POM 解析失败结论。随后执行 `./mvnw test -DargLine=-javaagent:/Users/mingjie/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar`：42 项测试全部通过，0 失败、0 错误、0 跳过，未修改 POM。Agent 路径为本机依赖缓存，其他环境需要使用各自实际路径；建议后续在构建中统一 Agent 配置。
