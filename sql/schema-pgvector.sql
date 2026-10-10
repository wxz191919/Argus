-- ===================================================
-- Argus 向量库初始化 DDL（PostgreSQL + pgvector）
-- ===================================================
-- 二次开发改造说明：
--   业务库已迁移 MySQL（见 sql/schema-mysql.sql），本库仅承载向量存储。
--   连接配置见 application-dev.yml 的 argus.vector.datasource.*，
--   由 VectorStoreConfiguration 装配的 PgVectorStore 独占使用。
--
--   前置条件：PostgreSQL 16+ 且已安装 pgvector 扩展。
--   初始化：
--     psql -h <host> -U <user> -d <vector_db> -f sql/schema-pgvector.sql
-- ===================================================

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 向量存储表（由 Spring AI PGvector 使用，检索参数：512 维 / HNSW / 余弦距离）
CREATE TABLE IF NOT EXISTS vector_store (
    id          VARCHAR(36)     PRIMARY KEY,
    content     TEXT,
    metadata    JSONB,
    embedding   VECTOR(512)
);

COMMENT ON TABLE  vector_store            IS '向量存储表（PGvector），业务库迁 MySQL 后独立承载';
COMMENT ON COLUMN vector_store.id         IS '向量条目唯一标识（UUID）';
COMMENT ON COLUMN vector_store.content    IS '原始文本内容';
COMMENT ON COLUMN vector_store.metadata   IS '元数据（JSONB 格式，含 document_id、chunk_index、source、groupId 等）';
COMMENT ON COLUMN vector_store.embedding  IS '文本嵌入向量（512 维，text-embedding-v3）';

-- HNSW 索引（加速近似最近邻搜索）
CREATE INDEX IF NOT EXISTS idx_vector_embedding_hnsw
    ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);
