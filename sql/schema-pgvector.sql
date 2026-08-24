-- ============================================================
-- Cyrene Agent - RAG知识库向量表 (PostgreSQL pgvector)
-- Run: psql -U postgres -f schema-pgvector.sql
-- ============================================================

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 知识库文档表（含向量嵌入）
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id              BIGSERIAL PRIMARY KEY                COMMENT '自增主键',
    collection      VARCHAR(128)    NOT NULL DEFAULT 'default' COMMENT '知识库集合名称',
    source          VARCHAR(512)    DEFAULT NULL         COMMENT '来源文件名',
    content         TEXT            NOT NULL             COMMENT '文本块内容',
    embedding       vector(1024)    NOT NULL             COMMENT '向量嵌入（维度需匹配embedding模型）',
    metadata        JSONB           DEFAULT NULL         COMMENT '扩展元数据',
    chunk_index     INT             DEFAULT NULL         COMMENT '文本块在原文中的序号',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW() COMMENT '创建时间'
);

CREATE INDEX IF NOT EXISTS idx_knowledge_collection ON knowledge_documents (collection);

-- HNSW 索引用于快速近似最近邻搜索
-- 向量维度 (1024) 需与 embedding 模型匹配
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding
    ON knowledge_documents
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 查询示例：按集合 + 相似度搜索
-- SELECT id, content, source, 1 - (embedding <=> $1::vector) AS score
-- FROM knowledge_documents
-- WHERE collection = $2
-- ORDER BY embedding <=> $1::vector
-- LIMIT $3;

-- ============================================================
-- 全文检索支持 (Fulltext Search)
-- 需要先运行此段 SQL 才能使用 HARNESS_RAG_FULLTEXT_ENABLED=true
-- ============================================================

-- 自动生成 tsvector 列（content 变化时自动更新）
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

-- GIN 索引加速全文检索
CREATE INDEX IF NOT EXISTS idx_knowledge_fts
    ON knowledge_documents USING GIN (content_tsv);

-- 查询示例：按集合 + 全文检索
-- SELECT id, content, source,
--        ts_rank_cd(content_tsv, plainto_tsquery('english', $1)) AS score
-- FROM knowledge_documents
-- WHERE collection = $2
--   AND content_tsv @@ plainto_tsquery('english', $1)
-- ORDER BY score DESC
-- LIMIT $3;
