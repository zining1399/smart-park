# 附录 02 · 后端评审与最小鉴权方案（程不宕 · 后端服务工程师）

> 归档说明：本文件为成员产出原文归档（主报告 + 三次契约/裁决补强）。**汇编裁决修正**：①主清单编号为本项目统一编号基准（身份证回写缺陷 = B6，前端评审中的 B7 即此问题）；②B1 修复路径最终定为 Flyway（数据组方案，后端已自行采纳）；③`@Transactional` 落法按裁决 13 改为**循环内逐条独立小事务**（后端已确认：扫描逻辑抽独立 Bean、方法级 REQUIRES_NEW、先建告警后改状态）；④`uk_perm_target_area` 按裁决 8 改普通复合索引；⑤`RETURN` 死枚举按裁决 15 移除（残留仅 StockRecord 实体 type 注释一处）。以 `docs/delivery-improvement-plan.md` 为执行基准。

---

## 主报告

评审范围：9 Controller / 9 ServiceImpl / 9 Mapper / 8 Entity / 4 DTO / common / config / task / application.yml / schema.sql / data.sql / api-contract.md，并抽查了前端 request.js 与 Visitor.vue（用于核实一个数据损坏链路）。**未修改任何代码。**

### 〇、契约核对结论

api-contract.md 与实现逐条比对**一致**：路径、分页结构、脱敏规则、访客/物资状态流转、simulate 放行逻辑、dashboard 字段全部吻合，未发现实现偏离契约。前端 request.js 已预留 token 注入 TODO，鉴权对接成本低。

### 一、问题清单

#### 🔴 Blocker（不改不能上线）

**B1. 重启即清库，数据全丢**
- 位置：`application.yml`（`spring.sql.init.mode: always`）+ `src/main/resources/schema.sql`（开头 8 条 `DROP TABLE IF EXISTS`）+ `data.sql`（演示数据）
- 现象：每次应用启动都执行 DROP 全部 8 张表并重灌 2026 年演示数据
- 影响：生产现场**任何一次重启/升级 = 全部业务数据清零**，且出入库、告警、访客登记全部回到演示状态。这是全项目最高危问题。
- 建议：`mode: never`；初始化脚本与日常运行彻底分离（最终定案：Flyway 版本化迁移）；data.sql 仅保留在 dev profile。

**B2. 全部接口零鉴权裸奔**
- 位置：`pom.xml` 无 security 依赖；所有 Controller 无任何认证
- 影响：部署即暴露——任何人可 DELETE 员工/物资、可 approve 出库、可调 `/access-records/simulate` 凭空造通行记录和告警。
- 方案见第二部分。

**B3. CORS 通配符 + 允许凭证**
- 位置：`config/CorsConfig.java` L16：`allowedOriginPatterns("http://localhost:5173", "http://127.0.0.1:5173", "*")` 且 `allowCredentials(true)`
- 影响：等于向任意来源发放可携带凭证的跨域许可，任何恶意网页都能借用户浏览器调用这套 API
- 建议：来源白名单配置化（`smartpark.cors.allowed-origins`），删掉 `*`；生产填客户现场实际访问域名。

**B4. H2 Console 生产暴露 + 空密码**
- 位置：`application.yml`：`h2.console.enabled: true`（路径 `/api/h2-console`），数据源 `sa` 用户空密码
- 影响：部署后任何人可打开数据库控制台直接改表
- 建议：prod profile 关闭 console、设置数据源密码。生产换 MySQL（最终定案）。

**B5. 出库/审批/归还并发缺陷（check-then-act 无锁）**
- 位置：`service/impl/StockRecordServiceImpl.java`
  - `stockOut` L64-95：先查物资 `在库` 再落单。两个并发申领同时通过检查 → **同一物资双出库**
  - `approve` L99-120：先查 `待审批` 再更新。双击/并发 → **双审批、双写出库时间**
  - `returnBack` L124-143：先查 `return_time is null` 再更新 → **重复归还，物资状态反复覆盖**
