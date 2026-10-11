# MySQL 迁移规范

本规范描述 Argus 业务数据从 PostgreSQL 迁移到 MySQL 8.0 的技术要求。

## 保留字处理

PostgreSQL 的 groups 表在迁移到 MySQL 时必须改名为 argus_groups，因为 groups 是 MySQL 8.0 的保留字，直接使用会导致建表和查询失败。

## 字段类型映射规则

迁移时的字段类型映射规则如下：BIGSERIAL 映射为 BIGINT AUTO_INCREMENT；JSONB 映射为 JSON；BOOLEAN 映射为 TINYINT(1)；TEXT 长文本字段映射为 MEDIUMTEXT。

## SQL 语法转换规则

PostgreSQL 专有语法必须按以下规则转换：RETURNING id 改为 MyBatis-Plus 的 useGeneratedKeys 主键回填；ON CONFLICT DO UPDATE 改为 ON DUPLICATE KEY UPDATE；EXTRACT(EPOCH FROM ...) 改为 TIMESTAMPDIFF；TO_CHAR 改为 DATE_FORMAT。

## 字符集与引擎

MySQL 业务库统一使用 utf8mb4 字符集和 InnoDB 存储引擎，排序规则为 utf8mb4_0900_ai_ci。连接串必须设置 serverTimezone 为 Asia/Shanghai。
