-- ============================================================
-- Cyrene Agent - MySQL 统一建表脚本
-- Database: agent（Docker Compose 部署由 MYSQL_DATABASE=agent 自动选择；
--           手动执行前请先创建并选中数据库，例如：
--           CREATE DATABASE IF NOT EXISTS `agent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;）
-- 包含：审计追踪、会话、消息、用户、知识权威层（metadata/偏好/制品/任务）
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可在已有库重复执行。
-- 可选扩展：graph_space_bindings（图谱租户隔离表）单独保留在
--           schema-graph-space-bindings-mysql.sql，默认不安装；
--           该表一旦存在，框架即切换为租户绑定强制校验模式。
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
    INDEX `idx_trace_session_time` (`session_id`, `timestamp`, `trace_id`),
    INDEX `idx_risk_level` (`risk_level`),
    INDEX `idx_llm_model` (`llm_model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计追踪表 - 记录Agent每次请求的完整执行链路';

-- ========== 会话表 ==========
CREATE TABLE IF NOT EXISTS `sessions` (
    `id`                  VARCHAR(64)     NOT NULL     DEFAULT ''                   COMMENT '会话ID（主键）',
    `user_id`             VARCHAR(128)    NOT NULL     DEFAULT ''                   COMMENT '用户ID',
    `tenant_id`           VARCHAR(128)    DEFAULT NULL                              COMMENT '可空租户ID',
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
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)           COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_messages_session_id` (`session_id`),
    INDEX `idx_messages_session_summary` (`session_id`, `is_summary`),
    INDEX `idx_message_session_trace` (`session_id`, `trace_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表 - 存储会话中的所有消息（含压缩摘要）';

-- 已有数据库迁移：将 content 从 MEDIUMTEXT 改为 JSON
-- ALTER TABLE messages MODIFY COLUMN content JSON NOT NULL COMMENT '结构化内容块数组（TEXT/ARTIFACT）';
-- UPDATE messages SET content = JSON_ARRAY(JSON_OBJECT('type', 'TEXT', 'text', content)) WHERE JSON_VALID(content) = 0;

-- ========== 用户表 ==========
CREATE TABLE IF NOT EXISTS `users` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`       VARCHAR(128)    NOT NULL     COMMENT '用户ID（业务唯一标识）',
    `username`      VARCHAR(64)     NOT NULL     COMMENT '登录用户名',
    `password_hash` VARCHAR(256)    NOT NULL     COMMENT '密码哈希（SHA-256）',
    `display_name`  VARCHAR(128)    DEFAULT NULL COMMENT '显示名称',
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
    created_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at                 DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    CONSTRAINT chk_metadata_type CHECK (concept_type <> 'USER_PREFERENCE'),
    PRIMARY KEY (id),
    INDEX idx_concept_user_type_key
        (tenant_id, user_id, concept_type, logical_key, id),
    INDEX idx_concept_scope_status
        (tenant_id, user_id, namespace_type, concept_type, status, updated_at, id),
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
