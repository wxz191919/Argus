package com.argus.rag.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 双数据源配置（MySQL 业务库 + PostgreSQL 向量库）。
 * <p>
 * 二次开发改造说明：原项目业务数据与向量数据同库（PostgreSQL + pgvector）。
 * 业务库迁移 MySQL 后，数据库职责拆分为：
 * <ul>
 *   <li><b>主库（MySQL 8.0+）：</b>用户、群组、文档、ETL 任务、问答记录等全部业务表，
 *       由 {@code spring.datasource.*} 配置，供 MyBatis-Plus 与事务管理器使用，
 *       标记 {@link Primary} 保证自动装配（MyBatis-Plus、事务、Actuator 健康检查）全部指向 MySQL。</li>
 *   <li><b>向量库（PostgreSQL + pgvector）：</b>仅承载 {@code vector_store} 向量表，
 *       由 {@code argus.vector.datasource.*} 配置，仅供
 *       {@link com.argus.rag.engine.pgvector.VectorStoreConfiguration} 构建的 PgVectorStore 使用。</li>
 * </ul>
 * 向量检索已通过 Spring AI {@code VectorStore} 抽象层隔离（见 {@code PgVectorRetrievalAdapter}），
 * 业务代码无需感知底层向量数据库。
 *
 * @author Argus-RAG Team
 */
@Configuration
public class DataSourceConfiguration {

    /**
     * 主库（MySQL）连接属性，绑定 spring.datasource.*。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 主库（MySQL）连接池。MyBatis-Plus、@Transactional 事务、Actuator 健康检查均使用该数据源。
     * <p>
     * Hikari 专属调优参数可通过 {@code spring.datasource.hikari.*} 覆盖。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * 向量库（PostgreSQL + pgvector）连接属性，绑定 argus.vector.datasource.*。
     */
    @Bean
    @ConfigurationProperties("argus.vector.datasource")
    public DataSourceProperties vectorDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 向量库（PostgreSQL + pgvector）连接池。
     * <p>
     * Hikari 专属调优参数可通过 {@code argus.vector.datasource.hikari.*} 覆盖；
     * 向量库写入量远小于业务库，默认连接池尺寸即可满足。
     */
    @Bean(name = "vectorDataSource")
    @ConfigurationProperties("argus.vector.datasource.hikari")
    public HikariDataSource vectorDataSource(
            // 显式按名注入：主库 DataSourceProperties 标记了 @Primary，
            // 若不指定 Qualifier，Spring 会优先注入 primary（MySQL）属性，
            // 导致向量池错误地连接 MySQL（按参数名匹配不会覆盖 @Primary）。
            @Qualifier("vectorDataSourceProperties") DataSourceProperties vectorDataSourceProperties) {
        return vectorDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
