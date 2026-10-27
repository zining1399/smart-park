# 附录 05 · 部署运维方案（陆上线 · 部署运维工程师）

> 归档说明：本文件为成员产出原文归档，版本链 v1.0（全案）→ v1.1（MySQL 运维包整合）→ v1.2（联审修复）→ v1.3（子网断链+3306 收口）→ **v1.4（终审修复+冻结）**→ v1.4.1（环境变量命名对齐）→ v1.4.2（actuator 占位/TraceIdFilter 归属/CI 自检）。**以 §2.6'/§2.7' 的 v1.4 冻结版 compose 与 init 全文为最终落地版本**，替换 v1.0 中的对应内容；其余以 v1.0 全文为准并叠加各版本增量注记。以 `docs/delivery-improvement-plan.md` 为执行基准。

---

## 0. 现状实勘结论（影响方案的硬事实）

1. 后端 Spring Boot 3.2.12 / Java 17 / Maven，端口 8080，context-path `/api`；当前无 actuator、无 test、无 MySQL 驱动、无 Flyway 依赖。
2. 前端 Vue3 + Vite5，**无 package-lock.json**（CI `npm ci` 会失败，需先生成提交）。
3. `schema.sql` DROP + `sql.init.mode: always`，生产必须切断。
4. 根目录非 git 仓库，无任何部署文件——第一步 git 初始化。
5. 部署目标为**客户内网单机/少量 Linux 主机，无 K8s**，docker compose 为主。

## 1. 环境规划

| 环境 | 用途 | 数据 | 配置 | 访问 |
|------|------|-----|------|------|
| dev | 日常开发 | 本地 H2 或 MySQL 容器 | application-dev.yml + application-local.yml（gitignore） | 开发本人 |
| staging | 客户验收/演练 | MySQL 容器独立卷 | .env.staging + Profile staging | 实施方+客户接口人 |
| prod | 客户生产 | MySQL 数据卷 + 每日备份 | .env（现场生成，**永不进 git**）+ Profile prod | 客户运维 |

`application.yml` 公共骨架（v1.4.2 定稿，actuator 占位切换）：

```yaml
server:
  port: 8080
  servlet:
    context-path: /api
spring:
  application:
    name: smart-park-backend
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: GMT+8
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false
management:
  endpoints:
    web:
      exposure:
        include: ${ACTUATOR_EXPOSE:health}   # 默认仅 health；选装监控 env 切换，免发版
  endpoint:
    health:
      show-details: never
      probes:
        enabled: true
  health:
    db:
      enabled: true
```

`application-prod.yml`：MySQL 数据源（环境变量注入）+ Flyway（迁移账号分离）+ `sql.init.mode: never` + h2 console 关闭 + `app.jwt.secret: ${SMARTPARK_JWT_SECRET}` + 日志 info。dev profile 保留 H2 + sql.init always（演示模式）。

前端 `.env.*`：`VITE_API_BASE_URL=/api`（同域反代默认）。

**数据安全红线**：生产库结构唯一来源是 Flyway `V1__init_mysql.sql`（无 DROP，由 data-engineer 出，见附录 03）；`mode: never`；MySQL 命名卷持久化；每日 **02:30** 备份【口径变更：本评审稿原定 02:00，全队统一改为 02:30，与 `scripts/backup.sh` 的 crontab `30 2 * * *` 及巡检"mtime<25h"断言一致；注意区分——下文的"发布窗口避开 02:00±1h"是**发布窗口**口径，不是备份时间，不要一起改】（发布窗口避开 02:00±1h，`--single-transaction` 与 Flyway DDL 并发有风险）。

## 2. 容器化

### 2.1 后端 `smart-park-backend/Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1.7
########## 阶段一：构建 ##########
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

