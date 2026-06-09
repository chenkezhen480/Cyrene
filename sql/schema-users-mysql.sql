-- ============================================================
-- Cyrene Agent - 用户表 (MySQL)
-- Database: agent（与审计/记忆库共用）
-- ============================================================

USE `agent`;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 测试用户: userId=test-user-001, username=test, password=test1234
-- 密码哈希为 "test1234" 的 SHA-256
INSERT INTO `users` (`user_id`, `username`, `password_hash`, `display_name`)
VALUES ('test-user-001', 'test', '937e8d5fbb48bd4949536cd65b8d35c426b80d2f830c5c308e2cdec422ae2244', 'Test User')
ON DUPLICATE KEY UPDATE `user_id` = `user_id`;