- `AlarmServiceImpl.handle` 同模式（影响小）
- 建议：改成**条件 UPDATE 原子状态迁移**：`UPDATE material SET status='在借' WHERE code=? AND status='在库'`，影响行数=0 即抛业务异常（其余两个同理）。`@Transactional` 已有，缺的是把检查与写入合并成一条原子语句。此方案无需引入乐观锁版本列，改动最小。

**B6. 访客身份证脱敏值回写，一次编辑即永久损坏数据（前后端已双端核实）**
- 位置：后端 `VisitorServiceImpl.update()` 直接 `updateById(入参 visitor)`；前端 `Visitor.vue openDialog(row)` 把列表返回的**脱敏后** idCard（`110101********7733`）填入编辑表单并原样回传
- 现象/影响：用户在编辑弹窗里保存任意字段一次，库里该访客身份证就被星号串**永久覆盖**，无法找回
- 建议（三层防护）：①前端编辑模式 idCard 输入框 disabled；②前端编辑提交 payload 剔除 idCard 字段；③后端 update 时若入参 idCard 含 `*` 则不更新该字段。

**B7. 系统异常信息直接透传给客户端**
- 位置：`common/GlobalExceptionHandler.java` L38 `"系统异常：" + e.getMessage()`、L32 `"请求参数不合法：" + e.getMessage()`
- 影响：DB 报错（表名、字段、SQL 片段）、反序列化细节直接暴露给外网，帮攻击者做信息侦察
- 建议：对外统一通用文案（"系统繁忙，请稍后重试" / "请求参数不合法"），详情只进服务端日志（已有 `log.error`）。

**B8. 字符串时间比较依赖客户端格式，未做任何校验**
- 位置：全库时间 `VARCHAR(20)` 按字典序比较（`DateTimes` 注释明确了这一假设）；但 `StockOutRequest.expectReturnTime`、`AccessPermission.startTime/endTime`、`Visitor.appointTime/expireTime` 均为客户端传入且**无格式校验**
- 影响：一旦传入 `2026-11-1 9:00`、ISO `2026-11-01T09:00` 或空格差异，字典序比较全错——逾期扫描漏判、通行权限误放行/误拦截、访客滞漏预警，**且不报任何错**，现场极难排查
- 建议：所有时间入参加 `@Pattern("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")`；`expectReturnTime` 加"不早于当前时间"校验。（长期正解是 DATETIME 列，与 MySQL 迁移同批落地。）

#### 🟠 强烈建议（同班车带上，一周内完成）

**S9. 五个 CRUD 模块直接绑定实体、无 @Valid、内部字段可被外部指定**
- 位置：Employee/Material/Vehicle/Permission/Visitor 五个 Controller 的 create/update
- 现象与影响：①必填字段可空入库；②超长字符串直击 DB 报 500；③**实体状态字段可被外部任意设置**——例如 `PUT /materials/{id}` 传 `status:"在库"` 可把「在借」物资直接改回「在库」，完全绕过出入库状态机
- 建议：新建 Create/Update DTO + `@Valid` + `@NotBlank/@Size`；status、checkInTime、checkOutTime、outTime、returnTime 等状态字段一律不出现在写接口入参中，由服务端维护。

**S10. 唯一性检查 check-then-insert 有竞态，兜底后报 500**
- 位置：`EmployeeServiceImpl.create` L34、`MaterialServiceImpl` L38、`VehicleServiceImpl` L34
- 建议：保留唯一键兜底（正确），补 `@ExceptionHandler(DuplicateKeyException.class)` 返回 40901 友好提示。

**S11. 错误码不成体系、HTTP 恒 200**
- 建议（最小错误码表，配合鉴权一并落）：40101 未登录(token 无效/过期) HTTP 401；40301 无权限 HTTP 403；40001 参数校验失败 HTTP 400；40401 资源不存在 HTTP 404；40901 状态冲突/重复 HTTP 409；50001 系统异常 HTTP 500。BizException 逐步带上下列 code，`Result` 结构不变，前端零破坏。（→ 见下方契约定稿，401 已细分为 40101/40102/40103。）

