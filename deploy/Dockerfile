# ---- Argus 后端镜像 ----
# 构建：先在 Argus-backend 下执行 ./mvnw clean package -DskipTests
# 然后在仓库根目录执行：docker build -f deploy/Dockerfile -t argus-backend:latest .
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 时区与字符集
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1024m -Dfile.encoding=UTF-8"

COPY Argus-backend/target/argus-backend-*.jar app.jar

EXPOSE 10001

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://127.0.0.1:10001/actuator/health | grep -q '"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
