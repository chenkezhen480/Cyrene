-- ============================================================
-- Harness Agent - PostgreSQL pgvector Schema
-- Run: psql -U postgres -f schema-pgvector.sql
-- ============================================================

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Knowledge base document table with vector embeddings
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id              BIGSERIAL PRIMARY KEY,
    collection      VARCHAR(128)    NOT NULL DEFAULT 'default',
    source          VARCHAR(512)    DEFAULT NULL,
    content         TEXT            NOT NULL,
    embedding       vector(1024)    NOT NULL,     -- dimension matches your embedding model
    metadata        JSONB           DEFAULT NULL,
    chunk_index     INT             DEFAULT NULL,
    prev_chunk_id   VARCHAR(64)     DEFAULT NULL,
    next_chunk_id   VARCHAR(64)     DEFAULT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_collection ON knowledge_documents (collection);

-- Migration for existing tables:
-- ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS chunk_index INT;
-- ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS prev_chunk_id VARCHAR(64);
-- ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS next_chunk_id VARCHAR(64);

-- HNSW index for fast approximate nearest neighbor search
-- Adjust vector dimension (1024) to match your embedding model
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding
    ON knowledge_documents
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Query helper: search by collection + similarity
-- Usage:
--   SELECT id, content, source, 1 - (embedding <=> $1::vector) AS score
--   FROM knowledge_documents
--   WHERE collection = $2
--   ORDER BY embedding <=> $1::vector
--   LIMIT $3;
