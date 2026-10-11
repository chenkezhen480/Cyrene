-- ============================================================
-- Cyrene Agent - MySQL 统一建表脚本
-- Database: agent（Docker Compose 部署由 MYSQL_DATABASE=agent 自动选择；
--           手动执行前请先创建并选中数据库，例如：
--           CREATE DATABASE IF NOT EXISTS `agent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;）
-- 包含：审计追踪、会话、消息、子 Agent 任务、用户、知识权威层、API/工具禁用、图谱租户绑定。
-- 新库建表与旧库增量升级统一在本文件，升级语句集中于末尾，可重复执行。
-- 仅在选中的目标库执行，不为旧用户自动分配 tenant_id/identity；MySQL DDL 会隐式提交。
-- 图空间访问绑定（graph_space_bindings）随本文件一起建立，见文件末尾；
-- 建图空间时会自动为创建方租户登记绑定行，多租户部署再按需调整权限。
-- ============================================================

-- ========== 审计追踪表 ==========
CREATE TABLE IF NOT EXISTS `agent_traces` (
    `trace_id`          VARCHAR(64)     NOT NULL                                    COMMENT '追踪ID（主键）',
    `timestamp`         DATETIME(3)     NOT NULL                                    COMMENT '请求时间',
    `user_id`           VARCHAR(128)    DEFAULT NULL                                COMMENT '用户ID',
    `session_id`        VARCHAR(64)     DEFAULT NULL                                COMMENT '会话ID',

    -- 输入层
    `input_text`        MEDIUMTEXT      DEFAULT NULL                                COMMENT '用户输入文本',
    `input_attachments` JSON            DEFAULT NULL                                COMMENT '用户输入附件列表',

    -- 预处理层
    `intent`            VARCHAR(512)    DEFAULT NULL                                COMMENT '识别的用户意图',
    `rag_hits`          JSON            DEFAULT NULL                                COMMENT 'RAG知识库检索命中的文档ID列表',
    `rerank_result`     TEXT            DEFAULT NULL                                COMMENT '重排序结果',

    -- AI层
    `llm_model`         VARCHAR(128)    DEFAULT NULL                                COMMENT '使用的LLM模型名称',
    `prompt_version`    VARCHAR(64)     DEFAULT NULL                                COMMENT '提示词版本号',
    `total_tokens`      INT             DEFAULT 0                                   COMMENT '总消耗token数',

    -- ReAct循环
    `steps_json`        MEDIUMTEXT      DEFAULT NULL                                COMMENT 'ReAct步骤详情JSON（含toolCalls/toolResults/inspection）',
    `step_count`        INT             DEFAULT 0                                   COMMENT 'ReAct循环步数',

    -- 输出
    `final_output`      MEDIUMTEXT      DEFAULT NULL                                COMMENT '最终输出文本',
    `risk_level`        VARCHAR(16)     DEFAULT 'LOW'                               COMMENT '风险等级（LOW/MEDIUM/HIGH）',
    `user_confirmed`    TINYINT(1)      DEFAULT 0                                   COMMENT '用户是否确认（高风险操作）',

    -- 元数据
    `total_duration_ms` BIGINT          DEFAULT 0                                   COMMENT '总耗时（毫秒）',
    `metadata`          JSON            DEFAULT NULL                                COMMENT '扩展元数据（session_id/audit_score等）',

    -- 完整JSON快照（反序列化用）
    `full_json`         MEDIUMTEXT      NOT NULL                                    COMMENT '完整AgentTrace JSON快照',

    PRIMARY KEY (`trace_id`),
    INDEX `idx_timestamp` (`timestamp`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_trace_session_time` (`session_id`, `timestamp`, `trace_id`, `user_id`),
    INDEX `idx_trace_owner_time` (`user_id`, `timestamp`, `trace_id`, `session_id`),
    INDEX `idx_risk_level` (`risk_level`),
    INDEX `idx_llm_model` (`llm_model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计追踪表 - 记录Agent每次请求的完整执行链路';

-- ========== 会话表 ==========
CREATE TABLE IF NOT EXISTS `sessions` (
    `id`                  VARCHAR(64)     NOT NULL     DEFAULT ''                   COMMENT '会话ID（主键）',
    `user_id`             VARCHAR(128)    NOT NULL     DEFAULT ''                   COMMENT '用户ID',
    `tenant_id`           VARCHAR(128)    DEFAULT NULL                              COMMENT '可空租户ID',
    `identity`            VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL             COMMENT '最近一次请求的可信身份；异步任务单独保留原请求身份',
    `title`               VARCHAR(256)    DEFAULT NULL                              COMMENT '会话标题（用户首条消息）',
    `created_at`          DATETIME(3)     NOT NULL     DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `last_active`         DATETIME(3)     NOT NULL     DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后活跃时间',
    `ended_at`            DATETIME(3)     DEFAULT NULL                              COMMENT '结束时间（超时/手动关闭）',
    `status`              VARCHAR(16)     NOT NULL     DEFAULT 'active'             COMMENT '状态（active/timeout/closed）',
    PRIMARY KEY (`id`),
    INDEX `idx_sessions_user_id` (`user_id`),
    INDEX `idx_sessions_status` (`status`),
    INDEX `idx_sessions_last_active` (`last_active`),
    INDEX `idx_session_memory_scan` (`last_active`, `id`),
    INDEX `idx_session_owner_active` (`tenant_id`, `user_id`, `last_active`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表 - 管理用户对话会话的生命周期';

-- ========== 消息表 ==========
-- content 为结构化 JSON 数组，统一格式：
-- 纯文字消息: [{"type":"TEXT","text":"你好"}]
-- 含产物消息: [{"type":"TEXT","text":"已生成"},{"type":"ARTIFACT","artifactId":"xxx","metadata":{"type":"IMAGE",...}}]
CREATE TABLE IF NOT EXISTS `messages` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT                          COMMENT '自增主键',
    `session_id`    VARCHAR(64)     NOT NULL     DEFAULT ''                          COMMENT '所属会话ID',
    `trace_id`      VARCHAR(64)     DEFAULT NULL                                     COMMENT '请求 root Trace ID',
    `role`          VARCHAR(32)     NOT NULL     DEFAULT ''                          COMMENT '角色（user/assistant/assistant_tool_call/tool/system）',
    `content`       JSON            NOT NULL                                         COMMENT '结构化内容块数组（TEXT/ARTIFACT）',
    `is_summary`    TINYINT(1)      NOT NULL DEFAULT 0                               COMMENT '是否为压缩摘要（0=原始消息，1=摘要）',
    `external_event_id` VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '外部事件幂等标识',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)           COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_messages_session_id` (`session_id`),
    INDEX `idx_messages_session_summary` (`session_id`, `is_summary`),
    INDEX `idx_message_session_trace` (`session_id`, `trace_id`, `id`),
    UNIQUE KEY `uk_messages_external_event` (`session_id`, `external_event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表 - 存储会话中的所有消息（含压缩摘要）';

-- ========== 子 Agent 持久化任务与结果投递 ==========
CREATE TABLE IF NOT EXISTS subagent_tasks (
    task_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '任务 ID',
    tenant_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '可信租户 ID',
    owner_user_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '任务所属用户',
    owner_identity VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '任务创建时的可信身份',
    owner_session_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属会话 ID',
    owner_run_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '主 Agent 运行 ID',
    owner_turn_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属请求轮次 ID',
    root_trace_id VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '主 Agent Trace ID',
    child_trace_id VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '子 Agent Trace ID',
    spawn_tool_call_id VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '派发工具调用 ID',
    task_json JSON NOT NULL COMMENT '任务输入与完成约束',
    status VARCHAR(32) NOT NULL COMMENT '任务运行状态',
    result_json JSON DEFAULT NULL COMMENT '持久化完成结果',
    delivery_state VARCHAR(32) NOT NULL COMMENT '结果投递状态',
    delivery_event_id VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '幂等投递事件 ID',
    delivery_lease_until DATETIME(3) DEFAULT NULL COMMENT '投递领取租约截止时间',
    delivery_lease_token VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '投递领取租约令牌',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    finished_at DATETIME(3) DEFAULT NULL COMMENT '完成时间',
    expires_at DATETIME(3) NOT NULL COMMENT '任务与结果过期时间',
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_subagent_delivery_event (delivery_event_id),
    UNIQUE KEY uk_subagent_spawn_call (owner_run_id, spawn_tool_call_id),
    KEY idx_subagent_owner_tasks (tenant_id, owner_user_id, owner_session_id, created_at, task_id),
    KEY idx_subagent_delivery (delivery_state, delivery_lease_until, owner_session_id),
    KEY idx_subagent_expiry (expires_at, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='子 Agent 持久化任务与结果投递';

-- ========== 用户表 ==========
CREATE TABLE IF NOT EXISTS `users` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`       VARCHAR(128)    NOT NULL     COMMENT '用户ID（业务唯一标识）',
    `username`      VARCHAR(64)     NOT NULL     COMMENT '登录用户名',
    `password_hash` VARCHAR(256)    NOT NULL     COMMENT '密码哈希（SHA-256）',
    `display_name`  VARCHAR(128)    DEFAULT NULL COMMENT '显示名称',
    `tenant_id`     VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '可信认证租户，必须由管理侧配置',
    `identity`      VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '可信认证身份，必须由管理侧配置',
    `status`        VARCHAR(16)     NOT NULL DEFAULT 'active' COMMENT '状态（active/disabled）',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_users_user_id` (`user_id`),
    UNIQUE INDEX `idx_users_username` (`username`),
    INDEX `idx_users_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表 - 登录账号与显示信息';

-- 测试用户: userId=test-user-001, username=test, password=test1234
-- 密码哈希为 "test1234" 的 SHA-256
INSERT INTO `users` (`user_id`, `username`, `password_hash`, `display_name`)
VALUES ('test-user-001', 'test', '937e8d5fbb48bd4949536cd65b8d35c426b80d2f830c5c308e2cdec422ae2244', 'Test User')
ON DUPLICATE KEY UPDATE `user_id` = `user_id`;

-- ========== 知识权威层 ==========
CREATE TABLE IF NOT EXISTS internal_api_permission (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'Permission row ID',
    tenant_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Trusted tenant ID',
    identity VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Trusted business identity',
    endpoint_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Endpoint key',
    disabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 disables this endpoint, legacy grants remain 0',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Created time',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Updated time',
    UNIQUE KEY uk_internal_api_scope (tenant_id, identity, endpoint_key),
    INDEX idx_internal_api_page (tenant_id, id),
    INDEX idx_internal_api_identity_page (tenant_id, identity, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Internal API permissions by tenant and identity';

-- Wiki 权威层、用户偏好、原文件登记与后台任务，与 Milvus 投影集合配合使用。

CREATE TABLE IF NOT EXISTS knowledge_metadata (
    id                         VARCHAR(64)   NOT NULL COMMENT '记录唯一标识',
    tenant_id                  VARCHAR(128)  NULL COMMENT '租户标识，空值表示全局范围',
    user_id                    VARCHAR(128)  NULL COMMENT '所属用户，操作记忆不设置用户归属',
    namespace_type             VARCHAR(32)   NOT NULL COMMENT '知识所属范围类型',
    namespace_key              VARCHAR(256)  NULL COMMENT '集合名或图谱范围定位键',
    concept_type               VARCHAR(64)   NOT NULL COMMENT '知识类型，用户偏好不进入 Wiki',
    logical_key                VARCHAR(256)  NULL COMMENT '同一知识条目的稳定业务键',
    status                     VARCHAR(20)   NOT NULL DEFAULT 'draft' COMMENT '当前生命周期或任务状态',
    current_version            VARCHAR(64)   NULL COMMENT '当前有效知识版本标识，由 MySQL 决定',
    route_type                 VARCHAR(32)   NOT NULL COMMENT '最终读取类型 DOCUMENT、GRAPH、USER_MEMORY 或 OPERATION_MEMORY',
    route_data                 JSON          NOT NULL COMMENT '权威读取定位：文档集合与文档标识、记忆标识或图谱范围',
    revision_metadata          JSON          NULL COMMENT '当前版本技术元数据，不含正文摘要来源，最终路由见 route_data',
    links                      JSON          NULL COMMENT '关联知识标识与关系类型 JSON 数组',
    version                    BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    stale_after                DATETIME(3)   NULL COMMENT '失效时间，空值表示不自动到期',
    event_time                 DATETIME(3)   NULL COMMENT '事件发生时间，仅用户情景记忆使用；空值回退到 updated_at 排序',
    created_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT chk_metadata_type CHECK (concept_type <> 'USER_PREFERENCE'),
    PRIMARY KEY (id),
    INDEX idx_concept_user_type_key
        (tenant_id, user_id, concept_type, logical_key, id),
    INDEX idx_concept_scope_status
        (tenant_id, user_id, namespace_type, concept_type, status, updated_at, id),
    INDEX idx_concept_scope_event
        (tenant_id, user_id, namespace_type, concept_type, status, event_time, id),
    INDEX idx_concept_namespace
        (tenant_id, namespace_type, namespace_key, concept_type, id),
    INDEX idx_concept_current_revision (current_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Wiki 当前权限、归属、生命周期与版本指针，不保存正文摘要来源';

CREATE TABLE IF NOT EXISTS user_preferences (
    id                         VARCHAR(64)   NOT NULL COMMENT '记录唯一标识',
    tenant_id                  VARCHAR(128)  NULL COMMENT '租户标识，空值表示全局范围',
    user_id                    VARCHAR(128)  NULL COMMENT '所属用户，操作记忆不设置用户归属',
    namespace_type             VARCHAR(32)   NOT NULL COMMENT '知识所属范围类型',
    namespace_key              VARCHAR(256)  NULL COMMENT '集合名或图谱范围定位键',
    concept_type               VARCHAR(64)   NOT NULL COMMENT '知识类型，用户偏好不进入 Wiki',
    logical_key                VARCHAR(256)  NULL COMMENT '同一知识条目的稳定业务键',
    status                     VARCHAR(20)   NOT NULL DEFAULT 'draft' COMMENT '当前生命周期或任务状态',
    current_revision_id        VARCHAR(64)   NULL COMMENT '当前可用版本标识',
    revision_metadata          JSON          NULL COMMENT '当前版本技术元数据与路由范围，不含正文摘要来源',
    links                      JSON          NULL COMMENT '关联知识标识与关系类型 JSON 数组',
    version                    BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    stale_after                DATETIME(3)   NULL COMMENT '失效时间，空值表示不自动到期',
    created_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    snapshot JSON NULL COMMENT '当前偏好内容及证据，不进入向量集合',
    CONSTRAINT chk_preference_type CHECK (concept_type = 'USER_PREFERENCE'),
    PRIMARY KEY (id),
    INDEX idx_concept_user_type_key
        (tenant_id, user_id, concept_type, logical_key, id),
    INDEX idx_concept_scope_status
        (tenant_id, user_id, namespace_type, concept_type, status, updated_at, id),
    INDEX idx_concept_namespace
        (tenant_id, namespace_type, namespace_key, concept_type, id),
    INDEX idx_concept_current_revision (current_revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户偏好当前值，按租户用户偏好键更新并在请求前注入';

CREATE TABLE IF NOT EXISTS knowledge_artifacts (
    id                         VARCHAR(64)   NOT NULL COMMENT '记录唯一标识',
    tenant_id                  VARCHAR(128)  NULL COMMENT '租户标识，空值表示全局范围',
    collection_key             VARCHAR(128)  NOT NULL COMMENT '目标文档集合名',
    artifact_type              VARCHAR(32)   NOT NULL COMMENT '原文件或规范 Markdown 类型',
    file_name                  VARCHAR(512)  NOT NULL COMMENT '原始文件名',
    media_type                 VARCHAR(255)  NOT NULL COMMENT '文件媒体类型',
    content_hash               VARCHAR(64)   NOT NULL COMMENT '内容哈希，用于幂等与完整性检查',
    storage_uri                VARCHAR(2048) NOT NULL COMMENT '不可变文件存储地址',
    status                     VARCHAR(20)   NOT NULL DEFAULT 'active' COMMENT '当前生命周期或任务状态',
    created_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_artifact_scope_hash (tenant_id, collection_key, content_hash, id),
    INDEX idx_artifact_scope_file (tenant_id, collection_key, file_name(191), id),
    INDEX idx_artifact_scope_time (tenant_id, collection_key, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不可变原文件和转换文件登记';

CREATE TABLE IF NOT EXISTS knowledge_tasks (
    sequence_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务分页序号',
    task_type VARCHAR(32) NOT NULL COMMENT '文档入库、向量索引或图谱变更',
    id VARCHAR(128) NOT NULL DEFAULT (UUID()) COMMENT '任务业务标识，按任务类型隔离',
    tenant_id                  VARCHAR(128)  NULL COMMENT '租户标识，空值表示全局范围',
    status                     VARCHAR(24)   NOT NULL COMMENT '当前生命周期或任务状态',
    attempts                   INT           NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    available_at               DATETIME(3)   NOT NULL COMMENT '下次可处理时间',
    claimed_at                 DATETIME(3)   NULL COMMENT '工作线程领取时间',
    started_at                 DATETIME(3)   NULL COMMENT '开始处理时间',
    completed_at               DATETIME(3)   NULL COMMENT '完成时间',
    error_message              VARCHAR(1024) NULL COMMENT '最近一次失败原因',
    created_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    user_id                    VARCHAR(128)  NULL COMMENT '所属用户，操作记忆不设置用户归属',
    concept_id                 VARCHAR(64)   NULL COMMENT '知识条目标识',
    revision_id                VARCHAR(64)   NULL COMMENT '知识版本标识',
    operation                  VARCHAR(20)   NULL COMMENT '投影写入或删除操作',
    artifact_id                VARCHAR(64)   NULL COMMENT '原文件标识',
    collection_key             VARCHAR(128)  NULL COMMENT '目标文档集合名',
    converted_artifact_id      VARCHAR(64)   NULL COMMENT '规范 Markdown 文件标识',
    source_concept_id          VARCHAR(64)   NULL COMMENT '源文档知识条目标识',
    source_revision_id         VARCHAR(64)   NULL COMMENT '对应知识版本标识',
    graph_id                   VARCHAR(128)  NULL COMMENT '图空间标识',
    schema_id                  VARCHAR(128)  NULL COMMENT '图模式标识',
    result_revision_id         VARCHAR(64)   NULL COMMENT '已完成的知识版本标识',
    payload_hash               VARCHAR(64)   NULL COMMENT '规范变更载荷哈希',
    canonical_payload          JSON          NULL COMMENT '已确认的图变更 JSON',
    graph_node_count           INT           NULL COMMENT '提交的节点数量',
    graph_relation_count       INT           NULL COMMENT '提交的关系数量',
    graph_committed_at         DATETIME(3)   NULL COMMENT 'Neo4j 已提交时间',
    payload JSON NULL COMMENT '待写入向量存储的临时版本载荷，完成后清理',
    PRIMARY KEY (sequence_id),
    UNIQUE KEY uk_task_identity (task_type, id),
    UNIQUE KEY uk_task_projection (task_type, concept_id, revision_id, operation),
    INDEX idx_task_claim (task_type, status, available_at, sequence_id),
    INDEX idx_task_artifact (task_type, artifact_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统一后台任务及跨存储重试状态';

-- ========== 租户/身份工具禁用表 ==========
-- 工具本身不落库：名称、描述、Schema 一律来自 ToolRegistry，
-- 这里只保存“某个租户的某个身份禁用哪些工具”。
-- 空数组代表什么都不禁用，即全部工具可用；未配置的行回退到同租户的 DEFAULT 行，
-- 两者都没有时同样全部可用。

CREATE TABLE IF NOT EXISTS `tool_permission_profile` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT                     COMMENT '自增主键',
    `tenant_id`           VARCHAR(128) COLLATE utf8mb4_bin NOT NULL                                    COMMENT '所属租户，无租户体系的接入使用 000000',
    `identity`            VARCHAR(128) COLLATE utf8mb4_bin NOT NULL                                    COMMENT '工具身份，例如 teacher/parent/DEFAULT',
    `disabled_tools_json` JSON          NOT NULL                                    COMMENT '该身份禁用的工具名 JSON 数组；空数组代表全部启用，工具定义仍以 ToolRegistry 为准',
    `created_at`          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)       COMMENT '创建时间',
    `updated_at`          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_profile_tenant_identity` (`tenant_id`, `identity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='身份工具禁用 - 同一租户同一身份一份配置，空列表代表全部启用';

-- 默认行：没有单独配置过的身份一律走它，空列表即什么都不禁用。
INSERT INTO `tool_permission_profile` (`tenant_id`, `identity`, `disabled_tools_json`)
VALUES ('000000', 'DEFAULT', JSON_ARRAY())
ON DUPLICATE KEY UPDATE `tenant_id` = `tenant_id`;

-- ========== 图空间访问绑定（租户 -> 图空间） ==========
-- 使用约定：
-- 1. HARNESS_GRAPH_PROVIDER 是知识图谱功能的唯一开关；值为 none 时不启用图功能。
-- 2. 本表只维护“租户 -> 图空间”的访问映射，不负责启用或禁用知识图谱。
-- 3. tenantId 由可信的系统后端通过请求 context 传入，不会作为 LLM 工具参数暴露。
-- 4. 请求未提供 tenantId 时，框架固定使用默认租户 000000。
-- 5. 本表存在即启用按租户强制校验：**没有任何绑定行的图空间对所有租户都不可读**
--    （含 query_graph 与 Wiki 卡片）。因此建图空间时会自动为创建方（单租户即 000000）
--    登记一行 write 绑定，多租户部署再按需增删。
-- 6. 非默认租户必须配置绑定行，否则框架拒绝其图空间访问，防止跨租户读取。
-- 7. 当前请求启动的子 Agent 会继承 tenantId；未保存原请求上下文的异步恢复流程不启用图工具。
-- 8. 应用启动时探测本表；表不存在则回退为“图空间全局可读”，此时建图不会写绑定行。

CREATE TABLE IF NOT EXISTS `graph_space_bindings` (
    `id`          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '自增主键及分页游标',
    `tenant_id`   VARCHAR(128)    NOT NULL DEFAULT '000000' COMMENT '调用方租户ID，单租户默认000000',
    `graph_id`    VARCHAR(128)    NOT NULL                COMMENT 'Neo4j图空间ID',
    `schema_id`   VARCHAR(128)    NOT NULL                COMMENT '图谱Schema ID',
    `description` VARCHAR(1000)   NOT NULL DEFAULT ''     COMMENT '图空间用途及数据关系说明，供Agent选择检索目标',
    `permission`  VARCHAR(16)     NOT NULL DEFAULT 'read' COMMENT '权限（read/write/admin）',
    `status`      VARCHAR(16)     NOT NULL DEFAULT 'active' COMMENT '状态（active/disabled）',
    `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_graph_binding_tenant_space` (`tenant_id`, `graph_id`, `schema_id`),
    INDEX `idx_graph_binding_tenant_page` (`tenant_id`, `status`, `id`),
    INDEX `idx_graph_binding_space` (`graph_id`, `schema_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户与图空间的访问绑定';

-- ========== 旧库增量升级（不覆盖身份、权限或已有结果） ==========
SET @schema_alter = (
    SELECT CONCAT_WS(', ',
        IF(SUM(column_name = 'tenant_id') = 0, 'ADD COLUMN tenant_id VARCHAR(128) DEFAULT NULL COMMENT ''可信租户 ID''', NULL),
        IF(SUM(column_name = 'identity') = 0, 'ADD COLUMN identity VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''最近一次请求的可信身份''', NULL))
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sessions'
);
SET @schema_migration = IF(@schema_alter = '', 'SELECT 1', CONCAT('ALTER TABLE sessions ', @schema_alter));
PREPARE schema_statement FROM @schema_migration;
EXECUTE schema_statement;
DEALLOCATE PREPARE schema_statement;

SET @schema_alter = (
    SELECT CONCAT_WS(', ',
        IF(SUM(column_name = 'tenant_id') = 0, 'ADD COLUMN tenant_id VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''可信认证租户，须由管理侧配置''', NULL),
        IF(SUM(column_name = 'identity') = 0, 'ADD COLUMN identity VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''可信认证身份，须由管理侧配置''', NULL))
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users'
);
SET @schema_migration = IF(@schema_alter = '', 'SELECT 1', CONCAT('ALTER TABLE users ', @schema_alter));
PREPARE schema_statement FROM @schema_migration;
EXECUTE schema_statement;
DEALLOCATE PREPARE schema_statement;

SET @schema_alter = (
    SELECT CONCAT_WS(', ',
        IF(SUM(column_name = 'trace_id') = 0, 'ADD COLUMN trace_id VARCHAR(64) DEFAULT NULL COMMENT ''请求 root Trace ID''', NULL),
        IF(SUM(column_name = 'is_summary') = 0, 'ADD COLUMN is_summary TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否为压缩摘要''', NULL),
        IF(SUM(column_name = 'external_event_id') = 0, 'ADD COLUMN external_event_id VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''外部事件幂等标识''', NULL))
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'messages'
);
SET @schema_migration = IF(@schema_alter = '', 'SELECT 1', CONCAT('ALTER TABLE messages ', @schema_alter));
PREPARE schema_statement FROM @schema_migration;
EXECUTE schema_statement;
DEALLOCATE PREPARE schema_statement;

-- 先在事务中保留旧文本，再改为 JSON；已是 JSON 的列与内容不变。
SET @messages_content_is_json = (
    SELECT data_type = 'json' FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'messages' AND column_name = 'content'
);
SET @schema_migration = IF(@messages_content_is_json, 'SELECT 1',
    'UPDATE messages SET content = JSON_ARRAY(JSON_OBJECT(''type'', ''TEXT'', ''text'', content)) WHERE CASE WHEN JSON_VALID(content) THEN JSON_TYPE(content) <> ''ARRAY'' ELSE TRUE END');
START TRANSACTION;
PREPARE schema_statement FROM @schema_migration;
EXECUTE schema_statement;
DEALLOCATE PREPARE schema_statement;
COMMIT;
SET @schema_migration = IF(@messages_content_is_json, 'SELECT 1',
    'ALTER TABLE messages MODIFY COLUMN content JSON NOT NULL COMMENT ''结构化内容块数组（TEXT/ARTIFACT）''');
PREPARE schema_statement FROM @schema_migration;
EXECUTE schema_statement;
DEALLOCATE PREPARE schema_statement;

SET @schema_alter = (
    SELECT CONCAT_WS(', ',
        IF(SUM(index_name = 'idx_messages_session_summary') = 0, 'ADD INDEX idx_messages_session_summary (session_id, is_summary)', NULL),
        IF(SUM(index_name = 'idx_message_session_trace') = 0, 'ADD INDEX idx_message_session_trace (session_id, trace_id, id)', NULL),
        IF(SUM(index_name = 'uk_messages_external_event') = 0, 'ADD UNIQUE KEY uk_messages_external_event (session_id, external_event_id)', NULL))
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'messages'
);
SET @schema_migration = IF(@schema_alter = '', 'SELECT 1', CONCAT('ALTER TABLE messages ', @schema_alter));
PREPARE schema_statement FROM @schema_migration;
EXECUTE schema_statement;
DEALLOCATE PREPARE schema_statement;

SET @schema_alter = (
    SELECT CONCAT_WS(', ',
        IF(SUM(index_name = 'idx_session_memory_scan') = 0, 'ADD INDEX idx_session_memory_scan (last_active, id)', NULL),
        IF(SUM(index_name = 'idx_session_owner_active') = 0, 'ADD INDEX idx_session_owner_active (tenant_id, user_id, last_active, id)', NULL))
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'sessions'
);
SET @schema_migration = IF(@schema_alter = '', 'SELECT 1', CONCAT('ALTER TABLE sessions ', @schema_alter));
PREPARE schema_statement FROM @schema_migration;
EXECUTE schema_statement;
DEALLOCATE PREPARE schema_statement;

SET @schema_alter = (
    SELECT CONCAT_WS(', ',
        IF(SUM(index_name = 'idx_trace_session_time') = 0, 'ADD INDEX idx_trace_session_time (session_id, timestamp, trace_id, user_id)', NULL),
        IF(SUM(index_name = 'idx_trace_owner_time') = 0, 'ADD INDEX idx_trace_owner_time (user_id, timestamp, trace_id, session_id)', NULL))
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'agent_traces'
);
SET @schema_migration = IF(@schema_alter = '', 'SELECT 1', CONCAT('ALTER TABLE agent_traces ', @schema_alter));
PREPARE schema_statement FROM @schema_migration;
EXECUTE schema_statement;
DEALLOCATE PREPARE schema_statement;

-- 兼容更早创建过、没有 description 列的图空间绑定表。
SET @graph_binding_description_exists = (
    SELECT COUNT(*)
    FROM `information_schema`.`columns`
    WHERE `table_schema` = DATABASE()
      AND `table_name` = 'graph_space_bindings'
      AND `column_name` = 'description'
);
SET @graph_binding_description_migration = IF(
    @graph_binding_description_exists = 0,
    'ALTER TABLE `graph_space_bindings` ADD COLUMN `description` VARCHAR(1000) NOT NULL DEFAULT '''' COMMENT ''图空间用途及数据关系说明，供Agent选择检索目标'' AFTER `schema_id`',
    'SELECT 1'
);
PREPARE graph_binding_description_statement FROM @graph_binding_description_migration;
EXECUTE graph_binding_description_statement;
DEALLOCATE PREPARE graph_binding_description_statement;

-- Existing allowlist rows must not become denials when upgrading.
SET @internal_api_disabled_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'internal_api_permission' AND column_name = 'disabled'
);
SET @internal_api_disabled_migration = IF(
    @internal_api_disabled_exists = 0,
    'ALTER TABLE internal_api_permission ADD COLUMN disabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1 disables this endpoint, legacy grants remain 0'' AFTER endpoint_key',
    'SELECT 1'
);
PREPARE internal_api_disabled_statement FROM @internal_api_disabled_migration;
EXECUTE internal_api_disabled_statement;
DEALLOCATE PREPARE internal_api_disabled_statement;
