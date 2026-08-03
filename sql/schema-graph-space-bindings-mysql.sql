-- ============================================================
-- Cyrene Agent - 图空间访问绑定表（MySQL，可选扩展）
-- Database: agent（与审计/记忆库共用）
--
-- 只有需要按外部系统传入的 tenantId 隔离图空间时才执行本脚本。
--
-- 使用约定：
-- 1. HARNESS_GRAPH_PROVIDER 是知识图谱功能的唯一开关；值为 none 时不启用图功能。
-- 2. 本表只维护“租户 -> 图空间”的访问映射，不负责启用或禁用知识图谱。
-- 3. tenantId 由可信的系统后端通过请求 context 传入，不会作为 LLM 工具参数暴露。
-- 4. 请求未提供 tenantId 时，框架固定使用默认租户 000000。
-- 5. 未创建本表时，仅默认租户 000000 可以使用 Neo4j 中的全局图空间。
-- 6. 非默认租户必须安装本表并配置绑定，否则框架将拒绝图空间访问，防止跨租户读取。
-- 7. 当前请求启动的子 Agent 会继承 tenantId；未保存原请求上下文的异步恢复流程不启用图工具。
-- 8. 创建本表或修改绑定后应重启应用，使图空间访问服务重新检测本表。
-- ============================================================

USE `agent`;

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

-- 兼容已经创建过旧版 graph_space_bindings 的环境。
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

-- 请求 context.tenantId 缺失时，框架固定使用 tenant_id='000000'。
-- 示例：
-- INSERT INTO `graph_space_bindings`
--     (`tenant_id`, `graph_id`, `schema_id`, `description`, `permission`)
-- VALUES
--     ('000000', 'student-capability-demo', 'student-capability-v1',
--      '记录学生、教师、能力以及教学与能力归属关系', 'read');