**S12. 未处理 MethodArgumentTypeMismatchException / 404**
- 建议：补两个 handler（400 / 404）。

**S13. 物理删除 + 业务唯一键可改 → 引用悬挂**
- 位置：五个模块 delete 均直接 `removeById`；`EmployeeServiceImpl.update` 允许改 empNo、`MaterialServiceImpl.update` 允许改 code、`VehicleServiceImpl` 允许改 plateNo
- 影响：员工删除/改工号后，通行权限、流水按 empNo 悬挂（simulate 报"未登记"但权限记录还在）；物资改 code 后 stock_record.materialCode 悬挂，逾期扫描直接失联
- 建议：①禁止修改业务唯一键（empNo/plateNo/code 建后不可改，DTO 中不收）；②删除前做引用校验，或加 deleted 标记走逻辑删除（本次交付至少做①）。

**S14. 无审计字段、无操作留痕、无 traceId**
- 建议：鉴权上线后补：①登录人身份从 SecurityContext 取出，出入库/审批/归还/告警处理/删除写一行 INFO 审计日志（operator、action、target、result）；②加 MDC filter 生成 traceId 并入日志 pattern。

**S15. 定时任务健壮性**
- 位置：`task/ScheduledTasks.java`
- 现象：①`scanVisitors` L80 循环中单条失败（告警写入异常）会中断本轮剩余访客；②`scanMaterials` L102 每条记录单查 material，N+1；③三任务共享单线程调度器，一个慢任务阻塞其余；④多实例部署会重复告警（单实例暂无实际风险）
- 好的一面：三个任务都有状态守卫，**天然幂等**，fixedDelay 下单轮异常不会终止调度
- 建议：循环体逐条 try-catch 隔离；material 按 code 批量 IN 查询；换 MySQL 多实例再加 ShedLock。（→ 裁决 13 定案：**逐条独立小事务**，见文末补强。）

**S16. Dashboard 内存聚合 + 全表扫描**
- 建议：GROUP BY 下推 SQL；物资分组 `GROUP BY status`。数据量增长前先修，改动小。

**S17. pageSize 无上限**
- 影响：`pageSize=100000` 直接打爆内存
- 建议：统一 max 200。

**S18. 逾期检查 N+1**
- 位置：`StockRecordServiceImpl.checkOverdueSameCategory` L169-182，逐条单查 material
- 建议：批量 IN 查询后内存比对 category。

**S19. stockIn 不校验物资当前状态**
- 影响：物资「在借」时误操作入库 → 物资直接置「在库」，但对应 OUT 单未闭环、账实不符
- 建议：校验状态 ∈ {在库, 维修中}（具体允许集合【待确认】）。

**S20. 时区假设未固定**
- 位置：`DateTimes.now()` 用 JVM 默认时区；Jackson 硬编码 GMT+8
- 建议：启动脚本固定 `-Duser.timezone=Asia/Shanghai`（写进部署文档），或 DateTimes 显式 `ZoneId.of("Asia/Shanghai")`。

#### 🟡 可选
- C21. 零测试：至少补登录链路、角色矩阵抽查、出库并发冒烟三条主干。
- C22. 无 actuator：暴露 /health 供探活（permitAll）。
- C23. 状态值域无约束（DTO 校验落地后可顺带解决）。
- C24. 访客身份证明文落库：若客户有等保/个保法合规要求需加密存储【待确认】。

### 二、最小登录 + 角色鉴权落地方案（Spring Security + JWT）

范围界定：**只做登录 + 三角色 + 接口级权限**，不做四维 RBAC、不做数据范围、不做用户管理界面。

#### 2.1 新增依赖（Maven 坐标）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```
security/actuator 由 Boot 3.2.12 BOM 管版本，不写版本号。