########## 阶段二：运行（JRE + 非 root）##########
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -g 1001 app && useradd -u 1001 -g app -m -s /usr/sbin/nologin app
WORKDIR /app
COPY --from=builder --chown=app:app /build/target/*.jar app.jar
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
  CMD curl -fsS http://localhost:8080/api/actuator/health | grep -q '"UP"' || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
```

要点：pom 先行做依赖层缓存；JRE-only；非 root；`MaxRAMPercentage` 自适应容器内存；健康检查走 Actuator；**jammy + runtime 装 curl**（防 slim/alpine 镜像 `curl: not found` 导致 healthcheck 永不健康的同症状故障；CI 已加镜像自检）。

### 2.2 前端 `smart-park-frontend/Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1.7
FROM node:18-alpine AS builder
WORKDIR /build
ARG NPM_REGISTRY=https://registry.npmmirror.com
COPY package.json package-lock.json ./
RUN npm config set registry $NPM_REGISTRY && npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html
ENV TZ=Asia/Shanghai
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost/ >/dev/null || exit 1
```

⚠️ 依赖：**当前没有 package-lock.json，`npm ci` 会失败**，落地前必须 `npm install --package-lock-only` 并提交。

### 2.5 前端 `nginx.conf`

```nginx
server {
    listen 80;
    server_name _;
    client_max_body_size 20m;
    server_tokens off;
    gzip on;
    gzip_min_length 1k;
    gzip_comp_level 5;
    gzip_types text/plain text/css application/javascript application/json image/svg+xml;

    location /assets/ {
        root /usr/share/nginx/html;
        expires 30d;
        add_header Cache-Control "public, immutable";
    }
    location / {
        root /usr/share/nginx/html;
        index index.html;
        try_files $uri $uri/ /index.html;    # SPA history fallback，解决刷新 404
    }
    location = /api/actuator/health {
        proxy_pass http://backend:8080/api/actuator/health;
        access_log off;
    }
    location /api/actuator/ {
        return 403;
    }
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }
}
```

### 2.6' docker-compose.yml —— v1.4 冻结版（最终落地版本）

```yaml
name: smart-park

services:
  mysql:
    image: mysql:8.0.39
    container_name: smart-park-mysql
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-smart_park}
      # init 脚本所需变量，与授权 host 段推导同源
      SP_NET_SUBNET: ${SP_NET_SUBNET:-172.28.0.0/24}
      MYSQL_APP_USER: ${MYSQL_APP_USER:-smartpark_app}
      MYSQL_MIG_USER: ${MYSQL_MIG_USER:-smartpark_mig}
      MYSQL_BAK_USER: ${MYSQL_BAK_USER:-smartpark_bak}
      MYSQL_APP_PASSWORD: ${MYSQL_APP_PASSWORD}
      MYSQL_MIG_PASSWORD: ${MYSQL_MIG_PASSWORD}
      MYSQL_BAK_PASSWORD: ${MYSQL_BAK_PASSWORD}
    volumes:
      - mysql-data:/var/lib/mysql
      - ./mysql/my.cnf:/etc/mysql/conf.d/smart-park.cnf:ro
      - ./mysql/init:/docker-entrypoint-initdb.d:ro
      - ./mysql/bak.cnf:/run/secrets/bak.cnf:ro
    # 3306 不发布到宿主机：备份/DBA/导入统一 docker exec（socket），全栈对外仅 nginx 一个入口
    healthcheck:
      test: ["CMD-SHELL", "MYSQL_PWD=$$MYSQL_ROOT_PASSWORD mysqladmin ping -u root"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 60s
    networks:
      - smart-park-net

  backend:
    image: smart-park-backend:${APP_VERSION:-1.0.0}
    build: ./smart-park-backend
    container_name: smart-park-backend
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/${MYSQL_DATABASE:-smart_park}?useUnicode=true&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&connectionCollation=utf8mb4_0900_ai_ci&allowMultiQueries=false
      SPRING_DATASOURCE_USERNAME: ${MYSQL_APP_USER:-smartpark_app}
      SPRING_DATASOURCE_PASSWORD: ${MYSQL_APP_PASSWORD}
      SPRING_FLYWAY_USER: ${MYSQL_MIG_USER:-smartpark_mig}
      SPRING_FLYWAY_PASSWORD: ${MYSQL_MIG_PASSWORD}
      SMARTPARK_JWT_SECRET: ${SMARTPARK_JWT_SECRET}
      SMARTPARK_CORS_ORIGINS: ${SMARTPARK_CORS_ORIGINS:-}
      JAVA_OPTS: ${JAVA_OPTS:--XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai}
    depends_on:
      mysql:
        condition: service_healthy
    volumes:
      - ./logs/backend:/app/logs
    deploy:
      resources:
        limits:
          memory: 1g
    # 8080 不对宿主机发布，仅 compose 内网给 nginx 反代
    # actuator 未就绪时联调兜底（正式交付前必须切回）：
    # test: ["CMD-SHELL", "curl -fsS http://localhost:8080/api/actuator/health || timeout 2 bash -c '</dev/tcp/localhost/8080'"]
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/api/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 120s
    networks:
      - smart-park-net

  frontend:
    image: smart-park-frontend:${APP_VERSION:-1.0.0}
    build: ./smart-park-frontend
    container_name: smart-park-frontend
    restart: unless-stopped
    ports:
      - "${NGINX_PORT:-80}:80"
    depends_on:
      backend:
        condition: service_healthy    # 首启等 Flyway 迁移完成后放流量
    networks:
      - smart-park-net

volumes:
  mysql-data:
    name: smart-park-mysql-data

networks:
  smart-park-net:
    name: smart-park-net
    driver: bridge
    ipam:
      config:
        - subnet: ${SP_NET_SUBNET:-172.28.0.0/24}
```

**v1.2-v1.4 修复要点（防复发）**：①init 曾引用尚不存在的 `flyway_schema_history` 表报 1146 使首次部署必失败（库级授权对新建表自动生效，该行多余已删）；②`SP_NET_SUBNET` 曾只在 compose 层解析不注入容器 env（改子网后应用全连不上库），现显式注入 + fail-fast；③mysql 容器曾缺 6 个 init 变量（`set -u` 下容器起不来、全栈瘫痪），现补齐 + 密码强断言；④JDBC URL 曾用 `characterEncoding=utf8`（3 字节残缺 utf8mb3），已删参数改 `connectionCollation=utf8mb4_0900_ai_ci`。

### 2.7' mysql/init/01-accounts.sh —— v1.4 终稿

```bash
#!/bin/bash
# 挂载 /docker-entrypoint-initdb.d/，仅首次建卷执行一次
set -euo pipefail
: "${SP_NET_SUBNET:?SP_NET_SUBNET 未注入容器 env：检查 compose mysql.environment 段}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 未注入}"
: "${MYSQL_APP_PASSWORD:?MYSQL_APP_PASSWORD 未注入}"
: "${MYSQL_MIG_PASSWORD:?MYSQL_MIG_PASSWORD 未注入}"
: "${MYSQL_BAK_PASSWORD:?MYSQL_BAK_PASSWORD 未注入}"
MYSQL_APP_USER="${MYSQL_APP_USER:-smartpark_app}"
MYSQL_MIG_USER="${MYSQL_MIG_USER:-smartpark_mig}"
MYSQL_BAK_USER="${MYSQL_BAK_USER:-smartpark_bak}"
MYSQL_DATABASE="${MYSQL_DATABASE:-smart_park}"
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"
# 保持默认 socket 连接（不写 -h），勿在调试时改成 -h 127.0.0.1
HOST_PREFIX=$(echo "$SP_NET_SUBNET" | cut -d. -f1-2)
APP_HOST="'${MYSQL_APP_USER}'@'${HOST_PREFIX}.%'"
MIG_HOST="'${MYSQL_MIG_USER}'@'${HOST_PREFIX}.%'"

mysql -uroot <<SQL
CREATE USER ${APP_HOST} IDENTIFIED BY '${MYSQL_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON ${MYSQL_DATABASE}.* TO ${APP_HOST};
CREATE USER ${MIG_HOST} IDENTIFIED BY '${MYSQL_MIG_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER, REFERENCES, CREATE VIEW
  ON ${MYSQL_DATABASE}.* TO ${MIG_HOST};
CREATE USER '${MYSQL_BAK_USER}'@'localhost' IDENTIFIED BY '${MYSQL_BAK_PASSWORD}';
GRANT SELECT, LOCK TABLES, RELOAD, REPLICATION CLIENT, PROCESS ON *.*
  TO '${MYSQL_BAK_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL
```

三账号分权：应用仅 DML（注入破坏半径限数据层）/ 迁移账号仅 Flyway 执行期（含 DDL）/ 备份账号只读+锁表走 socket。`my.cnf`（data-engineer 出）：双一持久化、binlog ROW/MINIMAL、慢日志 2s、max_connections=200、`skip-name-resolve`（禁反向 DNS，防客户 DNS 慢导致偶发超时）。

### .env.example（v1.4.1 终版命名）

```bash
# 复制为 .env 后填写；.env 严禁拷出生产机、严禁提交 git
APP_VERSION=1.0.0                    # 回滚即改此行
MYSQL_ROOT_PASSWORD=                 # openssl rand -base64 24
MYSQL_DATABASE=smart_park
MYSQL_APP_USER=smartpark_app         # 应用运行时（仅 DML）
MYSQL_APP_PASSWORD=
MYSQL_MIG_USER=smartpark_mig         # 发布迁移专用（含 DDL）
MYSQL_MIG_PASSWORD=
MYSQL_BAK_USER=smartpark_bak         # 备份专用（只读+锁表）
MYSQL_BAK_PASSWORD=
SMARTPARK_JWT_SECRET=                # openssl rand -base64 48，HS256 ≥32 字节
SMARTPARK_CORS_ORIGINS=              # 同域部署留空；分域名时逗号分隔
NGINX_PORT=80
JAVA_OPTS=
SP_NET_SUBNET=172.28.0.0/24          # 客户内网占用 172.28.x 时改（账号授权自动联动）
ACTUATOR_EXPOSE=health               # 选装监控时切 health,metrics,prometheus
```

**命名规则**：Spring 标准变量管基础设施（SPRING_DATASOURCE_*）、`SMARTPARK_` 前缀管应用级（JWT/CORS）——一套东西一个名字，杜绝"compose 注入 A、代码读 B"的静默空值。

`.gitignore`（根目录）：target/、node_modules/、dist/、data/、logs/、*.mv.db、.env*、.idea/、.vscode/ 等（全文见任务交付）。

**一键部署**：有外网 `docker compose --env-file .env up -d`；离线 `docker load -i images/smart-park-images.tar.gz && docker compose --env-file .env up -d`；交付包含镜像 tar、compose、nginx.conf、.env.example、deploy.sh、backup.sh、运维手册。

## 3. CI/CD（GitHub Actions，否决 GitLab CI / Jenkins / 手工脚本）

四 job：backend（mvn verify，qa 用例就绪后去 -DskipTests）→ frontend（npm ci + build + vitest）→ **images（needs [backend,frontend]，测试不过不出镜像；buildx 分层缓存推送 ghcr）** → release（仅 tag 触发，GitHub Environments 人工审批，`docker save` 出离线交付包 + 版本号写入 .env.example）。CI release job 含**镜像自检**：`docker run --rm <镜像> which curl`，缺失即 fail 构建。内网降级：`scripts/release-pack.sh` 本地等价打包 + git tag 人工登记版本台账。

版本策略：git tag 语义化版本 = 镜像 tag 一一对应 + `{branch}-{sha}` 追溯；现场切换版本唯一开关 = `.env` 的 `APP_VERSION`。

## 4. 发布策略与回滚

- 单机简化蓝绿：切版本 = 改 `APP_VERSION` 重建 backend/frontend 容器，MySQL 不动，窗口 <30s；staging 先跑 24h 冒烟 → 生产切换 → 观察 30 分钟（错误率/ERROR 日志/核心页面点检）。
- Flyway 迁移先于应用自动执行；**所有迁移脚本向后兼容一个版本**（加列可空/带默认、跨版本删列）。
- 回滚触发：发布 30 分钟内 5xx>5% / 健康检查不过 / 启动失败 → 改 APP_VERSION 重建，<1 分钟；数据损坏 → 停 backend → 恢复最近备份 → 起旧版本（5~15 分钟）。
- **回滚铁律**：旧版本镜像永不主动删除（磁盘 80% 告警才清 N-2 前）；数据库不随代码回滚，靠"向后兼容 + 备份"双保险。staging 每次演练必含一次"发布→制造故障→回滚"。

## 5. 可观测性

- **Actuator**：默认仅 health（占位切换）；三层防护——8080 不对宿主机发布、Nginx 对 /api/actuator/ 除 health 一律 403、SecurityConfig 恒只放行 /actuator/health。
- **logback-spring.xml**：dev 控制台；staging/prod JSON 结构化滚动文件（100MB/文件、30 天、5GB 上限、gzip）+ 异步 appender（neverBlock）+ ERROR 单独落盘；卷 `./logs/backend:/app/logs` 持久化。
- **TraceIdFilter**（模板在此，实现归 backend-engineer，与安全骨架同班车）：MDC key `traceId`、`X-Trace-Id` 头（入参 `[0-9a-f]{8,64}` 校验防日志注入、格式不过则忽略生成新值 + WARN 记录、响应回写）、`@Component @Order(HIGHEST_PRECEDENCE)`、`finally MDC.clear()`。前端 axios 请求头生成 + 错误文案带追踪号；排障 `grep '"traceId":"xxx"' smart-park.json`。
- **告警**：Prometheus+Grafana 可选附加包（--profile monitoring，客户不装零侵入）；**保底轻量巡检** crontab 每分钟：容器 healthy / 磁盘 ≥80% / 近 1 分钟 ERROR 增量 / **备份文件 mtime <25h 断言（超龄即 P1——很多事故是备份静默失败数月无人知）**。告警规则要点：服务不可用 P0、连接池耗尽 P1、5xx 错误率>5% P1、慢接口 P2、磁盘 P1。
- 排查口诀：backend 长期 starting/unhealthy → **一查 curl、二查 actuator、三查应用**（三个失效点同一症状：frontend 永不启动）。

## 6. 备份与恢复（v1.4 终版）

```bash
#!/bin/bash
# /opt/smart-park/scripts/backup.sh —— crontab 每日 02:30（原稿 02:00，全队统一为 02:30，即 `30 2 * * *`）
set -euo pipefail
DATE=$(date +%Y%m%d_%H%M)
DIR=/opt/smart-park/backup
RETENTION_DAYS=30
mkdir -p "$DIR"
docker exec -i smart-park-mysql sh -c \
  'exec mysqldump --defaults-extra-file=/run/secrets/bak.cnf \
   --single-transaction --master-data=2 --routines --triggers \
   --set-gtid-purged=OFF smart_park' | gzip > "$DIR/full_${DATE}.sql.gz"
find "$DIR" -name "full_*.sql.gz" -mtime +${RETENTION_DAYS} -delete
echo "[$(date '+%F %T')] backup ok: full_${DATE}.sql.gz ($(du -h "$DIR/full_${DATE}.sql.gz" | cut -f1))"
```

要点：`docker exec -i` **不带 -t**（防 CR 污染 gzip 流）；凭证走挂载密钥文件；备份失败或 mtime>25h 即 P1 告警；binlog 7 天支撑 PITR；恢复演练每季度一次并记录实测 RTO（写进客户验收清单）；备份文件加密存储（库内尚有明文身份证阶段）+ 异机一份。

**H2 存量迁移管道（v1.4 C 方案）**：H2 导 CSV → `scripts/csv-to-inserts.py` 转 INSERT 语句（**首尾包事务 `SET autocommit=0`/`COMMIT`，防半成品数据库**——QA TC-MIG-08 依赖）→ `docker exec -i` 管道导入（MySQL 8 `local_infile` 默认 OFF，不常开安全开关）→ 四步校验。导入前断言 `@@sql_mode` 含 `STRICT_TRANS_TABLES` + 零值日期检出（写进运维手册）。

## 7. 上线检查单（合并各版）

**上线前**：git 仓库+.gitignore 生效（status 无 target/node_modules/dist/.env）；Flyway V1 评审通过；package-lock 已提交；staging 全量冒烟通过；回滚演练完成留记录；.env 密钥用随机命令生成；备份盘+crontab 配置；离线镜像 `docker load` 成功且 digest 与交付清单一致；**客户 LAN 未占用容器网段**（`ip -4 route | grep '^172\.28\.'` 应为空）；CI 镜像自检（which curl）通过。

**上线中**：三容器全部 healthy（frontend 最后）；Flyway 日志确认迁移执行且仅一次；时区四值验收（`SELECT NOW(), UTC_TIMESTAMP(), @@global.time_zone` 与北京时间一致、global=+08:00）；**重启 backend 一次抽查明细表行数不变（sql.init=never 实测闭环，最高危项）**；`curl .../api/h2-console` 返回 404；app 账号执行 DROP 应 Access denied（最小权限实测）；核心功能点检；首次备份手工触发并验证可恢复。

**上线后**：30 分钟观察期；24 小时复检日志滚动与磁盘增速；旧版本镜像保留；运维手册移交 + 培训。

## 8. 运维手册要点

**日常巡检**：`docker compose ps` 三行 healthy；健康检查 curl；`tail -100` error 日志；`df -h`；`docker stats --no-stream`。
**红线**：**销毁 `mysql-data` 卷 = 危险操作**（init 仅首次建卷执行，销毁即数据不可恢复），仅恢复演练场景允许；归档作业每月低峰分批 5000 行 + sleep 0.1s，执行前先当日备份；扩容实操上限 5 个后端实例。
**变更步骤**：应用挪出 compose/改连已有 MySQL → host 授权失效，调整 SP_NET_SUBNET 或对目标 IP 追加授权 → 重启；分离部署时 useSSL 必须 true+证书，allowPublicKeyRetrieval=true 仅限 compose 内网明文链路。
**排障**：后端起不来看 Flyway/连接池报错；刷新 404 查 try_files；502 查 backend healthy；凭追踪号 grep 日志；磁盘涨先清日志与旧备份**绝不动 mysql-data 卷**。

## 9. 工作量与实施顺序

| 序 | 工作项 | 人日 | 依赖 |
|----|--------|------|------|
| P0 | git init + .gitignore + package-lock | 0.5 | 无 |
| P1 | MySQL 迁移（Flyway V1，data-engineer 出） | 2 | P0 |
| P2 | 后端改造（依赖+Profile+Actuator+logback+TraceIdFilter，按附录 02） | 2 | P1 |
| P3 | 容器化（Dockerfile×2 + compose + nginx + .env + dockerignore） | 1 | P2 |
| P4 | CI workflow + 镜像自检（门禁随 qa 用例收紧） | 1 | P0 |
| P5 | 可观测附加包（Prometheus profile + 巡检脚本） | 1.5 | P3 |
| P6 | 备份脚本 + 运维手册 + 回滚演练 | 1 | P3 |
| P7 | staging 验收 + 割接 + 培训 | 1 | 全部 |

**合计 devops 侧 7 人日**（各版本修复均在预算内消化）；关键路径 P0→P1→P2→P3，P4/P5/P6 并行。
