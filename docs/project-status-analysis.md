# AI Gateway 项目现状与问题分析

> 初始分析日期：2026-07-27  
> 当前状态复核日期：2026-07-27  
> 分析分支：`dev`  
> 本文仅记录现状与问题，不包含代码修复。

## 1. 项目定位

`ai-gateway` 是一个基于 Spring WebFlux 的轻量级 AI 模型网关原型，目标是提供 OpenAI 兼容接口，并在网关侧统一处理模型路由、限流、熔断、会话持久化和消息缓存。

目前已经具备以下主要能力：

- OpenAI 兼容的 `POST /v1/chat/completions`
- 流式 SSE 与非流式 JSON 响应
- `GET /v1/models` 模型列表接口
- DeepSeek、Qwen 和本地 Stub Provider
- 基于模型名的可插拔路由策略
- Provider 级限流和熔断
- Session/IP 维度的调用方限流
- MySQL 会话与消息持久化
- Redis 消息历史缓存
- 会话查询、标题修改和删除接口
- Swagger/OpenAPI UI
- 简单的静态测试页面

初始盘点时，项目约包含：

- 42 个生产 Java 文件
- 7 个测试 Java 文件
- 生产代码、测试代码和静态页面合计约 2394 行

整体仍处于 MVP/原型阶段，不应视为已经达到生产可用状态。

## 2. 技术栈

当前工作区配置：

| 领域 | 技术 |
|---|---|
| Java | Java 25 |
| 应用框架 | Spring Boot 4.1.0（工作区未提交修改） |
| Web | Spring WebFlux / Reactor Netty |
| 数据访问 | MyBatis |
| 数据库 | MySQL |
| 缓存 | Redis / Lettuce |
| 容错 | Resilience4j |
| API 文档 | Springdoc OpenAPI |
| 测试 | JUnit 5、Mockito、Reactor Test、H2 |
| 构建 | Maven Wrapper |

需要注意：初始分析时仓库提交版本使用 Spring Boot 3.5.13；当前未提交的 `pom.xml` 已改为 Spring Boot 4.1.0 和 Java 25。因此本文会区分“提交版本问题”和“当前工作区问题”。

## 3. 核心架构

主要请求链路：

```text
客户端
  │
  ▼
CallerRateLimiter
  │  按 X-Session-Id 或 IP 限流
  ▼
ChatController
  ├── 保存用户消息
  ├── 选择流式/非流式模式
  ▼
ModelRouter
  ├── RoutingPolicy 筛选支持目标模型的 Provider
  └── ProviderGuard 过滤熔断中的 Provider
  ▼
ProviderGuard
  ├── Provider 级 RateLimiter
  └── CircuitBreaker
  ▼
ModelProvider
  ├── DeepSeekProvider
  ├── QwenProvider
  └── StubModelProvider
  │
  ▼
上游 OpenAI 兼容 API
```

会话数据链路：

```text
SessionController / ChatController
  ▼
SessionService
  ├── SessionRepository ── MySQL
  └── MessageRepository
        ├── Redis 缓存
        └── MySQL 持久化
```

## 4. 当前做得较好的部分

### 4.1 分层和扩展性

- `ModelProvider` 隔离了不同模型厂商。
- `AbstractOpenAiCompatibleProvider` 复用了 OpenAI 兼容厂商的调用逻辑。
- `RoutingPolicy` 已抽象成接口，便于以后实现成本、延迟、上下文长度或权重路由。
- 限流、熔断、路由和 Controller 的职责边界基本清晰。

### 4.2 WebFlux 与阻塞访问的处理

MyBatis 和 Redis 都属于阻塞式访问。Controller 中的主要数据库操作已经通过 `boundedElastic` 调度，避免直接阻塞 Netty Event Loop，方向正确。

### 4.3 缓存策略

消息历史采用 cache-aside：

- 读取时优先查询 Redis
- 未命中时读取 MySQL 并回填
- 新增或删除消息时主动删除缓存
- 缓存 TTL 为两小时

对当前规模而言，该方案简单且容易理解。

### 4.4 设计过程可追溯

仓库内存在模型路由重构设计和实施计划，Git 提交也按照路由策略、ProviderGuard、调用方限流和 Controller 接入逐步拆分，开发过程相对清晰。