#### 2.2 新增类/表清单

```
com.smartpark
├─ entity/SysUser.java        // id, username(唯一), passwordHash, realName, role(ADMIN/GATE/STORE), status, createTime
├─ mapper/SysUserMapper.java
├─ dto/LoginRequest.java      // @NotBlank username / password
├─ dto/LoginResponse.java     // token, tokenType="Bearer", expiresIn, user:{username, realName, role}
├─ controller/AuthController.java
│     POST /auth/login   匿名放行
│     GET  /auth/me      登录即可（前端刷新后恢复会话）
│     POST /auth/refresh 刷新 access token（双 token 方案）
├─ security/
│   ├─ SecurityConfig.java         // SecurityFilterChain（无状态、CSRF 关闭、角色规则、异常绑定）
│   ├─ JwtService.java             // 签发/解析校验
│   ├─ JwtAuthFilter.java          // OncePerRequestFilter
│   ├─ SecurityErrorHandlers.java  // AuthenticationEntryPoint(401→40101) + AccessDeniedHandler(403→40301)
│   └─ JwtProperties.java          // @ConfigurationProperties(prefix="smartpark.security")
└─ config/AdminInitializer.java    // CommandLineRunner：sys_user 空时初始化三角色账号
```

**sys_user 表**（随 Flyway V1 建）：
```sql
CREATE TABLE sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,   -- BCrypt
    real_name     VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL,   -- ADMIN / GATE / STORE
    status        VARCHAR(16)  NOT NULL DEFAULT '启用',
    create_time   VARCHAR(20)  NOT NULL,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);
```

**关键实现要点**：
- `SecurityFilterChain`：`SessionCreationPolicy.STATELESS`；csrf disable；`permitAll`：`POST /auth/login`、全部 `OPTIONS`（CORS 预检）、`/actuator/health`；其余 `authenticated()`；`exceptionHandling()` 绑定自定义 handler —— **必须输出 `Result` JSON 且带正确 HTTP 401/403 状态码**，前端拦截器才能分流。
- `JwtAuthFilter`：取 `Authorization: Bearer xxx` → 验签+验期 → 按 username 查 sys_user（status=启用）→ 构造 `UsernamePasswordAuthenticationToken`（authorities=`ROLE_+角色`）放 SecurityContext。任何失败**不抛异常**，走匿名，由 entry point 统一回 401。
- JWT：HS256，claims：`sub=username`、`role`、`uid`、`iat`、`exp`。
- 密码：`BCryptPasswordEncoder`（**自带随机盐，strength 12**），不引入任何自定义加盐逻辑。
- 账号初始化：init.sql 预置 admin/gate/store 三个账号（BCrypt hash 事先生成），部署文档注明首登必须改密。

#### 2.3 角色划分与权限矩阵

