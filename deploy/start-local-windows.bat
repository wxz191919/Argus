@echo off
chcp 65001 >nul
REM ============================================================
REM Argus 本机环境一键启动（Windows 便携版三件套）
REM 前提：组件位于 D:\argus-env（PG16+pgvector / MinIO / ES+IK）
REM 每个 服务会各占一个窗口，窗口保持打开即代表运行中，请勿关闭
REM ============================================================

set ENV_DIR=D:\argus-env

echo [1/3] 启动 PostgreSQL (5432)...
start "Argus-PostgreSQL" cmd /k "cd /d %ENV_DIR% && pg16\pgsql\bin\pg_ctl.exe -D pgdata -o "-p 5432" start && pause"

echo [2/3] 启动 MinIO (9000/9001)...
start "Argus-MinIO" cmd /k "cd /d %ENV_DIR% && set MINIO_ROOT_USER=argusminio&& set MINIO_ROOT_PASSWORD=ArgusMinio2026&& minio.exe server minio-data --address ":9000" --console-address ":9001""

echo [3/3] 启动 Elasticsearch (9200)...
start "Argus-Elasticsearch" cmd /k "cd /d %ENV_DIR%\elasticsearch-8.14.3 && bin\elasticsearch.bat"

echo.
echo 全部已在独立窗口启动。等待约 60 秒后验证：
echo   PostgreSQL : pg16\pgsql\bin\psql -h 127.0.0.1 -U argus -d argus_rag_vector -c "SELECT 1;"
echo   MinIO      : curl http://127.0.0.1:9000/minio/health/live
echo   ES         : curl http://127.0.0.1:9200/_cluster/health
echo 然后启动后端：
echo   cd Argus-backend ^&^& mvnw spring-boot:run
pause
