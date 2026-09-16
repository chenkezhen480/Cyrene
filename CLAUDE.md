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
- Do not restore removed legacy modules (`harness-env`, `harness-preprocess`, `harness-graph`, `harness-ai`, `harness-audit`) or legacy knowledge tools (`knowledge_base_search`, `knowledge_context_read`).
- Never use `git reset --hard` or checkout whole files; the working tree contains intentional uncommitted TODO12 work — preserve it.