| 接口 | ADMIN 管理员 | GATE 门岗 | STORE 仓管 | 说明 |
|---|---|---|---|---|
| POST /auth/login | 匿名 | 匿名 | 匿名 | |
| GET /auth/me | ✓ | ✓ | ✓ | |
| GET /employees | ✓ | 读（现场核验身份） | ✗ | |
| POST/PUT/DELETE /employees(**) | ✓ | ✗ | ✗ | |
| GET /vehicles | ✓ | 读 | ✗ | |
| POST/PUT/DELETE /vehicles(**) | ✓ | ✗ | ✗ | |
| GET /permissions | ✓ | 读 | ✗ | |
| POST/PUT /permissions | ✓ | 写（门岗现场授权）【待确认】 | ✗ | |
| DELETE /permissions/{id} | ✓ | ✗ | ✗ | |
| GET /access-records | ✓ | 读 | ✗ | |
| POST /access-records/simulate | ✓ | ✓ | ✗ | 模拟终端属门岗操作 |
| GET /visitors | ✓ | 读 | ✗ | |
| POST/PUT/DELETE /visitors(**) | ✓ | 写 | ✗ | 门岗日常登记 |
| POST /visitors/{id}/check-in、/check-out | ✓ | ✓ | ✗ | 门岗核心操作 |
| GET /materials | ✓ | ✗ | 读 | |
| POST/PUT/DELETE /materials(**) | ✓ | ✗ | 写 | |
| GET /stock-records | ✓ | ✗ | 读 | |
| POST /stock-records/in、/out | ✓ | ✗ | 写 | |
| POST /stock-records/{id}/return | ✓ | ✗ | 写 | |
| POST /stock-records/{id}/approve | ✓ | ✗ | ✗ | 审批权收归管理员，避免仓管自我审批【待确认】 |
| GET /alarms | ✓ | 读 | 读 | |
| POST /alarms/{id}/handle | ✓ | 写 | 写 | 告警处置人人有责 |
| GET /dashboard/** | ✓ | ✓ | ✓ | |
| GET /actuator/health | 匿名放行 | | | 探活 |

SecurityConfig 骨架（**同路径不同方法必须带 `HttpMethod` 区分**，具体规则在前、宽泛在后、`anyRequest().authenticated()` 兜底）：
```java
http.authorizeHttpRequests(reg -> reg
    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
    .requestMatchers("/auth/login").permitAll()
    .requestMatchers("/actuator/health").permitAll()
    .requestMatchers(HttpMethod.GET, "/employees").hasAnyRole("ADMIN","GATE")
    .requestMatchers("/employees/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.GET, "/vehicles").hasAnyRole("ADMIN","GATE")
    .requestMatchers("/vehicles/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.GET, "/permissions").hasAnyRole("ADMIN","GATE")
    .requestMatchers(HttpMethod.POST, "/permissions").hasAnyRole("ADMIN","GATE")
    .requestMatchers(HttpMethod.PUT, "/permissions/**").hasAnyRole("ADMIN","GATE")
    .requestMatchers("/permissions/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.GET, "/access-records").hasAnyRole("ADMIN","GATE")
    .requestMatchers("/access-records/simulate").hasAnyRole("ADMIN","GATE")
    .requestMatchers(HttpMethod.GET, "/visitors").hasAnyRole("ADMIN","GATE")
    .requestMatchers("/visitors/*/check-in", "/visitors/*/check-out").hasAnyRole("ADMIN","GATE")
    .requestMatchers("/visitors/**").hasAnyRole("ADMIN","GATE")
    .requestMatchers(HttpMethod.GET, "/materials").hasAnyRole("ADMIN","STORE")
    .requestMatchers("/materials/**").hasAnyRole("ADMIN","STORE")
    .requestMatchers(HttpMethod.GET, "/stock-records").hasAnyRole("ADMIN","STORE")
    .requestMatchers("/stock-records/*/approve").hasRole("ADMIN")
    .requestMatchers("/stock-records/**").hasAnyRole("ADMIN","STORE")
    .requestMatchers("/alarms/**").authenticated()
    .requestMatchers("/dashboard/**").authenticated()
    .requestMatchers("/auth/**").authenticated()
    .anyRequest().authenticated())
 .exceptionHandling(e -> e.authenticationEntryPoint(...).accessDeniedHandler(...))
 .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
 .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
 .csrf(AbstractHttpConfigurer::disable);
