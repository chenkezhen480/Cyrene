# Skills 目录

此目录用于存放 Skill 文件，供 Cyrene Agent 加载使用。

## 什么是 Skill？

Skill 是可复用的能力包，定义了 LLM 在特定场景下的操作规范：
- **System Prompt**：定义 agent "是什么"（身份、角色）
- **Skill**：定义"怎么做"（特定场景的技能规范/操作流程）
- **MCP Tools / Builtin Tools**：实际的工具实现

## 文件格式

每个 Skill 是一个 Markdown 文件，包含 YAML frontmatter：

```markdown
---
name: my-skill
description: 何时使用此技能的描述
version: "1.0.0"
tools:
  - web_search
  - code_execution
parameters:
  key: value
---

# 技能标题

## 操作步骤

1. 第一步
2. 第二步
...

## 注意事项

...
```

### 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `name` | 是 | Skill 唯一标识 |
| `description` | 是 | 何时使用此 Skill |
| `version` | 否 | 版本号，默认 "1.0.0" |
| `tools` | 否 | 绑定的工具列表 |
| `parameters` | 否 | 默认参数 |

## 使用方式

### 1. 启动时加载

Agent 启动时会自动扫描此目录，建立索引：

```bash
# 默认目录 ./skills
java -jar harness-server.jar

# 自定义目录
HARNESS_SKILL_DIR=/path/to/skills java -jar harness-server.jar
```

### 2. LLM 调用

LLM 在对话中可以通过工具调用使用 Skill：

```
用户：帮我审查这段代码的安全性

LLM：我来加载代码审查规范...
→ load_skill(name="code-review")

LLM：根据审查规范，我将检查...
→ search_skill(skill="code-review", query="安全审查")
```

### 3. 上传临时 Skill

用户也可以通过聊天上传 `.md` 文件作为临时 Skill（仅当前会话有效）。

## 内置示例

| Skill | 用途 |
|-------|------|
| `code-review` | 代码审查规范 |
| `data-analysis` | 数据分析流程 |
| `api-design` | API 设计规范 |
| `git-workflow` | Git 工作流指导 |

## 最佳实践

1. **描述清晰**：`description` 应说明"何时使用"，而非"是什么"
2. **工具绑定**：只绑定必要的工具，避免 LLM 滥用
3. **结构化内容**：使用标题、列表、代码块组织内容
4. **适当长度**：太短无法指导，太长浪费 token
