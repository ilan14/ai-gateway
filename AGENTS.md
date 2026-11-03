# AI Gateway

## Project

技术栈：
- Java 25
- Spring Boot / Spring WebFlux
- MySQL / Redis
- Prometheus / Grafana

## 基本原则

- 遵循现有项目结构和代码风格。
- 优先选择简单、清晰、易于维护的方案。
- 聚焦当前需求，避免与任务无关的变更。
- 不为了抽象而抽象。
- 未经充分理由，不得引入新的依赖。
- 复杂业务字段、流程和非直观实现应添加必要注释。
- 不得声称未经实际执行验证的内容已通过验证。
- 不得覆盖用户或其他 Agent 的未提交修改。

## 测试原则

- 新增或修改 Java 业务行为时，必须新增或更新相关测试。
- 纯注释、格式等非行为变更可不新增测试。
- 测试不得修改真实业务数据。
- 不得通过弱化断言、跳过测试或隐藏失败制造通过结果。
- 具体测试策略遵循 `docs/standards/java.md`。

## Standards

执行相关任务时，遵循以下规范：

- [架构与模块边界规范](docs/standards/architecture.md)
- [Java 编码与测试规范](docs/standards/java.md)
- [Java 代码风格规范](docs/standards/java-style.md)
- [Git 协作规范](docs/standards/git.md)

涉及 Git 操作时，遵循 `docs/standards/git.md`。

当具体规范与本文件冲突时，应报告冲突，不得自行选择性忽略。

## 工作流

- 功能开发、复杂重构和多 Agent 协作使用 `feature-development` Skill。
- 简单任务可直接完成，不强制调用 Subagent 或创建完整需求文档。
- 所有任务均须遵守项目基本原则和适用的工程规范。
- 已经用户确认的关键需求和设计决策不得擅自改变。

## 完成标准（Definition of Done）

- 已满足本次任务的验收标准。
- 必要的代码和测试已经实现。
- 相关自动化测试已实际执行并通过。
- 适用的代码格式和静态检查已通过。
- 已检查最终 Diff，不包含无关变更。
- 不存在已知且未处理的阻断性缺陷。
- 必要文档已更新。
- 已汇报修改内容、验证证据和剩余风险。

如因环境限制无法完成必要验证，必须明确说明未验证的部分，不得声称完全完成。