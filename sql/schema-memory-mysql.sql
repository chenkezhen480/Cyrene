-- ============================================================
-- Harness Agent - 会话记忆管理表 (MySQL)
-- Database: agent（与审计库共用）
-- ============================================================

USE `agent`;

-- 会话表：管理对话会话生命周期
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

-- 消息表：存储每个会话的对话消息
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

-- 用户偏好表：从会话中提炼的长期记忆
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