## 5. 当前构建与测试状态

### 5.1 当前工作区状态

复核时 Git 状态为：

```text
## dev
 M pom.xml
?? .github/
```

`pom.xml` 和 `.github/` 都是用户尚未提交的工作，不应被自动覆盖或回滚。

### 5.2 当前 Maven 构建在解析 POM 时失败

执行：

```bash
./mvnw test
```

当前结果：

```text
'dependencies.dependency.version' for
org.springdoc:springdoc-openapi-starter-webflux-ui:jar is missing

BUILD FAILURE
```

Maven 尚未进入编译和测试阶段。原因是升级到 Spring Boot 4.1.0 后，`springdoc-openapi-starter-webflux-ui` 没有显式版本，而当前依赖管理没有为它提供版本。

### 5.3 初始测试结果

在此前 Spring Boot 3.5.13 的工作区状态下执行测试，结果为：

```text
Tests run: 29, Failures: 0, Errors: 17, Skipped: 0
BUILD FAILURE
```

当时错误分为：

1. Spring 上下文无法创建 `ProviderGuard`
2. Java 25 下 Mockito/Byte Buddy 无法动态挂载 Agent

因此即使修复当前 POM，后续仍可能遇到这些问题。

## 6. 问题清单

### P0：当前项目无法构建

#### P0-1 Springdoc 依赖缺少版本

当前 `pom.xml` 将 Spring Boot 升级到 4.1.0，并删除了 Springdoc 显式版本，导致 Maven 无法解析项目。

影响：

- 无法编译
- 无法运行测试
- 无法打包或启动
- CI 无法执行

建议：

- 选择明确支持 Spring Boot 4 的 Springdoc 版本并显式声明；或者
- 在完成兼容性验证前保留 Spring Boot 3.5.x。

不要只为了消除 Maven 报错随意填写版本，应同时验证 Springdoc、MyBatis Starter、Resilience4j 和 Spring Boot 4/Spring Framework 7 的兼容性。

#### P0-2 `.github/ci.yml` 不是合法的 GitHub Actions 工作流

当前 `.github/ci.yml` 的内容实际上是一份 Maven POM XML，而不是 YAML。

影响：

- GitHub Actions 不会将其识别为合法工作流
- 即使修复文件内容，标准工作流目录应为 `.github/workflows/*.yml`
- 项目目前没有可用的自动化构建基线

建议将 CI 文件放到类似：

```text
.github/workflows/ci.yml
```

并配置 JDK 25、Maven 缓存和 `./mvnw verify`。

### P1：应用启动阻断

#### P1-1 `ProviderGuard` 构造器注入不明确

`ProviderGuard` 同时包含生产构造器和包级测试构造器。初始上下文测试中，Spring 没有选择生产构造器，而是尝试寻找无参构造器，导致：

```text
No default constructor found
```

建议明确标注生产构造器，或者把测试构造方式迁移到工厂方法/测试配置，避免组件本身暴露多个候选构造器。

#### P1-2 `CallerRateLimiter` 存在相同模式

`CallerRateLimiter` 同样包含两个构造器。即使先修复 `ProviderGuard`，它也可能成为下一个上下文启动问题。

### P1：Java 25 测试工具链未配置完整

项目采用 Java 25 是合理的，因为它是 LTS，并且 Spring Boot 3.5 支持 Java 25，Spring Boot 4.1 也支持 Java 25。

当前问题并不是 Java 25 本身，而是 Mockito 默认 inline mock maker 依赖运行时动态挂载 Byte Buddy Agent。较新 JDK 对动态 Agent 加载限制更严格，当前测试出现：

```text
Could not initialize inline Byte Buddy mock maker
Could not self-attach to current VM
```

建议：

- 在 Maven Surefire 中显式配置 Mockito Java Agent
- 固定 Maven、Surefire、Mockito 和 Byte Buddy 版本组合
- CI 与本地统一使用同一 JDK 25 发行版
- 不依赖“本地刚好允许动态挂载”的隐式行为

### P1：模型路由语义错误

#### P1-3 未知模型静默回退到 Stub

如果请求模型不存在、Provider 未启用或 Provider 熔断，`ModelRouter` 最终都会返回 Stub Provider。

