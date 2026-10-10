# MySQL 迁移指南（阶段 2 交付物）

> 适用版本：二次开发 V1.0 · 2026-10-10
> 迁移路线：**路线 B** —— 业务数据迁 MySQL 8.0+，向量存储保留 PostgreSQL + pgvector
> 代码改造已完成并通过全部单元测试（23/23），本文档说明数据库侧操作步骤。

## 1. 架构变更总览

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| 业务表（17 张） | PostgreSQL（argus_rag 库） | MySQL 8.0+（建议库名 argus_rag） |
| 向量表（vector_store） | PostgreSQL + pgvector | 不变（建议独立库 argus_rag_vector） |
| 表名 `groups` | `groups` | `argus_groups`（MySQL 8.0 保留字规避） |
| 应用数据源 | 单数据源 | 双数据源（`spring.datasource` → MySQL；`argus.vector.datasource` → PG） |

## 2. 初始化步骤（空环境部署）

### 2.1 MySQL（业务库）

```bash
# 建库（utf8mb4）
mysql -h <host> -u root -p -e "CREATE DATABASE IF NOT EXISTS argus_rag DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 建表（脚本幂等，可重复执行）
mysql -h <host> -u root -p argus_rag < sql/schema-mysql.sql
```

### 2.2 PostgreSQL（向量库，需 pgvector 扩展）

```bash
psql -h <host> -U <user> -d postgres -c "CREATE DATABASE argus_rag_vector;"
psql -h <host> -U <user> -d argus_rag_vector -f sql/schema-pgvector.sql
```

### 2.3 应用配置

`application-local.yml`（不入库）参照 `application-dev.yml` 填写：

```yaml
spring:
  datasource:                      # 业务库：MySQL
    url: jdbc:mysql://localhost:3306/argus_rag?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: <mysql-user>
    password: <mysql-password>
    driver-class-name: com.mysql.cj.jdbc.Driver

argus:
  vector:
    datasource:                    # 向量库：PostgreSQL + pgvector
      url: jdbc:postgresql://localhost:5432/argus_rag_vector
      username: <pg-user>
      password: <pg-password>
      driver-class-name: org.postgresql.Driver
```

## 3. 存量数据迁移（已有 PG 数据的环境）

> 原则：**迁移窗口内先备份，校验通过前不删旧库**。

1. 备份：`pg_dump -h <host> -U <user> argus_rag > backup-$(date +%F).sql`
2. 表结构与数据分批导出/导入（17 张业务表，注意 `groups` → `argus_groups` 改名映射；JSONB → JSON、BOOLEAN → TINYINT 由驱动自动兼容）。
3. `vector_store` 表数据不迁移——文档重新摄入即可重建向量（或用 `COPY` 原样迁移）。

### 3.1 数据校验（行数 + 关键唯一键）

```sql
-- 在 PG（旧库）与 MySQL（新库）分别执行，逐表比对行数
SELECT 'users' t, count(*) FROM users
UNION ALL SELECT 'user_refresh_tokens', count(*) FROM user_refresh_tokens
UNION ALL SELECT 'argus_groups', count(*) FROM argus_groups
UNION ALL SELECT 'group_memberships', count(*) FROM group_memberships
UNION ALL SELECT 'group_invitations', count(*) FROM group_invitations
UNION ALL SELECT 'group_join_requests', count(*) FROM group_join_requests
UNION ALL SELECT 'documents', count(*) FROM documents
UNION ALL SELECT 'document_upload_sessions', count(*) FROM document_upload_sessions
UNION ALL SELECT 'document_upload_chunks', count(*) FROM document_upload_chunks
UNION ALL SELECT 'document_chunks', count(*) FROM document_chunks
UNION ALL SELECT 'ingestion_jobs', count(*) FROM ingestion_jobs
UNION ALL SELECT 'assistant_sessions', count(*) FROM assistant_sessions
UNION ALL SELECT 'assistant_messages', count(*) FROM assistant_messages
UNION ALL SELECT 'assistant_session_contexts', count(*) FROM assistant_session_contexts
UNION ALL SELECT 'llm_usage_records', count(*) FROM llm_usage_records
UNION ALL SELECT 'qa_records', count(*) FROM qa_records
UNION ALL SELECT 'qa_record_citations', count(*) FROM qa_record_citations;
```

业务校验：注册/登录 → 建群 → 上传文档 → ETL 完成 → 问答 → 删除文档 全链路回归。

## 4. 回滚方案

- 代码回滚：`git revert` 迁移提交，恢复单数据源（PG）配置。
- 数据回滚：MySQL 数据可随时导回 PG（反向映射 `argus_groups` → `groups`），或直接使用第 3 步的备份 `backup-*.sql`。
- 双写过渡（可选）：数据量大时可在业务 Service 层临时加双写，观察 1-2 个业务周期后切换读流量。

## 5. SQL 兼容性改造清单（代码侧，已完成）

| 原语法（PostgreSQL） | 出现位置 | MySQL 改法 |
|---|---|---|
| `RETURNING id` ×3 | 群组/邀请/加入申请插入 | MyBatis-Plus `insert(entity)` / `useGeneratedKeys` 回填主键 |
| `ON CONFLICT ... DO UPDATE` ×2 | 分片 upsert、会话上下文 upsert | `ON DUPLICATE KEY UPDATE` |
| `metadata_json::text` ×2 | 切片查询 | 直接输出 JSON 列 |
| `EXTRACT(EPOCH FROM ...)` ×5 | 用量统计 | `TIMESTAMPDIFF(SECOND, ...)` |
| `TO_CHAR(..., 'YYYY-MM-DD')` ×2 | 每日趋势 | `DATE_FORMAT(..., '%Y-%m-%d')` |
| 表名 `groups` | 实体 + 4 个 XML | 更名 `argus_groups` |
| `BIGSERIAL/JSONB/BOOLEAN/部分索引` | schema.sql | 见 `sql/schema-mysql.sql` 头部映射说明 |
