-- ============================================================
-- Harness Agent - Memory Management Schema (MySQL)
-- Database: agent (same as audit)
-- ============================================================

USE `agent`;

-- Sessions table: manages conversation session lifecycle
CREATE TABLE IF NOT EXISTS `sessions` (
    `id`            VARCHAR(64)     NOT NULL,
    `user_id`       VARCHAR(128)    NOT NULL,
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `last_active`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `ended_at`      DATETIME(3)     DEFAULT NULL,
    `status`        VARCHAR(16)     NOT NULL DEFAULT 'active',
    PRIMARY KEY (`id`),
    INDEX `idx_sessions_user_id` (`user_id`),
    INDEX `idx_sessions_status` (`status`),
    INDEX `idx_sessions_last_active` (`last_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Messages table: stores conversation messages per session
CREATE TABLE IF NOT EXISTS `messages` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `session_id`    VARCHAR(64)     NOT NULL,
    `role`          VARCHAR(16)     NOT NULL,
    `content`       MEDIUMTEXT      NOT NULL,
    `is_summary`    TINYINT(1)      NOT NULL DEFAULT 0,
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    INDEX `idx_messages_session_id` (`session_id`),
    INDEX `idx_messages_session_summary` (`session_id`, `is_summary`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- User preferences table: long-term memory extracted from sessions
CREATE TABLE IF NOT EXISTS `user_preferences` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`           VARCHAR(128)    NOT NULL,
    `category`          VARCHAR(64)     NOT NULL,
    `content`           TEXT            NOT NULL,
    `source_session_id` VARCHAR(64)     DEFAULT NULL,
    `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_pref_user_category` (`user_id`, `category`),
    INDEX `idx_pref_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
