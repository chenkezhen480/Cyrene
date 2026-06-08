---
name: git-workflow
description: 当用户需要Git操作指导、分支管理或解决冲突时使用
version: "1.0.0"
tools:
  - web_search
parameters:
  branchStrategy: gitflow
  commitConvention: conventional
---

# Git 工作流规范

## 分支策略

### 主要分支
- `main` / `master`：生产环境代码
- `develop`：开发分支
- `release/*`：发布准备分支
- `hotfix/*`：紧急修复分支

### 功能分支
```
feature/JIRA-123-user-login
bugfix/JIRA-456-fix-null-pointer
```

## 提交规范

### Conventional Commits
```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Type 类型
| 类型 | 说明 |
|------|------|
| feat | 新功能 |
| fix | Bug修复 |
| docs | 文档更新 |
| style | 代码格式（不影响逻辑） |
| refactor | 重构 |
| test | 测试相关 |
| chore | 构建/工具变动 |

### 示例
```
feat(auth): add JWT token refresh mechanism

- Implement token rotation
- Add refresh token endpoint
- Update token validation logic

Closes #123
```

## 常用命令

### 分支操作
```bash
# 创建并切换分支
git checkout -b feature/new-feature

# 查看分支
git branch -a

# 删除本地分支
git branch -d feature/old-feature

# 删除远程分支
git push origin --delete feature/old-feature
```

### 提交操作
```bash
# 暂存修改
git add -p  # 交互式暂存

# 提交
git commit -m "feat: add new feature"

# 修改上次提交
git commit --amend

# 压缩提交
git rebase -i HEAD~3
```

### 同步操作
```bash
# 拉取最新代码
git pull --rebase origin main

# 推送分支
git push -u origin feature/new-feature

# 获取远程更新
git fetch --all
```

## 冲突解决

### 合并冲突
```bash
# 合并分支
git merge feature/branch

# 解决冲突后
git add .
git commit
```

### 变基冲突
```bash
# 变基到main
git rebase main

# 解决冲突后
git rebase --continue

# 放弃变基
git rebase --abort
```

## 最佳实践

1. **频繁提交**：小步快跑，易于回滚
2. **清晰的提交信息**：说明"为什么"而非"做了什么"
3. **保持main干净**：通过PR合并，代码审查
4. **及时同步**：定期rebase/merge主分支
5. **标签管理**：使用语义化版本标签

## 常见问题

### 撤销修改
```bash
# 撤销工作区修改
git checkout -- <file>

# 撤销暂存
git reset HEAD <file>

# 撤销提交（保留修改）
git reset --soft HEAD~1

# 撤销提交（丢弃修改）
git reset --hard HEAD~1
```

### 查看历史
```bash
# 图形化历史
git log --graph --oneline --all

# 查看某文件历史
git log -p <file>

# 查看某次提交
git show <commit-hash>
```
