-- 为现有表补充中文字段注释
USE `agent`;

-- ========== agent_traces 审计追踪表 ==========
ALTER TABLE `agent_traces`
  MODIFY COLUMN `trace_id`          VARCHAR(64)   NOT NULL COMMENT '追踪ID（主键）',
  MODIFY COLUMN `timestamp`         DATETIME(3)   NOT NULL COMMENT '请求时间',
  MODIFY COLUMN `user_id`           VARCHAR(128)  DEFAULT NULL COMMENT '用户ID',
  MODIFY COLUMN `session_id`        VARCHAR(64)   DEFAULT NULL COMMENT '会话ID',
  MODIFY COLUMN `input_text`        MEDIUMTEXT    DEFAULT NULL COMMENT '用户输入文本',
  MODIFY COLUMN `input_attachments` JSON          DEFAULT NULL COMMENT '用户输入附件列表',
  MODIFY COLUMN `intent`            VARCHAR(512)  DEFAULT NULL COMMENT '识别的用户意图',
  MODIFY COLUMN `rag_hits`          JSON          DEFAULT NULL COMMENT 'RAG知识库检索命中的文档ID列表',
  MODIFY COLUMN `rerank_result`     TEXT          DEFAULT NULL COMMENT '重排序结果',
  MODIFY COLUMN `llm_model`         VARCHAR(128)  DEFAULT NULL COMMENT '使用的LLM模型名称',
  MODIFY COLUMN `prompt_version`    VARCHAR(64)   DEFAULT NULL COMMENT '提示词版本号',
  MODIFY COLUMN `total_tokens`      INT           DEFAULT 0 COMMENT '总消耗token数',
  MODIFY COLUMN `steps_json`        MEDIUMTEXT    DEFAULT NULL COMMENT 'ReAct步骤详情JSON',
  MODIFY COLUMN `step_count`        INT           DEFAULT 0 COMMENT 'ReAct循环步数',
  MODIFY COLUMN `final_output`      MEDIUMTEXT    DEFAULT NULL COMMENT '最终输出文本',
  MODIFY COLUMN `risk_level`        VARCHAR(16)   DEFAULT 'LOW' COMMENT '风险等级（LOW/MEDIUM/HIGH）',
  MODIFY COLUMN `user_confirmed`    TINYINT(1)    DEFAULT 0 COMMENT '用户是否确认',
  MODIFY COLUMN `total_duration_ms` BIGINT        DEFAULT 0 COMMENT '总耗时（毫秒）',
  MODIFY COLUMN `metadata`          JSON          DEFAULT NULL COMMENT '扩展元数据',
  MODIFY COLUMN `full_json`         MEDIUMTEXT    NOT NULL COMMENT '完整AgentTrace JSON快照';

-- ========== sessions 会话表 ==========
ALTER TABLE `sessions`
  MODIFY COLUMN `id`                VARCHAR(64)   NOT NULL COMMENT '会话ID（主键）',
  MODIFY COLUMN `user_id`           VARCHAR(128)  NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `created_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  MODIFY COLUMN `last_active`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后活跃时间',
  MODIFY COLUMN `ended_at`          DATETIME(3)   DEFAULT NULL COMMENT '结束时间',
  MODIFY COLUMN `status`            VARCHAR(16)   NOT NULL DEFAULT 'active' COMMENT '状态（active/timeout/closed）',
  MODIFY COLUMN `refinement_status` VARCHAR(20)   NOT NULL DEFAULT 'none' COMMENT '长期记忆提炼状态（none/pending/done）';

-- ========== messages 消息表 ==========
ALTER TABLE `messages`
  MODIFY COLUMN `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN `session_id` VARCHAR(64)  NOT NULL COMMENT '所属会话ID',
  MODIFY COLUMN `role`       VARCHAR(16)  NOT NULL COMMENT '角色（user/assistant/system）',
  MODIFY COLUMN `content`    MEDIUMTEXT   NOT NULL COMMENT '消息内容',
  MODIFY COLUMN `is_summary` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为压缩摘要（0=原始，1=摘要）',
  MODIFY COLUMN `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间';

-- ========== user_preferences 用户偏好表 ==========
ALTER TABLE `user_preferences`
  MODIFY COLUMN `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN `user_id`           VARCHAR(128) NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `category`          VARCHAR(64)  NOT NULL COMMENT '偏好类别',
  MODIFY COLUMN `content`           TEXT         NOT NULL COMMENT '偏好内容',
  MODIFY COLUMN `source_session_id` VARCHAR(64)  DEFAULT NULL COMMENT '来源会话ID',
  MODIFY COLUMN `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  MODIFY COLUMN `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间';
