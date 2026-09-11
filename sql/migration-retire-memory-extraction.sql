-- 退役 TODO12 批量记忆提取管线后的一次性旧库清理（2026-09）。
-- 代码侧已删除对这些列/键的全部使用，执行本脚本使旧库与 sql/schema-mysql.sql 对齐。
-- 新库按 schema 文件初始化时无需执行。
-- MySQL 8.0 的 DROP COLUMN / DROP INDEX 不支持 IF EXISTS，脚本不可重复执行。

ALTER TABLE sessions
    DROP COLUMN memory_extraction_watermark_message_id,
    DROP COLUMN memory_extraction_last_completed_at;

ALTER TABLE knowledge_tasks
    DROP INDEX uk_task_batch,
    DROP INDEX idx_task_session,
    DROP COLUMN session_id,
    DROP COLUMN from_message_id,
    DROP COLUMN cutoff_message_id,
    DROP COLUMN extractor_version;

-- 如旧库残留已废弃的批量提取任务行，可一并清理：
-- DELETE FROM knowledge_tasks WHERE task_type = 'preference_extraction';