```

#### 2.4 前端对接要求
1. `request.js` 请求拦截器注入 `Authorization: Bearer <token>`（现有 TODO 位置）。
2. 响应拦截器 error 分支：40101 → 清 token 跳 /login；40102/40103 → 仅提示不跳转；40301 → 提示无权限；其余沿用现有逻辑。
3. 新增 `/login` 路由 + 登录页；成功后存 `{ token, role, realName }` 到 localStorage。
4. router 全局守卫：无 token 重定向 /login；按 role 过滤侧边菜单与路由。前端隐藏只是体验层，**真正的权限控制在服务端**。

#### 2.5 敏感配置与密钥管理
```yaml
smartpark:
  security:
    jwt-secret: ${SMARTPARK_JWT_SECRET}      # openssl rand -base64 48，≥32 字节
    access-token-minutes: 120
    refresh-token-days: 7
    cors:
      allowed-origins: ${SMARTPARK_CORS_ORIGINS:http://localhost:5173}
spring:
  datasource:
    username: ${SMARTPARK_DB_USER:sa}
    password: ${SMARTPARK_DB_PASSWORD:}
```
- JWT 密钥：HS256 ≥32 字节随机串，环境变量注入，**绝不写进 Git、绝不进交付配置模板**。
- 密码：BCrypt 自带盐，无需单独盐配置。
- **Token 策略（已定案）**：双 token——access 2 小时 + refresh 7 天，`/auth/refresh` 换新并轮换 refresh。已知取舍（登出不即时吊销）见终稿"已知限制"。
- 配置剥离：`application-prod.yml` 只含非敏感项，全部敏感项走环境变量；prod profile 同时覆盖：`h2.console.enabled: false`、`sql.init.mode: never`、CORS 白名单。

### 三、工作量估算与实施顺序（最终版，裁决并入后）

| # | 任务 | 人日 | 依赖 |
| 1 | B1 修复：Flyway 接管 + sys_user 建表 + dev/prod profile 拆分 | 0.5 | — |
| 2 | 引入 security+jjwt，搭 SecurityConfig / JwtAuthFilter / 401/403 handler 骨架 | 1 | — |
| 3 | SysUser 实体+Mapper+BCrypt+账号初始化 | 0.5 | 2 |
| 4 | AuthController：login / me / refresh | 0.5 | 3 |
| 5 | 权限矩阵落入 SecurityConfig + 全接口回归验证 | 0.5 | 4 |
| 6 | GlobalExceptionHandler 改造（404/类型不匹配/唯一键冲突 + 错误码分段） | 0.5 | 2 |
| 7 | B5 并发修复：出库/审批/归还/告警处理改条件 UPDATE + 贵重物资多待审批单入口校验 | 1.5 | — |
| 8 | B6 修复：idCard 脱敏值回写防护 | 0.25 | — |
| 9 | B8+S17：时间格式 @Pattern + expectReturnTime 校验 + pageSize 上限 | 0.5 | — |
| 10 | S9：五模块 Create/Update DTO + @Valid + 状态字段服务端化（含 S13 禁改唯一键）+ **枚举 @Pattern 全覆盖** | 1.5 | — |
| 11 | B3+B4+S20：CORS 白名单化 + h2-console 关闭 + prod profile 拆分 + 时区固定 | 0.5 | — |
| 12 | S14：审计日志（operator/action/target）+ traceId MDC | 1 | 5 |
| 13 | 前端：登录页 + axios 拦截器 + 路由守卫 + 菜单按角色过滤 | 1 | 4（联调在 5 后） |
| 14 | 回归测试：登录链路、角色矩阵抽查、并发出库冒烟 | 1 | 5、7 |

- 后端合计 ≈ **9.75 ≈ 10 人日**（原 8.25，+0.25 待审批入口校验 +0.25 枚举校验 +0.75 Flyway 路径升级对冲），前端 1 人日。
- 依赖链：`1` 独立先做（保数据）→ `2→3→4→5→12` 串行；`6/7/8/9/10/11` 并行插入；`13` 依赖 4；`14` 收尾。

### 四、风险与待确认
1. 【已定案】生产数据库：MySQL 8 + Flyway。
2. 【待确认】门岗对 `/permissions` 是否开放写权限。
3. 【待确认】贵重物资审批人：仅 ADMIN，还是 ADMIN+STORE。
4. 【已定案】双 token。
5. 【待确认】`/access-records/simulate` 生产是否保留（保留则限 ADMIN+GATE）。
6. 【待确认】现场部署形态（CORS 白名单取值与密钥注入方式）。
7. 【待确认】客户等保/个保法合规要求（身份证加密档期）。

---

## 补强一 · 契约增量（与前端定稿）

1. **登录/刷新接口出参结构**：`POST /auth/login` 成功 → `data: { token, refreshToken, expiresIn(秒), user: { username, realName, role } }`；`POST /auth/refresh` 入参 `{ refreshToken }`，返回结构同登录，refresh 每次**轮换**。
2. **401 语义细分**（前端按 code 分流）：40101 = token 缺失/过期/无效 → 踢登录（HTTP 401）；40102 = 账号密码错误（仅 /auth/login）→ 仅提示（HTTP 401）；40103 = 账号被禁用 → 仅提示（HTTP 401）；40301 = 已登录但无角色权限（HTTP 403）。角色编码 ADMIN / GATE / STORE；登出不做服务端端点（无状态 JWT），前端丢弃 token 即可。

## 补强二 · 错误码冻结（最终值）

40101（401，token 缺失/过期/无效）、40102（401，账号密码错误，仅 /auth/login）、40103（401，账号禁用，仅 /auth/login）、40301（403，无角色权限）、40001（400，参数校验失败）、40401（404，资源不存在）、40901（409，状态冲突/重复操作）、50001（500，系统异常，文案"系统繁忙，请稍后重试"）。后端定义 `ErrorCodes` 常量类，前端 hard-code 分支，**联调期间不得变更**。4xx/409 的 message 保证面向用户中文文案。

## 补强三 · 裁决确认与实现要点（最终轮）

1. **并发修复**：MySQL 版条件 UPDATE 与 H2 兼容写法（MP `LambdaUpdateWrapper.eq(Material::getStatus, "在库").set(...)`，`update() != 1` 抛 BizException）。逻辑删除用 LambdaUpdateWrapper 手写、**不依赖 @TableLogic**（MP delval 引用本行 id 的行为版本间不一致，实测前不赌）；Phase 1 物理删除维持现状零回归。
2. **贵重物资多待审批单**：数据组方案成立——approve 只改 stock_record 的 approve_status，不触碰 material 状态，首张单走条件 UPDATE 必然失败第二张；实现 stockOut 时入口校验"该物资已存在未完结待审批单则拒绝"（+0.25 并入任务 7），业务口径待客户确认。
3. **枚举防线**：DTO 枚举 @Pattern 全覆盖升级为唯一防线（MySQL <8.0.16 CHECK 静默失效，确认前按不可用设计），任务 10 按 1.5 人日执行。
4. **时区**：三层一致（宿主机/JVM/jackson）为 Blocker 级；割接时执行四值时区验收命令：`date`、`timedatectl`、`docker exec smart-park-backend date`、`SELECT NOW()`。
5. **定时任务事务（裁决 13）**：`@Transactional` 不加方法级——事务边界内含远超单条记录的循环，跨条目持锁会阻塞并发出库/审批（终端上报 `simulate` 也是 DB 写）。拆独立 Bean + 方法级 `REQUIRES_NEW`，**循环内逐条**：先插告警、后改状态（单条一个事务）——任一失败抛异常即整条回滚且下轮自然重试，**无需人工修复已翻状态但无告警的记录**。
6. **权限唯一键**：等价结论已终审（普通复合索引，裁决 8）。Phase 1 去掉唯一键、防重留应用层。
7. **RETURN 死枚举**：`chk_sr_type` CHECK 与契约取值改为 `IN / OUT`；全库检索确认 RETURN 无任何赋值写入路径，残留仅 `StockRecord.type` 实体注释中一句，随 DTO 层清理。QA 已加 TC-STK-25 回归防线。
8. **v1.2 增补的 QA 并发用例（5 条）采纳**：TC-STK-22/23/24 + TC-STK-11 压测化（10 并发×20 轮）+ TC-AUTH-12；预期修复前全部失败，修复后必须 100% 通过。
