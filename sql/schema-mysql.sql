-- ============================================================
-- Harness Agent - MySQL Trace Schema
-- Database: agent
-- ============================================================

CREATE DATABASE IF NOT EXISTS `agent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `agent`;

CREATE TABLE IF NOT EXISTS `agent_traces` (
    `trace_id`          VARCHAR(64)     NOT NULL,
    `timestamp`         DATETIME(3)     NOT NULL,
    `user_id`           VARCHAR(128)    DEFAULT NULL,

    -- Input layer
    `input_text`        MEDIUMTEXT      DEFAULT NULL,
    `input_attachments` JSON            DEFAULT NULL,

    -- Preprocess layer
    `intent`            VARCHAR(512)    DEFAULT NULL,
    `rag_hits`          JSON            DEFAULT NULL,
    `rerank_result`     TEXT            DEFAULT NULL,

    -- AI layer
    `llm_model`         VARCHAR(128)    DEFAULT NULL,
    `prompt_version`    VARCHAR(64)     DEFAULT NULL,
    `total_tokens`      INT             DEFAULT 0,

    -- ReAct loop
    `steps_json`        MEDIUMTEXT      DEFAULT NULL,
    `step_count`        INT             DEFAULT 0,

    -- Output
    `final_output`      MEDIUMTEXT      DEFAULT NULL,
    `risk_level`        VARCHAR(16)     DEFAULT 'LOW',
    `user_confirmed`    TINYINT(1)      DEFAULT 0,

    -- Meta
    `total_duration_ms` BIGINT          DEFAULT 0,
    `metadata`          JSON            DEFAULT NULL,

    -- Full JSON snapshot (for fast deserialization)
    `full_json`         MEDIUMTEXT      NOT NULL,

    PRIMARY KEY (`trace_id`),
    INDEX `idx_timestamp` (`timestamp`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_risk_level` (`risk_level`),
    INDEX `idx_llm_model` (`llm_model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
