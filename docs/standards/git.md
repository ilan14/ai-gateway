# Git 协作规范

## 分支

- `main` 保持稳定；新功能使用 `feature/<description>`，修复使用 `fix/<description>`，重构使用 `refactor/<description>`。
- 新任务默认从当前分支的 HEAD 创建新分支；用户指定基准分支时，使用用户指定的基准。
- 若当前已经位于本次任务对应的开发分支，可继续使用，不必重复创建。
- 创建分支前必须检查当前分支、工作区和暂存区状态，默认要求工作区干净，包括不存在未提交的已跟踪文件修改和未跟踪文件。
- 工作区不干净时，停止自动开发，报告现有修改，由用户自行处理；不得自动 stash、清理、提交或丢弃这些修改。
- 不得覆盖、删除或强制重建已有分支。
- 并行写入任务应避免文件冲突，不得让多个 Agent 无协调地编辑同一文件。

## 提交与推送

- 默认不自动 commit、push，除非用户明确授权，或用户调用的 Skill 明确包含自动提交和推送流程。
- Commit Message 推荐 Conventional Commits，如 `feat: add provider concurrency limit`。
- 每次提交保持逻辑聚焦，提交前检查 `git status`、`git diff` 和实际暂存文件。
- 仅暂存本次任务相关文件，不使用 `git add .` 或 `git add -A` 盲目暂存所有文件。
- 提交前检查暂存区 Diff，不得包含无关修改、密钥、Token、生产凭据或本地敏感配置。
- 仅允许普通推送到当前开发分支对应的远程分支，不直接向 `main` 推送。
- 推送失败时报告原因，不得通过强制推送、合并、变基或重置历史解决。

## 禁止操作

未经用户针对具体操作明确授权，不得：

- 执行 `git merge`、`git rebase`、`git reset`。
- 执行 `git clean`、`git stash`，或通过 `git restore`、`git checkout -- <path>` 丢弃现有修改。
- 执行 `git push --force`、`git push --force-with-lease`。
- 执行 `git commit --amend` 或其他改写已有提交历史的操作。
- 删除分支、覆盖用户或其他 Agent 的未提交修改。

不得为绕过限制而使用其他等效命令。

## 集成与合并

- Agent 不自行合并分支或向主分支集成代码。
- 功能开发完成后，由用户决定是否发起 PR、合并或进行其他集成操作。
- GitHub 分支保护、必需 CI 检查等需在仓库平台实际配置；文档本身不是强制门禁。