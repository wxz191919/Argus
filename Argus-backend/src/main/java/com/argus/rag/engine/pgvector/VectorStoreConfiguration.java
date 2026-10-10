package com.argus.rag.engine.pgvector;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PgVector 向量存储装配配置。
 * <p>
 * 二次开发改造说明：原项目通过 Spring AI 的 PgVector 自动装配
 * （{@code PgVectorStoreAutoConfiguration}）创建向量存储，自动装配默认使用主
 * {@code JdbcTemplate}——业务库迁移 MySQL 后，主数据源已切换为 MySQL，
 * 自动装配会把向量表建到 MySQL 上导致启动失败。
 * <p>
 * 因此改为手动装配：基于 {@code vectorDataSource}（PostgreSQL，见
 * {@link com.argus.rag.common.config.DataSourceConfiguration}）构建专属
 * {@link JdbcTemplate}，并向容器注册 {@link PgVectorStore}。
 * 容器中已存在同名 Bean 时，Spring AI 的自动装配会自动退避
 * （{@code @ConditionalOnMissingBean}），无需额外排除配置。
 * <p>
 * 检索参数与原 {@code spring.ai.vectorstore.pgvector} 配置保持一致：
 * 512 维（text-embedding-v3）、HNSW 索引、余弦距离。
 * 建表 DDL 由 {@code sql/schema-pgvector.sql} 管理（initializeSchema = false）。
 *
 * @author Argus-RAG Team
 * @see com.argus.rag.common.config.DataSourceConfiguration 双数据源定义
 * @see PgVectorRetrievalAdapter 向量检索统一入口（业务侧无感知）
 */
@Configuration
public class VectorStoreConfiguration {

    /** 嵌入向量维度，与 text-embedding-v3 及 vector_store 表定义保持一致 */
    private static final int EMBEDDING_DIMENSIONS = 512;

    /**
     * 向量库专属 JdbcTemplate，固定绑定 vectorDataSource（PostgreSQL + pgvector），
     * 与主库（MySQL）的 MyBatis-Plus 数据访问完全隔离。
     */
    @Bean
    public JdbcTemplate vectorJdbcTemplate(
            // 显式按名注入 vectorDataSource（PostgreSQL）：主数据源标记了 @Primary，
            // 不加 Qualifier 时 Spring 按类型注入会优先选择 primary（MySQL）。
            @Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(vectorDataSource);
    }

    /**
     * PgVector 向量存储实例。
     * <p>
     * 不启用 initializeSchema：vector_store 表结构与 HNSW 索引由
     * {@code sql/schema-pgvector.sql} 显式管理，保证部署可重复、可审计。
     */
    @Bean
    public PgVectorStore vectorStore(JdbcTemplate vectorJdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(vectorJdbcTemplate, embeddingModel)
                .dimensions(EMBEDDING_DIMENSIONS)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false)
                .build();
    }
}