风险：

- 调用方请求真实模型，却得到固定的 `"ok"`
- HTTP 层表现为成功，掩盖配置错误和生产故障
- 监控难以发现真实 Provider 已不可用

建议区分：

- 未知模型：返回 OpenAI 兼容的 400/404 错误
- 已知模型但 Provider 暂不可用：返回 503
- Stub：只允许显式请求 `model=stub`，或仅在开发 Profile 启用

#### P1-4 模型列表可能展示未真正启用的 Provider

`ModelsController` 从 `GatewayProperties.providers` 枚举模型，再调用 `ProviderGuard.isAvailable(providerName)`。

但配置存在不代表对应 Provider Bean 已成功创建；Resilience4j Registry 又可能为一个新名称即时创建 CLOSED 状态的熔断器。因此没有有效 API Key 的 Provider 也可能被展示为“可用”。

建议让模型列表来源于实际注入的 `List<ModelProvider>`，并结合 Provider 健康状态生成结果。

### P1：Provider 条件装配不稳健

配置中使用：

```yaml
api-key: ${DEEP_SEEK_API_KEY}
api-key: ${QWEN_API_KEY}
```

Provider 使用 `@ConditionalOnProperty` 判断属性是否存在。属性键存在并不等同于 API Key 有效：

- 环境变量未设置时可能导致占位符解析失败
- 某些情况下条件仍可能因配置键存在而匹配
- 空字符串和真实密钥没有得到明确区分

建议：

- 为未配置状态设计明确语义
- 使用 `matchIfMissing = false`
- 校验绑定后的 API Key 是否非空
- 避免在日志和异常中泄露密钥

### P1：缺少输入校验和统一错误协议

`ChatRequest` 没有 Bean Validation。当前代码在记录日志时直接调用 `request.messages().size()`，当 `messages` 为 `null` 时会抛出空指针。

其他缺失校验包括：

- message role/content
- session ID 格式和长度
- 会话标题空值与最大长度
- temperature 范围
- max_tokens 合法范围

错误响应目前主要是空 body 的 500/503，不符合 OpenAI 兼容错误结构。建议增加统一异常处理和标准错误 DTO。

### P1：上游调用缺少超时

WebClient 没有明确配置：

- 连接超时
- 响应超时
- 读取超时
- 整体请求超时

模型 Provider 卡住时可能长期占用连接和请求资源。网关类项目必须明确超时预算，并将超时纳入熔断统计。

### P1：流式错误不能可靠传递

流式响应开始写出后，HTTP 状态和响应头通常已经提交。此时再设置 503 可能无效。

当前通用流错误直接返回空 Flux，客户端可能看到：

- HTTP 200
- 不完整的 SSE 数据
- 没有明确错误事件或 `[DONE]`

建议：

- 在写出首个 chunk 前尽量完成可用性检查
- 写出后以约定的 SSE error event 表达错误
- 明确客户端断连、上游断连和网关错误的处理策略

### P2：Provider 限流错误映射不准确

Provider RateLimiter 拒绝请求时，异常会进入通用异常处理并返回 500。

更合理的语义通常为：

- 429：调用方稍后重试
- 503：上游容量暂不可用

同时应设置适当的 `Retry-After`。

### P2：会话消息持久化存在一致性问题

#### P2-1 助手消息采用 fire-and-forget

助手消息通过内部 `subscribe()` 异步写入：

- HTTP 响应完成不代表消息已持久化
- 应用关闭时可能丢消息
- 保存失败只记录日志，调用方无感知

#### P2-2 用户消息与模型调用不具备完整事务语义

用户消息先保存，再调用上游模型。如果上游失败，会留下只有用户消息的不完整会话。虽然这可能是产品允许的状态，但目前没有显式的消息状态字段来表示失败、生成中或已完成。

#### P2-3 `getOrCreate` 存在并发竞争

当前逻辑是先查询再插入。两个请求并发使用同一个新 Session ID 时，都可能判断不存在，然后其中一个触发唯一键冲突。

建议采用数据库原子 upsert、捕获唯一键冲突后重查，或者调整会话创建协议。

### P2：Redis 是事实上的强依赖

消息 Repository 在 Redis 读取、写入或删除失败时没有降级策略。

