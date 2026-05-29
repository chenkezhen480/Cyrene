-- ============================================================
-- Harness Agent - 审计追踪表 (MySQL)
-- Database: agent
-- ============================================================

CREATE DATABASE IF NOT EXISTS `agent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `agent`;

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
