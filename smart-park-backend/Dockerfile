# syntax=docker/dockerfile:1.7
# ============================================================================
# smart-park 后端镜像：多阶段构建（Maven 构建 → JRE 运行）
# 关键约束：
#   - 运行阶段必须安装 curl（compose healthcheck 依赖，缺失会导致 frontend 永不启动）
#   - 非 root 用户运行
#   - JVM 参数走 JAVA_OPTS 环境变量（含 -Duser.timezone，防全库时间偏移 8 小时）
# ============================================================================

######################### 阶段一：构建 #########################
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷贝 pom 下载依赖 → 依赖层可缓存，业务代码变更不触发重新拉依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

######################### 阶段二：运行 #########################
FROM eclipse-temurin:17-jre-jammy

# curl：healthcheck 必需（/api/actuator/health）；tzdata：时区数据
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -g 1001 app \
 && useradd -u 1001 -g app -m -s /usr/sbin/nologin app

WORKDIR /app
COPY --from=builder --chown=app:app /build/target/*.jar app.jar

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

USER app
EXPOSE 8080

# 端点路径含 context-path /api；默认暴露 health（ACTUATOR_EXPOSE 切换）
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
  CMD curl -fsS http://localhost:8080/api/actuator/health | grep -q '"UP"' || exit 1

# 使用 exec 形式启动，保证 java 进程为 PID 1，可正确接收 SIGTERM（优雅停机）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
