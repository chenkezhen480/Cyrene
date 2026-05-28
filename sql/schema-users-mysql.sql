-- ============================================================
-- Harness Agent - Users Table (MySQL)
-- Database: agent (same as audit/memory)
-- ============================================================

USE `agent`;

CREATE TABLE IF NOT EXISTS `users` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`       VARCHAR(128)    NOT NULL,
    `username`      VARCHAR(64)     NOT NULL,
    `password_hash` VARCHAR(256)    NOT NULL,
    `display_name`  VARCHAR(128)    DEFAULT NULL,
    `status`        VARCHAR(16)     NOT NULL DEFAULT 'active',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_users_user_id` (`user_id`),
    UNIQUE INDEX `idx_users_username` (`username`),
    INDEX `idx_users_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Test user: userId=test-user-001, username=test, password=test1234
-- Password hash is SHA-256 of "test1234"
INSERT INTO `users` (`user_id`, `username`, `password_hash`, `display_name`)
VALUES ('test-user-001', 'test', '937e8d5fbb48bd4949536cd65b8d35c426b80d2f830c5c308e2cdec422ae2244', 'Test User')
ON DUPLICATE KEY UPDATE `user_id` = `user_id`;
