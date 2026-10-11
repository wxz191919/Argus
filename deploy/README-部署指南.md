# Argus RAG 平台部署指南

从空环境启动到完成第一次问答的完整步骤。

## 一、Docker Compose 部署（推荐，Linux/云服务器）

### 1. 前置条件

- Docker 24+ 与 Docker Compose v2（`docker compose version` 可用）
- 4C8G 及以上服务器（ES 建议至少 2G 内存）
- 一个 DashScope API Key（[开通地址](https://dashscope.console.aliyun.com/)）

### 2. 构建后端 JAR

```bash
cd Argus-backend
./mvnw clean package -DskipTests
cd ..
```

### 3. 配置环境变量

```bash
cp .env.example .env
vi .env   # 修改所有 change-me 密码，填入 DASHSCOPE_API_KEY
```

> ⚠️ `.env` 已被 gitignore，严禁提交真实密钥到仓库。

### 4. 一键启动

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

首次启动会自动完成：建库建表（`sql/schema-mysql.sql`、`sql/schema-pgvector.sql`
通过 docker-entrypoint-initdb.d 自动执行）→ ES 安装 IK 分词器 → 后端健康检查通过。

### 5. 验证

```bash
docker compose -f deploy/docker-compose.yml ps          # 全部 healthy
curl http://localhost:10001/actuator/health             # {"status":"UP"}
```

组件端口：后端 10001 ｜ MySQL 3306 ｜ PostgreSQL 5432 ｜ MinIO 9000/9001 ｜ ES 9200

### 6. 第一次问答

1. 注册账号：`POST /api/auth/register`（或使用前端页面）
2. 建群 → 上传文档 → 等待文档状态 READY
3. `POST /api/qa/ask {"groupId":N,"question":"..."}`

## 二、本机便携部署（Windows，无 Docker）

适用于开发环境，全部组件免安装（参考本项目实际运行环境）：

| 组件 | 获取方式 | 启动命令 |
|---|---|---|
| MySQL 8 | 官方安装器 | 服务方式运行 |
| PG16+pgvector | EDB 便携版 + conda-forge pgvector 预编译扩展 | `pg_ctl -D pgdata start` |
| MinIO | GitHub Release `minio.exe` | `minio.exe server minio-data` |
| ES+IK | 官方 zip + [IK 插件](https://release.infinilabs.com/analysis-ik/stable/)（版本必须与 ES 一致） | `bin\elasticsearch.bat` |
| 后端 | `./mvnw spring-boot:run`（profile=local） | - |

本机启动脚本见 `deploy/start-local-windows.bat`（双击依次拉起 PG/MinIO/ES）。

初始化数据库：

```bash
mysql -uroot -p -e "CREATE DATABASE argus_rag DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql -uroot -p argus_rag < sql/schema-mysql.sql
psql -U argus -d argus_rag_vector -f sql/schema-pgvector.sql
```

后端配置：复制 `application-dev.yml` 为 `application-local.yml`，填入真实凭据
（该文件已 gitignore），使用 `env -u SERVER__PORT` 启动可避免本机端口变量干扰。

## 三、数据持久化与备份

| Docker 卷 | 内容 | 备份建议 |
|---|---|---|
| `mysql-data` | 业务数据 | `mysqldump` 每日备份，保留 30 天 |
| `pgdata` | 向量数据 | 可重建（ETL 重跑即可生成），周备份足够 |
| `minio-data` | 原始文档 | **必须备份**（唯一原件） |
| `esdata` | 检索索引 | 无需备份（可全量重建） |

## 四、常见故障排查

| 现象 | 排查 |
|---|---|
| 后端起不来：端口占用 | `netstat -ano \| grep 10001`，或改 `BACKEND_PORT` |
| 后端起不来：DB 连接失败 | 检查 `.env` 密码与 `docker compose ps` 健康状态 |
| ETL 卡 PROCESSING | 运行时回收器 5 分钟/30 分钟阈值自动处理；或对 FAILED 文档调 `POST /api/documents/{id}/retry-ingestion?groupId=N` |
| ES 索引创建失败/分词不生效 | 确认镜像安装了与 ES 版本一致的 IK 插件（本仓库已内置） |
| 问答报 400 Arrearage | DashScope 账户欠费，充值即可 |
| 问答全部拒答 | 检查 DashScope Key 与群组内文档是否 READY |

## 五、安全清单

- [x] `.env` / `application-local.yml` 均已 gitignore，仓库无真实密钥
- [ ] 生产部署务必修改所有 `change-me` 默认密码与 JWT_SECRET
- [ ] 建议开启 ES `xpack.security` 并配置 MinIO bucket 策略（当前单机内网可暂缓）
- [ ] 登录接口限流（见阶段 6 报告 §4）
