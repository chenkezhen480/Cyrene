-- ============================================================
-- Harness Agent - MySQL 总表（不含 users 表）
-- Database: agent
-- 包含：审计追踪、会话、消息、用户偏好
-- ============================================================

CREATE DATABASE IF NOT EXISTS `agent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `agent`;

-- ========== 审计追踪表 ==========
CREATE TABLE IF NOT EXISTS `agent_traces` (
    `trace_id`          VARCHAR(64)     NOT NULL    COMMENT '追踪ID（主键）',
    `timestamp`         DATETIME(3)     NOT NULL    COMMENT '请求时间',
    `user_id`           VARCHAR(128)    DEFAULT NULL COMMENT '用户ID',
    `session_id`        VARCHAR(64)     DEFAULT NULL COMMENT '会话ID',

    -- 输入层
    `input_text`        MEDIUMTEXT      DEFAULT NULL COMMENT '用户输入文本',
    `input_attachments` JSON            DEFAULT NULL COMMENT '用户输入附件列表',

    -- 预处理层
    `intent`            VARCHAR(512)    DEFAULT NULL COMMENT '识别的用户意图',
    `rag_hits`          JSON            DEFAULT NULL COMMENT 'RAG知识库检索命中的文档ID列表',
    `rerank_result`     TEXT            DEFAULT NULL COMMENT '重排序结果',

    -- AI层
    `llm_model`         VARCHAR(128)    DEFAULT NULL COMMENT '使用的LLM模型名称',
    `prompt_version`    VARCHAR(64)     DEFAULT NULL COMMENT '提示词版本号',
    `total_tokens`      INT             DEFAULT 0    COMMENT '总消耗token数',

    -- ReAct循环
    `steps_json`        MEDIUMTEXT      DEFAULT NULL COMMENT 'ReAct步骤详情JSON（含toolCalls/toolResults/inspection）',
    `step_count`        INT             DEFAULT 0    COMMENT 'ReAct循环步数',

    -- 输出
    `final_output`      MEDIUMTEXT      DEFAULT NULL COMMENT '最终输出文本',
    `risk_level`        VARCHAR(16)     DEFAULT 'LOW' COMMENT '风险等级（LOW/MEDIUM/HIGH）',
    `user_confirmed`    TINYINT(1)      DEFAULT 0    COMMENT '用户是否确认（高风险操作）',

    -- 元数据
    `total_duration_ms` BIGINT          DEFAULT 0    COMMENT '总耗时（毫秒）',
    `metadata`          JSON            DEFAULT NULL COMMENT '扩展元数据（session_id/audit_score等）',

    -- 完整JSON快照（反序列化用）
    `full_json`         MEDIUMTEXT      NOT NULL     COMMENT '完整AgentTrace JSON快照',

    PRIMARY KEY (`trace_id`),
    INDEX `idx_timestamp` (`timestamp`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_risk_level` (`risk_level`),
    INDEX `idx_llm_model` (`llm_model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ========== 会话表 ==========
CREATE TABLE IF NOT EXISTS `sessions` (
    `id`                  VARCHAR(64)     NOT NULL     COMMENT '会话ID（主键）',
    `user_id`             VARCHAR(128)    NOT NULL     COMMENT '用户ID',
    `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `last_active`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后活跃时间',
    `ended_at`            DATETIME(3)     DEFAULT NULL COMMENT '结束时间（超时/手动关闭）',
    `status`              VARCHAR(16)     NOT NULL DEFAULT 'active' COMMENT '状态（active/timeout/closed）',
    `refinement_status`   VARCHAR(20)     NOT NULL DEFAULT 'none' COMMENT '长期记忆提炼状态（none/pending/done）',
    PRIMARY KEY (`id`),
    INDEX `idx_sessions_user_id` (`user_id`),
    INDEX `idx_sessions_status` (`status`),
    INDEX `idx_sessions_last_active` (`last_active`),
    INDEX `idx_sessions_refinement` (`refinement_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ========== 消息表 ==========
CREATE TABLE IF NOT EXISTS `messages` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `session_id`    VARCHAR(64)     NOT NULL     COMMENT '所属会话ID',
    `role`          VARCHAR(16)     NOT NULL     COMMENT '角色（user/assistant/system）',
    `content`       MEDIUMTEXT      NOT NULL     COMMENT '消息内容',
    `is_summary`    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否为压缩摘要（0=原始消息，1=摘要）',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_messages_session_id` (`session_id`),
    INDEX `idx_messages_session_summary` (`session_id`, `is_summary`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ========== 用户偏好表（长期记忆） ==========
CREATE TABLE IF NOT EXISTS `user_preferences` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`           VARCHAR(128)    NOT NULL     COMMENT '用户ID',
    `category`          VARCHAR(64)     NOT NULL     COMMENT '偏好类别（如：coding_style/response_format）',
    `content`           TEXT            NOT NULL     COMMENT '偏好内容',
    `source_session_id` VARCHAR(64)     DEFAULT NULL COMMENT '来源会话ID',
    `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_pref_user_category` (`user_id`, `category`),
    INDEX `idx_pref_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
