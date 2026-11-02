# Git 协作规范

## 分支
- main 保持稳定；新功能优先使用 `feature/<description>`，修复使用 `fix/<description>`，重构使用 `refactor/<description>`。
- 当前位于 main 且需要修改代码时，先确认分支策略。
- 并行写入任务优先使用独立 Worktree；不得让多个 Agent 无协调地编辑同一文件。
- 不覆盖或丢弃已有未提交变更。

## 提交
- 默认不自动 commit、push、merge，除非用户明确授权。
- Commit Message 推荐 Conventional Commits，如 `feat: add provider concurrency limit`。
- 每次提交保持逻辑聚焦，提交前检查 `git status`、`git diff` 和实际暂存文件。
- 不使用 `git add .` 盲目暂存所有文件。

## 禁止操作
- 未经批准不得执行 `git reset --hard`、`git clean -fd`、force push、rebase 已共享历史或修改他人提交。
- 不提交密钥、token、生产凭据和本地敏感配置。
- 不直接向 main 推送；推荐通过 PR 和 CI 合并。

## 集成与合并
- 主 Agent 负责协调合并冲突、核对最终 diff 和运行回归测试。
- GitHub 分支保护、必需 CI 检查等需在仓库平台实际配置；文档本身不是强制门禁。
