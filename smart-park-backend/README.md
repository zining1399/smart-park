# 智慧园区管理系统后端（smart-park-backend）

Spring Boot 3 后端工程，覆盖智慧门禁 / 访客接待 / 智慧仓管 / 告警中心 / 运营看板五大模块。
接口契约见 `../docs/api-contract.md`（唯一事实来源）。

## 技术栈

Java 17 · Spring Boot 3.2 · MyBatis-Plus 3.5 · **MySQL 8.0（≥ 8.0.16）** · Flyway · Spring Security + JWT（jjwt 0.12.6）· Actuator · Lombok · Maven

> H2 已降级为 `test` scope，**仅供单元/集成测试使用**，不再作为运行库。

## 本地启动前置条件（必读）

1. **本机必须有 MySQL 8.0（≥ 8.0.16）**：V1 基线使用了 `CHECK` 约束，MySQL 5.7 会**静默忽略** CHECK，
   枚举校验会全部失效且无任何报错，是最难排查的一类事故。
2. 建库：`CREATE DATABASE smart_park DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`
3. **表结构只由 Flyway 维护**（`src/main/resources/db/migration/V1__init.sql`）。
   旧的 `schema.sql` / `data.sql`（H2 方言、开头即 `DROP TABLE`）已删除——
   原「重启即清库」事故的根因已随文件一起移除，不再靠 `sql.init.mode` 兜底。
4. 默认激活 `dev` profile，可用 `SMARTPARK_PROFILE=prod` 切换。

```bash
mvn spring-boot:run
# 或指定环境变量
SMARTPARK_DB_HOST=127.0.0.1 SMARTPARK_DB_NAME=smart_park \
SPRING_DATASOURCE_USERNAME=smart_park SPRING_DATASOURCE_PASSWORD=xxx \
mvn spring-boot:run
```

- 接口前缀：`http://localhost:8080/api`
- 健康检查：`http://localhost:8080/api/actuator/health`（默认只暴露 health）
- H2 控制台：**已关闭**（生产强制关闭，dev 亦不再启用）

## 鉴权

- 登录：`POST /api/auth/login` → `{token, refreshToken, expiresIn, user, ...}`；`POST /api/auth/refresh` 免鉴权（否则双 token 链路自断）。
- 三角色：`ADMIN`（管理员）/ `GATE`（门岗）/ `STORE`（仓管），权限矩阵见 `SecurityConfig`。
- access token 2 小时、refresh token 7 天（可配，`SMARTPARK_ACCESS_TOKEN_MINUTES` / `SMARTPARK_REFRESH_TOKEN_DAYS`）。
- **账号禁用即时生效**：`JwtAuthFilter` 每次请求都查库且带 `status='启用'` 条件，
  禁用后下一个请求即返回 `40103`，无需黑名单表或 Redis。
- 默认管理员由 `AdminInitializer` 创建：用户名 `admin` / `gate` / `store`，
  初始口令取环境变量，未设置则随机生成并打印在启动日志里。

## 敏感数据

- 身份证 / 手机号落库为 **密文 + HMAC + 掩码** 三列（AES-256-GCM + HMAC-SHA256），
  读路径一律返回 `*_mask`，永不回传密文或明文。
- 密钥走环境变量 `SMARTPARK_CRYPTO_KEY` / `SMARTPARK_CRYPTO_HMAC_KEY`，缺失即 fail-fast。
- 存量明文回填：见 `PiiBackfillJob`（`SMARTPARK_PII_BACKFILL=true` 一次性启用，
  **执行前必须完成全量备份**）。

## 说明

- 统一返回体 `{code, message, data, traceId}`，`code=0` 表示成功；错误码见 `ErrorCodes`（已冻结）。
- 时间字段以字符串存储与返回，格式 `yyyy-MM-dd HH:mm:ss`（19 位定长）。
  JDBC URL 显式 `useServerPrepStmts=false` 保证走文本协议，**不要改成 true**（会带出 `.0` / `T`）。
- 定时任务（每 1 分钟）：权限时效扫描、访客滞留扫描、物资逾期扫描，逐条独立小事务 + 先告警后改状态。
- 关键操作写审计日志：`AUDIT | traceId= | operator= | action= | target= | result= | detail=`。
