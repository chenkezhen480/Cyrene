# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Authoritative Instructions

**Read `AGENTS.md` first** — it is the authoritative, detailed guide for this repo (build/verification commands, module boundaries, design rules, unified knowledge architecture, key entrypoints). Do not duplicate or contradict it; when code and docs disagree, the code wins.

## What This Is

Cyrene Agent — a Java 21 (Maven, multi-module) framework for building vertical-domain agents on top of existing business systems. Core is an autonomous ReAct loop, not a fixed workflow:

```
Provider -> Input -> immutable RunToolCatalog snapshot -> ReAct Loop -> Trace -> response
```

Module dependency chain (one-way, do not violate): `core -> provider/input/tool/react/trace -> agent -> server`.

Local filesystem and command tooling lives in `harness-tool/.../tool/filesystem/` and `.../tool/shell/`. The six filesystem tools are published to the model as one tool, `code_workspace`, dispatched on an `action` argument; the implementations stay separate, and the project-discovery scan still registers them under their own names in its own registry. `shell` is published on its own because its confirmation and refusal model is its own. Reach differs per action, and that difference is the design:

| Action | Reach |
|---|---|
| `read` `glob` `grep` `tree` | any path the process can read (`HARNESS_CODE_READ_SCOPE`) |
| `edit` `write` | `HARNESS_CODE_AGENT_ROOT` + `HARNESS_CODE_BACKEND_ROOTS` only |
| `shell` (separate tool) | independent `CommandPolicy`; no shell, argv only |

## Commands

```bash
# Full test suite
mvn clean test -DskipITs

# Subset of modules
mvn test -pl harness-agent,harness-server -am -DskipITs

# Single test class
mvn test -pl harness-agent -am -Dtest=KnowledgeGraphToolTest -Dsurefire.failIfNoSpecifiedTests=false -DskipITs

# Package & run (version comes from <revision> in root pom.xml)
mvn clean package -pl harness-server -am -DskipTests
java -jar harness-server/target/harness-server.jar

# Docker stack (mysql, milvus, redis, searxng, document-parser, ...)
docker compose --env-file .env -f docker/docker-compose.yml up -d --build
```

Before committing: run scope-appropriate tests, `git diff --check`, and `node --check` on changed JS under `harness-server/src/main/resources/public/js/`.

## Non-Negotiable Invariants

- All runtime config is `HARNESS_*` keys declared in `EnvKey.java`; update `.env.example` when adding config. Never hard-code ports, endpoints, credentials, timeouts.
- Tenant/credentials/knowledge/graph scope comes from trusted `context.*` at the server boundary — never from model tool arguments.
- Each Agent run uses one immutable `RunToolCatalog` snapshot; filtering is `excluding`/`allowing` on the snapshot, never per-request registry mutation.
- Growing lists use cursor pagination with the shared `PageResponse<T>` contract (`items` + `pageInfo` with `limit + 1` semantics).
- The current version lives only in root `pom.xml` `<revision>`; do not bump or edit release notes unasked.
- Tool permission and path permission are two separate layers and must stay separate: `ToolPermissionService` answers "does this tenant + identity have this tool" (by tool name), `FileSystemAccessPolicy` answers "may this path be touched". Neither may absorb the other's job.
- The permission store keeps one name per tool, so `code_workspace` is denied whole and the admin page shows it as one row. A denied action must leave the model's `action` enum rather than fail at execution; `RunToolCatalog.excluding`/`allowing` additionally accept `code_workspace.<action>`, which is how a sub-agent allowlist grants a single action.
- Pre-merge tool names (`read`, `edit`, `write`, …) are rewritten to the tool that now owns them — `CodeWorkspaceTool.ownerOf` for the permission store, `modernize` for an allowlist. Removing that rewrite is a silent permission widening: a stored `edit` would stop matching anything and hand back access a tenant had been denied.
- Writable roots come only from trusted server config. `project-apis.json.projectRoot` is never a write source. Resolve paths with `toRealPath()` — `normalize().startsWith()` is not sufficient, and on Windows `Files.isSymbolicLink` is false for junctions.
- `shell` never delegates to a shell: `command` and `args` reach `ProcessBuilder` as a list, so `;` `|` `>` `$(...)` are not syntax. Shell executables, a `command` containing a path separator, and log-following are hard-refused; anything else matching no rule is put to the operator. `CommandPolicy` therefore decides *whether to ask*, not *whether it is allowed* — do not treat it as a security boundary.
- Search has one complete default implementation (NIO) and one opt-in accelerator (ripgrep via `HARNESS_CODE_RG_PATH`). Both backends must return the same answer for the same input; CI installs ripgrep to keep that true, so a change to one backend must not change its answers.
- `glob` pages by cursor ordered on the path relative to the search root — never by offset, which shifts when the tree changes. Paging through must concatenate to exactly the unpaged result.
- Do not restore removed legacy modules (`harness-env`, `harness-preprocess`, `harness-graph`, `harness-ai`, `harness-audit`), legacy knowledge tools (`knowledge_base_search`, `knowledge_context_read`), or the superseded `code_glob` / `code_grep`.
- Console pages are wrapped in `<keep-alive>`, so navigating away fires `onDeactivated`, not `onUnmounted`. Anything holding a stream, timer or subscription must release on both, or it leaks for the rest of the session. The right-hand test dock sits outside that container, so its own `onUnmounted` does run.
- Never use `git reset --hard` or checkout whole files; the working tree contains intentional uncommitted TODO12 work — preserve it.