结果：

- Redis 故障可能让本应可从 MySQL 读取的会话接口一起失败
- 缓存从性能优化变成了可用性依赖

建议根据业务目标决定：

- Redis 故障时降级到 MySQL，并记录指标；或者
- 明确 Redis 是强依赖，并通过健康检查阻止实例接流量。

### P2：单机限流无法支持集群一致性

调用方限流和 Provider 限流都保存在当前 JVM 内存。

多实例部署后：

- 每个实例都有独立额度
- 总调用量可能达到配置值乘以实例数
- 调用方经负载均衡后可以绕过单实例限制

MVP 阶段可以接受，但生产多实例前需要分布式限流或网关层统一限流。

### P2：数据库约束不足

`messages.session_id` 只有普通索引，没有外键约束。

风险：

- 应用异常或人工操作可能产生孤儿消息
- 删除一致性完全依赖 Service 代码

此外建议评估：

- `(session_id, created_at, id)` 组合索引
- 消息 role 的约束
- 标题和模型字段长度
- 使用 Flyway/Liquibase 管理 schema，而不是手工运行 `schema.sql`

### P2：可观测性不足

项目目前主要依赖普通日志，缺少：

- Actuator 健康检查
- Prometheus/Micrometer 指标
- 每个 Provider 的延迟、成功率和错误率
- 熔断器状态与限流拒绝次数
- Token 使用量和模型成本
- Redis 命中率
- 数据库连接池指标
- 请求 Trace/Correlation ID

对于 AI 网关，可观测性不是附属功能，而是定位模型延迟、限额和成本问题的基础。

### P3：工程化和产品能力缺口

- 没有正式 README 和启动说明
- 没有环境变量示例
- 没有 Dockerfile/Compose
- 没有 API Key 认证
- 没有多租户、配额或计费
- 没有 Provider 故障后自动尝试下一个候选者
- 没有 Controller/API 级集成测试
- 没有数据库迁移工具
- 没有明确的生产、测试、开发 Profile

## 7. 建议修复顺序

### 第一阶段：恢复工程基线

1. 决定 Spring Boot 版本路线：
   - 稳妥路线：Spring Boot 3.5.x + Java 25
   - 前沿路线：Spring Boot 4.1.x + Java 25，并逐项验证生态兼容性
2. 修复 Springdoc 版本，使 Maven 可以解析 POM。
3. 修复 `.github/ci.yml` 的目录和内容。
4. 修复 `ProviderGuard`、`CallerRateLimiter` 构造器注入。
5. 显式配置 Mockito Java Agent。
6. 使 `./mvnw verify` 在本地和 CI 都通过。

### 第二阶段：修复 API 正确性

1. 未知模型不再静默回退 Stub。
2. 模型列表只展示实际启用的 Provider。
3. 修复 Provider API Key 条件装配。
4. 增加请求校验和 OpenAI 兼容错误响应。
5. 正确映射限流、熔断和上游错误状态。

### 第三阶段：提升可靠性

1. 配置 WebClient 超时。
2. 设计流式错误协议。
3. 改善消息持久化一致性。
4. 增加 Redis 降级或明确强依赖策略。
5. 增加 Provider 故障转移。
6. 引入数据库迁移工具。

### 第四阶段：生产化

1. API Key 认证和租户隔离。
2. 分布式限流和配额。
3. Actuator、Micrometer、Prometheus 和链路追踪。
4. Token/成本统计。
5. Docker 和部署文档。
6. 端到端与故障场景测试。

## 8. 总结

项目的领域拆分和架构方向总体合理，尤其是 Provider 抽象、路由策略、限流熔断分层以及 MySQL/Redis Repository 设计，已经具备继续演进的基础。

但当前项目仍处于“重构尚未收口”的状态：

- 当前 POM 无法解析
- CI 文件无效
- 此前应用上下文无法启动
- Java 25 测试 Agent 尚未正确配置
- 模型路由与可用模型展示存在语义错误
- 超时、错误协议、一致性和可观测性尚未达到网关生产要求

当前最有价值的工作不是继续增加 Provider 或新功能，而是先建立一条稳定、可重复的构建和测试基线，再逐步修复 API 正确性与可靠性问题。
