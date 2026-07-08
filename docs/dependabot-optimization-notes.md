# Dependabot 优化记录

日期: 2026-07-09
项目: hyperscan-java-panama

## 变更内容

1. **新增 `.github/dependabot.yml`**
   - Maven 依赖: 每周一 04:00 (Asia/Shanghai) 检查, 限制 10 个 open PR。
   - GitHub Actions: 每周一 04:00 检查, 限制 5 个 open PR。
   - 不使用 `groups:` 块, 每个依赖单独 PR, 便于 review 和回滚。

2. **新增 `.github/workflows/auto-merge.yml`**
   - 仅对 Dependabot 打开的 PR 生效 (`dependabot[bot]` 或 `app/dependabot`)。
   - 自动合并策略:
     - patch/minor: 自动合并
     - GitHub Actions major: 自动合并 (通常是 Node 运行时升级, 风险较低)
     - Maven major: 留给人工 review (可能破坏构建或 native 编译)
   - 使用 `dependabot/fetch-metadata@v2` 读取更新类型。
   - 使用 `MYTOKEN` (用户 OAuth token, dependabot 命名空间) 执行 `gh pr review --approve` 和 `gh pr merge --auto --rebase`。

3. **仓库设置变更**
   - 启用 `allow_auto_merge`。
   - 为 `main` 分支设置 branch protection:
     - `strict: true` (要求 PR 分支与 base 同步)
     - `required_linear_history: true` (保持线性历史)
     - 要求以下 11 个 CI 检查全部通过:
       - `Build native libs (linux-x86_64-baseline)`
       - `Build native libs (linux-x86_64-avx2)`
       - `Build native libs (linux-x86_64)`
       - `Build native libs (linux-arm64-baseline)`
       - `Build native libs (linux-arm64)`
       - `Build native libs (windows-x86_64-baseline)`
       - `Build native libs (windows-x86_64)`
       - `Package unified native jars`
       - `Test wrapper (linux-x86_64)`
       - `Test wrapper (linux-arm64)`
       - `Test wrapper (windows-x86_64)`
   - 创建 labels: `dependencies`, `java`, `github-actions`。
   - 在 Dependabot secret 命名空间设置 `MYTOKEN`。

## 验证

- `gh api repos/XenoAmess/hyperscan-java-panama --jq '.allow_auto_merge'` → `true`
- `gh api repos/XenoAmess/hyperscan-java-panama/branches/main/protection --jq '.required_status_checks.contexts'` → 列出 11 个检查名称
- `gh secret list --repo XenoAmess/hyperscan-java-panama --app dependabot` → 包含 `MYTOKEN`

## 注意事项

- 由于没有历史 Dependabot PR, 检查名称是从 `build.yml` 的 job `name` 字段推断的。如果实际 check name 与预期不符, Dependabot PR 会卡在 `mergeStateStatus: BLOCKED`, 需要重新运行 GraphQL 查询并更新 branch protection。
- 当前使用用户 OAuth token (`gho_`) 作为 `MYTOKEN`。如果后续 workflow 中出现 `enablePullRequestAutoMerge` 权限错误, 需要换用独立的 classic PAT (带 `repo` + `workflow` scope)。
- 如果大量 Dependabot PR 同时存在, 可能出现 `BEHIND` 竞争; 可通过批量 comment `@dependabot rebase` 或 `@dependabot recreate` 解决。
