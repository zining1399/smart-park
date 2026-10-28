# 智慧园区管理系统 · 交付改进方案（评审汇编终稿）

> 编制：全栈架构交付专家团 ｜ 评审范围：后端 9 模块全量源码、前端全部视图、8 张表结构、接口契约、工程化配置
> 结论有效前提：交付目标为**客户现场生产级部署上线**，鉴权补到"最小登录+三角色"，工程化补齐 Docker / 测试与 CI / 可观测性。
> 六份专项评审原文见 `docs/reviews/01~06`，本文件为裁决与汇编后的执行基准。

---

## 一、项目定义与成功标准

**一句话定义**：为园区提供门禁通行、访客接待、智慧仓管、运营看板、告警中心五模块一体化管理的内网 Web 系统。

**上线成功标准**（全部满足方可割接）：
1. 客户现场 Docker Compose 一键部署，三条命令内拉起全套服务；
2. 应用重启、升级、回滚均**不丢任何业务数据**；
3. 全部接口经 JWT 鉴权，三角色（ADMIN/GATE/STORE）越权访问返回 403；
4. 日均 1 万条通行流水量级下，看板 P95 < 1s、列表 P95 < 500ms【目标值，待客户确认】；
5. 每日自动备份，且完成过一次可验证的恢复演练；
6. 客户 UAT 签字确认，P0 用例 0 失败。

---

## 二、总体判定

**当前为"演示级"完成度，不具备客户现场生产上线条件。** 代码分层质量本身合格偏优（Controller 薄、Service 厚、契约与实现一致、前端列表页模式统一），但三大问题会在客户上线第一天暴露：

| # | 问题 | 客户现场后果 |
|---|------|------------|
| 1 | **数据不持久** | 任何一次重启/升级 = 全部业务数据清零，回到演示状态 |
| 2 | **接口不设防** | 内网任何一台电脑用 curl 即可删员工、批贵重物资出库、拉取明文身份证 |
| 3 | **无灾备** | 无备份、非 git 仓库、无任何部署资产，出问题无救济、无追溯、无回滚 |

两档交付节奏：

| 档位 | 内容 | 工作量 | 日历（假设 1 后端 + 1 前端 + 1 测试 + 0.5 运维） |
|------|------|--------|------|
| **试点上线** | Blocker 全部清零（第三节 B01-B10） | ≈ 18 人日 | ≈ 3 周 |
| **完整生产化** | + 治理改造 + 测试体系 + CI 门禁 + UAT | ≈ 75 人日总量（各线并行滚动） | ≈ 6-7 周 |

---

## 三、上线阻断项（Blocker，统一编号）

以下 10 项不做完禁止上线。每项给出位置、影响与修复方案（详细实施见对应附录）。

| ID | 问题 | 位置 | 客户现场影响 | 修复方案 | 附录 |
|----|------|------|------------|---------|------|
| **B01** | 重启清库：8 张表全部 `DROP TABLE IF EXISTS` + `spring.sql.init.mode: always` | `schema.sql` / `application.yml` | 任何重启=业务数据全丢 | 移除 DROP；生产 `mode: never`；Flyway 接管 DDL | 03 |
| **B02** | 零鉴权 + CORS 通配符带凭证（`*` + `allowCredentials(true)`）+ H2 Console 生产暴露（同机反代下任何人可开、空密码） | `pom.xml` / `CorsConfig.java` / `application.yml` | 接口裸奔，任何内网机器可增删改查全部数据，恶意网页可借用户浏览器调用 API | Spring Security + JWT 三角色；CORS 白名单化；生产关闭 H2 Console 并换 MySQL | 02 |
| **B03** | H2 文件库不可用于生产（单进程、无并发、损坏即全损）**且分页插件硬编码 `DbType.H2`**——切 MySQL 不改会持续用 H2 方言 | `application.yml` / `MybatisPlusConfig.java` | 数据层整体不可生产；分页 SQL 方言错 | MySQL 8 + Flyway + `DbType.MYSQL`（H2 降为 test scope，dev profile 保留） | 03 |
| **B04** | 出库/审批/归还均为 check-then-act 竞态，无锁 | `StockRecordServiceImpl`（stockOut/approve/returnBack） | 两个仓管并发申领同一件一物一码物资**双双成功**，台账与实物不符；双击审批双写出库时间 | 条件 UPDATE 原子状态迁移：`UPDATE material SET status='在借' WHERE id=? AND status='在库'`，影响行数≠1 抛业务异常；version 列随 MySQL 建表免费保留作兜底 | 02/03 |
| **B05** | 访客身份证脱敏值回写：编辑弹窗把脱敏星号串回填表单并原样回传，后端 `updateById` 直接覆盖 | `Visitor.vue`（openDialog）+ `VisitorServiceImpl.update` | 用户在编辑弹窗保存任意字段一次，库内身份证被星号串**永久覆盖**，无法找回 | 三层防护：前端编辑模式 idCard 禁用 + payload 剔除该字段；后端含 `*` 不更新 | 02/04 |
| **B06** | 时间字段 `VARCHAR(20)` 且入参无格式校验（仅 `@NotBlank`） | 全部 8 表 + 时间类 DTO | 传入 `2026/11/1 9:00` 等格式后字典序比较全错，逾期扫描漏判、权限误放行，**且不报错**，极难排查 | DTO 全部加 `@Pattern(yyyy-MM-dd HH:mm:ss)`；MySQL 迁移时改 DATETIME；固定 `TZ=Asia/Shanghai` | 02/03 |
| **B07** | 异常信息透传（`e.getMessage()` 拼进响应，泄露 SQL/表结构）+ 所有错误 HTTP 恒 200 | `GlobalExceptionHandler` | 帮攻击者做信息侦察；监控与拨测全部失明 | 对外统一中文文案 + 真实 HTTP 状态码 + 错误码分段（见第五节冻结表）+ 详情只进日志 | 02 |
| **B08** | 无部署资产：非 git 仓库、无 `.gitignore`、无 Dockerfile/compose/CI/nginx，`.idea/`、`data/*.mv.db`、`dist/` 混在交付目录 | 项目根 | 无法回滚、无法追溯、误提交 node_modules，现场部署无据可依 | git init + `.gitignore` + Docker Compose 三容器 + CI 流水线 + 备份脚本 | 05 |
| **B09** | 前端鉴权链路整体缺失：无登录页、无路由守卫、无 404 路由、无角色按钮权限、错误提示无去重、部署路径硬编码 | `request.js` / `router/index.js` / `MainLayout.vue` | 后端上 JWT 后首个请求 401 只弹错误 toast 不跳登录，**系统整体不可用** | 登录页 + auth store + 拦截器（40101/40102/40103/40301 分流）+ 路由守卫 + 角色菜单过滤 + v-permission | 04 |
| **B10** | 演示数据与孤儿引用：`data.sql` 含 2026 假数据、假身份证，alarm 引用不存在的物资 `M-SPARE-009`；无任何引用完整性 | `data.sql` | 演示数据污染生产主数据；未来日期导致看板上线即空白 | 生产不加载 `data.sql`（dev/staging 专用）；基础数据由客户 Excel 走 `LOAD DATA INFILE` 导入；上线前孤儿引用检查 | 03 |

---

## 四、强烈建议项（试点上线同班车或上线后一周内）

| ID | 问题 | 修复 | 归属附录 |
|----|------|------|---------|
| S01 | 五个 CRUD 模块直接绑定实体、无 @Valid、**实体状态字段可被外部任意设置**（PUT 传 `status:"在库"` 可绕过出入库状态机） | Create/Update DTO + @Valid；status/outTime 等状态字段一律不出现在写接口入参 | 02 |
| S02 | 唯一性 check-then-insert 有竞态，DB 兜底后报 500 | 补 `DuplicateKeyException` handler 返回 40901 | 02 |
| S03 | 错误码不成体系（业务错误恒为 1） | 八枚错误码冻结表（第五节） | 02 |
| S04 | 物理删除 + 业务唯一键可改（改 empNo/code 后流水悬挂） | 禁止修改业务唯一键（本期必做）；逻辑删除 `deleted` 列 + `@TableLogic`（随 MySQL 建表落地） | 02/03 |
| S05 | 无审计字段、无操作留痕、无 traceId | 全表补 `create_time/update_time`；关键操作 INFO 审计日志；MDC traceId | 02/03/05 |
| S06 | 定时任务无 `@Transactional`，先改状态再建告警，告警失败即**静默丢失**；单条异常中断本轮 | 加事务 + 先建告警再改状态 + 循环体逐条 try-catch | 02/03 |
| S07 | Dashboard 全表加载内存聚合（一年约 365 万行）；通行 LIKE 前置通配符不可走索引 | SQL 侧 GROUP BY 聚合；targetNo 改前缀匹配；列表默认限 30 天窗口 | 03 |
| S08 | pageSize 无上限（pageSize=100000 打爆内存） | 服务端 clamp ≤200 | 02 |
| S09 | 逾期检查 N+1、物资下拉 pageSize=500 hack | 批量 IN 查询；物资量级确认后加轻量 options 接口或远程搜索 | 02/03 |
| S10 | 身份证/手机号明文落库（接口已脱敏但存储未加密） | Phase 2 三列存储（密文+HMAC+掩码）；**必须在客户录入真实身份证前完成**，否则回填成本翻倍 | 03 |
| S11 | alarm 无去重，同一人反复刷卡失败持续灌告警 | `dedup_key` 唯一键 + `INSERT ... ON DUPLICATE KEY UPDATE` | 03 |
| S12 | H2/MySQL 大小写行为差异（`E1001`/`e1001` 唯一键语义变化） | 写入时 `emp_no/plate_no/code` 统一大写归一化 | 03 |
| S13 | 前端主包 1.21MB（Element Plus 全量）+ Dashboard chunk 1.04MB（ECharts 全量） | 按需引入 + manualChunks + gzip 预压缩，总传输预计降到 ~500KB 级 | 04 |
| S14 | 前端表单校验偏弱（手机号/身份证/车牌只校验必填） | 补格式正则校验（脏数据进 MySQL 后治理成本更高） | 04 |
| S15 | 无测试（后端无 src/test、pom 无 test 依赖、前端零测试） | 测试策略与 200+ 用例清单、CI 门禁（第六节） | 06 |
| S16 | 时区假设未固定（`DateTimes.now()` 读 JVM 默认时区） | 容器/JVM 固定 `TZ=Asia/Shanghai`，迁移后比对已知记录；割接时执行四值时区验收命令 | 02/03 |
| S17 | 枚举值域无校验；MySQL &lt;8.0.16 时 CHECK 约束**静默失效**（不报错也不生效，比报错更危险） | DTO 枚举 `@Pattern` 校验升级为**唯一防线必做项**，覆盖全部 10 个枚举字段（每表独立取值）；客户版本确认前按"CHECK 不可用"设计 | 02 |

可选优化项（O 档：看板 Caffeine 缓存、springdoc 活契约、告警通知渠道、simulate 幂等键、移动端适配、浏览器兼容摸底等）见各附录，不阻塞交付。

---

## 五、技术选型定稿（含被否决方案）

| 领域 | 定稿 | 被否决方案及理由 |
|------|------|----------------|
| 数据库 | **MySQL 8.0（≥8.0.16）** | H2 生产化（并发弱、无运维生态、损坏即全损）；PostgreSQL（技术上胜任，但园区/国企现场 MySQL 运维人才多）；达梦/openGauss（仅客户有国产化要求时启用【待确认】） |
| Schema 迁移 | **Flyway**（V1 基线 + undo/ 人工脚本 + `clean-disabled: true`） | 继续用 schema.sql（即 B01 数据丢失根因）；Liquibase（跨库抽象层本项目用不上，changelog 学习成本高、依赖运行时；其 rollback 优势已被"向前兼容 + 备份恢复"覆盖） |
| 鉴权 | **Spring Security + JWT（双 token：access 2h + refresh 7d 轮换）** | Session+Redis（单机不划算）；Keycloak（IDP 过重）；对接客户身份中心 OIDC/LDAP（若客户有则改走此路线【待确认】） |
| 连接池 | **HikariCP**（max 20，keepalive，max-lifetime < wait_timeout） | Druid（历史漏洞多、依赖重，监控面板本项目用不到） |
| 可观测性 | **Actuator(health/info) + Micrometer + logback JSON 滚动日志 + traceId + 轻量巡检脚本保底**，Prometheus/Grafana 作可选附加包 | ELK/Loki（单机规模过重）；SkyWalking（单体无分布式追踪需求） |
| 缓存 | **暂不引入**（看板先做 SQL 聚合下推；P95>500ms 或多实例时再引入 Caffeine） | Redis（当前规模过度设计） |
| CI/CD | **GitHub Actions**（Gitee/Gitea 同构可迁移 <0.5 人日） | GitLab CI（需自建 Runner）；Jenkins（插件维护成本高）；手工脚本（仅作离线降级路径保留） |
| E2E 测试 | **Playwright** | Cypress（多浏览器支持弱、并行收费）；组件测试 Vitest（Vite 原生零配置）优于 Jest（两套转译配置） |
| 部署形态 | **Docker Compose 单机栈**（nginx + backend + mysql + 备份 sidecar），K8s 不引入 | K8s（单站点规模纯增复杂度）；镜像与配置已按 12 要素外置，未来迁 K8s 无需改代码 |
| 集成测试库 | **Testcontainers(MySQL 8)** | H2 内存库（正在做 H2→MySQL 迁移且时间字段改型，H2 会掩盖 MySQL 特有行为，等于白测） |

---

## 六、前后端与鉴权方案要点（契约定稿）

### 6.1 鉴权定稿（前后端已双端对齐冻结）

- **角色编码**：`ADMIN`（管理员）/ `GATE`（门岗）/ `STORE`（仓管）。
- **登录/刷新出参**：`data: { token, refreshToken, expiresIn, user: { username, realName, role } }`；`POST /auth/refresh` 入参 `{ refreshToken }`，refresh 每次轮换。
- **登出**：无服务端端点（无状态 JWT），前端丢弃 token 即可。
- **token 方案（主理人已拍板）**：双 token——access 2 小时 + refresh 7 天轮换。已知取舍见第十一节"已知限制"。
- **错误码冻结（前端 hard-code 分支，联调期不得变更）**：

| code | HTTP | 语义 | 前端行为 |
|------|------|------|---------|
| 40101 | 401 | token 缺失/过期/无效 | 单飞 refresh 重试一次；失败清凭证跳 /login?redirect= |
| 40102 | 401 | 账号密码错误（仅 /auth/login） | 仅提示，不跳转 |
| 40103 | 401 | 账号禁用 | **登录页**：仅提示，不跳转（跳转会丢失 redirect 参数）；**业务请求**：踢回登录并说明"账号已被禁用"——禁用即时生效后每个请求都会返 40103，若按"仅提示"处理会让用户僵死在原页面、数据永远刷不出来 |
| 40301 | 403 | 无角色权限 | ElMessage 警告，不跳转 |
| 40001 | 400 | 参数校验失败 | 提示后端中文文案 |
| 40401 | 404 | 资源不存在 | 提示文案 |
| 40901 | 409 | 状态冲突/重复操作 | 提示文案 |
| 50001 | 500 | 系统异常（统一文案"系统繁忙，请稍后重试"） | 前端兜底文案特判 |

- **新增后端结构**：`SysUser` 实体 + `security/`（SecurityConfig / JwtService / JwtAuthFilter / 401/403 handler 绑定 Result JSON + 真实 HTTP 码）+ `AuthController`（login / me / refresh）+ `AdminInitializer`。密码 BCrypt（strength 12，自带盐）。JWT 密钥 HS256 ≥32 字节，`openssl rand -base64 48` 生成，`.env` 注入，绝不进 git。
- **权限矩阵**（URL 级，同路径不同方法必须带 HttpMethod 区分；具体规则原文见附录 02）：员工/车辆读写分角色；`stock-records/*/approve` 审批权建议收归 ADMIN（避免仓管自我审批，【待客户确认】）；告警处理三角色均可；看板全员。

### 6.2 并发修复（主理人裁决）

出库/审批/归还/告警处理统一改**条件 UPDATE + 影响行数校验**（无需乐观锁版本列，改动最小）；`version` 列随 MySQL 建表**免费保留**作兜底与后续扩展，本期不实现 `@Version` 拦截器。

### 6.3 B05 身份证三层防护

①前端编辑模式 idCard 输入框 disabled；②前端编辑提交 payload 剔除 idCard；③后端 update 时含 `*` 不更新该字段。新增模式保持可输入并校验 18 位格式。

---

## 七、数据层方案要点

- **MySQL DDL 已成稿**（附录 03 §4，可直接拷走执行）：时间字段全部 `DATETIME`；全表补 `create_time/update_time` 审计字段；主数据表补 `deleted`（逻辑删除）+ `version`；枚举字段加 `CHECK` 约束（需 MySQL ≥8.0.16）；`pass_time`（业务时间）与 `create_time`（入库时间）分离以支持终端补传对账；归档表提前建好。**归档表承重设计（不得删改）**：`CREATE TABLE ... LIKE` 在 MySQL 8.0.16+ **会复制主表的 CHECK 约束**，所以 V1 里 `LIKE` 之后必须紧邻执行 `DROP CHECK` 解耦——这两行**不是冗余**，删掉后归档表将带着约束，主表规则一收紧就会让归档作业被归档表自己的约束拒绝、静默失败数月无人知（历史留痕不应被主表未来的规则收紧追溯）。反证：若 `LIKE` 真不继承 CHECK，`DROP CHECK` 会报 ERROR 3911、V1 直接失败，故该行为可按此现场验证。
- **索引设计**（附录 03 §5，含每条索引服务的查询与依据）：门禁校验热路径由 `(target_type,target_no,area)` 复合索引直接命中【主理人裁决：**不建唯一键**——权限是版本化记录，续期即产生同人同区多条记录，唯一键会挡合法业务；普通复合索引查询性能与唯一键等价，"一人一区仅一条有效权限"若获客户确认则走应用层校验，不靠 DB 约束。附录 03 中 `uk_perm_target_area` 唯一键方案据此改为普通复合索引 `idx_perm_target_area`】；定时任务三条扫描各配 `(status, 时间列)` 复合索引；`idx_sr_overdue_scan` 列顺序把 `return_time IS NULL` 提到 IN 范围之前以多生效一个键部——**上线前必须 EXPLAIN 实测 key_len 验证**。
- **明确不建的索引**（附录 03 §5.3）：低区分度单列索引、小表（千级）索引——高增长表上乱建索引是隐蔽的写入杀手。
- **迁移路径**：路径 A 空库上线（推荐，客户无真实数据时）；路径 B H2 存量 CSV 中转导入（`STR_TO_DATE` 强制类型转换 + 五项校验含孤儿引用检查）。演示数据绝不进生产。
- **容量结论**：按员工 3000/车辆 500/日通行 1 万假设，3 年约 1095 万行 / 1.5GB——**无需分库分表、无需分区，只需归档**（24/36 个月，分批搬运避免长事务）。
- **MySQL 5.7 降级预案（DDL 附注，客户版本 <8.0.16 时执行）**：在 V1 DDL 上做 3 条机械变换——① `utf8mb4_0900_ai_ci` 全部替换为 `utf8mb4_general_ci`（库级+表级）；② 删除全部 20 条 CHECK 约束（5.7 静默忽略，保留会误导后来人以为有约束）；③ **必须同步删除归档表的 2 条 `DROP CHECK`**——5.7 无 CHECK 语法，`DROP CHECK` 不是"约束不存在所以跳过"而是**语法错误直接报错 → Flyway 迁移失败 → 应用起不来**（最易漏的陷阱：删了 CREATE 的 CHECK 却顺手留着 DROP 语句）。已核对不受影响项：全部索引最宽约 389 字节 < 767 前缀限制；DATETIME 双时间戳默认值不受 5.6.5 前 TIMESTAMP 单列限制。降级后 DB 防线整体消失，**防线位置明确转移到 API 层 7 个 P0 字段**（backend 任务 10 + QA P0 断言集），是防线转移而非安全性下降。客户确认为 8.0.16+ 则丢弃本附注用现版。
- **治理**：每日 mysqldump 全量 + binlog 增量（RPO/RTO 分层承诺：基础档 24h/4h，高保障档 15min/30min）；恢复演练每季度一次（未演练的备份等同于没有备份）；**身份证/手机号加密三列已按裁决 17 提前到 V1 基线**（`visitor.id_card/phone`、`employee.phone` 各用 cipher + HMAC hash + mask 三列；AES-256-GCM 密钥由环境变量注入；**禁用 MySQL AES_ENCRYPT**——密钥会进 binlog 和慢日志）。原 Phase 2 的 V1.1.0 加密迁移与 V1.1.1 回填两个脚本因此取消。

---

## 八、部署与可观测方案要点

- **交付形态**：`docker-compose.yml` 三容器（MySQL 8.0.39 / backend / frontend+Nginx），`.env.example` 为客户唯一填写入口（数据库密码、JWT 密钥、端口、`APP_VERSION`）；离线场景 `docker load` 导入镜像 tar 后同一条命令启动。
- **数据安全红线**：生产库结构唯一来源是 Flyway（V1 无 DROP）；`spring.sql.init.mode: never`；MySQL 命名卷持久化；每日 02:30 备份 + 保留 7 份 + 异机 1 份。
- **MySQL 三账号分权**（v1.4 交付冻结版，附录 05 含 compose/init 全文）：应用运行时账号仅 DML（不给 DDL/DROP，注入破坏半径限制在数据层）；迁移账号仅 Flyway 执行期使用（含 DDL）；备份账号只读+锁表走容器内 socket + `--defaults-extra-file` 密钥文件。`my.cnf` 全量参数（双一持久化、binlog ROW/MINIMAL、慢日志 2s、max_connections=200、`skip-name-resolve`）挂载进容器；账号授权 host 段由 `SP_NET_SUBNET` 推导且 mysql 容器 env **显式注入全部 init 变量 + 密码强断言**（漏注入=首次部署明确报错，绝不静默带错上线）；**`3306` 不对宿主机暴露，全栈对外只有 Nginx 一个入口**；JDBC URL 删除 `characterEncoding=utf8`（3 字节残缺映射，存 emoji 报错）改 `connectionCollation=utf8mb4_0900_ai_ci`；H2 存量迁移走 CSV→INSERT 管道导入（`LOAD DATA LOCAL INFILE` 被 MySQL 8 默认禁用，不常开 local_infile 开关）；**销毁 `mysql-data` 卷属危险操作**（init 脚本仅首次建卷执行）。运维与数据两组互相终审共排除 7 个"交付后才炸"级缺陷（备份 host 匹配、GRANT 1146、子网断链、env 漏注入、local_infile、utf8mb3、actuator 链路）。实操扩容上限 5 个后端实例。
- **备份强化**：备份失败必须告警（巡检脚本每日断言备份文件 mtime < 25h，超龄即 P1——很多事故是备份静默失败数月无人知）；发布窗口避开 02:00±1h（`--single-transaction` 与 Flyway DDL 并发有风险）。
- **Nginx 要点**：SPA history fallback（解决刷新 404）、`/api` 同域反代（前端零 CORS 依赖）、assets 长缓存 + index.html 禁缓存、gzip、`/api/actuator/` 除 health 外一律 403。
- **可观测**：logback JSON 滚动日志（异步 appender、错误单独落盘、保留 30 天 5GB 上限）；`TraceIdFilter`（MDC + 响应头回传，前端报错文案带追踪号，grep 一次定位全链路）；轻量巡检脚本（cron 每分钟：容器健康/磁盘/ERROR 增量）为保底，Prometheus+Grafana 作可选附加包。
- **发布与回滚**：单机简化蓝绿——切换 = `.env` 改 `APP_VERSION` 重建容器，窗口 <30 秒；**旧版本镜像永不主动删除**；Flyway 迁移脚本必须向后兼容一个版本（加列可空/带默认、跨版本删列）。
- **Actuator 定稿**：`include: ${ACTUATOR_EXPOSE:health}` 占位切换（默认仅暴露 health；客户选装监控时 env 一行切换 `health,metrics,prometheus` 免发版）；micrometer-registry-prometheus 依赖保留但端点未暴露 = 零攻击面；**SecurityConfig 恒只 permitAll `/actuator/health`**——环境变量切换暴露 ≠ 自动放行，prometheus 选装需另配内网抓取源授权。CI release job 加镜像自检（`docker run --rm <镜像> which curl`，缺失即 fail 构建）。
- **环境变量命名终版**（杜绝"compose 注入 A、代码读 B"的静默空值）：**Spring 标准变量管基础设施**（`SPRING_DATASOURCE_USERNAME/PASSWORD`，relaxed binding 零代码生效）、**`SMARTPARK_` 前缀管应用级**（`SMARTPARK_JWT_SECRET`、`SMARTPARK_CORS_ORIGINS`，同域部署 CORS 留空=不注册 CORS mapping）。

---

## 九、测试与验收门禁（Go/No-Go）

- **分层**：接口集成（Testcontainers + MySQL 8，P0 接口 100% 覆盖）> 核心状态机单测 > 定时任务幂等/边界 > 数据迁移专项 > E2E 冒烟 15 条 > 前端组件三点。明确**不追求**前端全量组件测试与 80% 覆盖率（内部系统性价比低）。
- **P0 用例重点**（v1.8 封版共 **107 条条目**，参数化展开后为 **271 个断言实例**，全表见附录 06；载体分布：后端自动化 69 / Playwright 15 / 人工割接 7 / 数据脚本 6 / 混合 10）。⚠️ 这是 v1.8 封版时的历史快照；**当前权威条目数以 `docs/qa/testcases.md` §0/§13 为准（119 条 / 对外 108 / 268 断言实例）**，下文各项重点不变）：**数据持久性冒烟第一关**（TC-SMOKE-01：录入→重启→数据仍在，不过此关其余测试无意义）**；鉴权矩阵（**42 接口** × 3 角色 + 未登录 = 168 项，越权全拒；接口数为逐个 Controller 实测值，非估算）；贵重物资审批全状态机；**并发出库/审批/归还专项 4 条**（TC-STK-11/22/23/24，须 10 并发×20 轮工具执行 + SQL 判定 `HAVING c>1`，预期修复前全挂）；时区环境矩阵（Asia/Shanghai 必过 / UTC 为已知缺陷须修复后双环境全过）；**TC-ENUM 封版**（**20 条** CHECK 约束断言矩阵——含新增的 `chk_perm_time_range`（`>=` 语义：只拦真倒挂，零时长放行）与 `sys_user` 的 2 条（`chk_sys_user_role` / `chk_sys_user_status`）；错误号 3819+约束名双条件；P0 断言集 7 字段=API 层唯一防线，MySQL 5.7 下裸奔，任何 FAIL 直接按清单定位 backend DTO 漏项；03a/03b 拆分以"DB 放行+API 拒绝"交叉验证防线分层；配套"枚举字段×防线归属"三层总表）；**TC-MIG 系列随 INSERT 管道方案局部解冻更新**（TC-MIG-02 断言换血：导入零错误日志断言 + 零值日期检出 + `@@sql_mode` 含 STRICT_TRANS_TABLES 模式锁定；**新增 TC-MIG-08 导入原子性**：含脏日期源数据导入后目标表行数必须=0，行数>0 即部分导入 FAIL——半成品数据库比完全失败更危险，依赖迁移脚本包事务）；异常信息泄露（TC-SEC-01）；身份证脱敏逐接口抓包；TC-FE-12（前端出入库筛选下拉清理 RETURN 选项）。
- **硬性 No-Go 条件（任一命中即停）**：① 存在未修复 Blocker；② 迁移对账不一致；③ 任何越权用例成功；④ 身份证在任何响应/日志明文出现；⑤ 备份恢复演练未做或失败；⑥ 客户未对 README 9 条假设书面确认（规则未确认 = 验收标准缺失）；**⑦ TC-SMOKE-01 重启丢数据未修复（init.mode/DROP TABLE 未整改）**。
- **测试工作量**：约 35 人日 / 6 周日历（1 名中级测试 + 前后端各 30% 支持）。

---

## 十、里程碑与排期

人力假设：**1 后端 + 1 前端 + 1 测试 + 0.5 运维**（单人执行则日历×2.5 以上）。★ = 关键路径。

| 里程碑 | 内容 | 工作量 | 依赖 |
|--------|------|--------|------|
| **M0** ★ | git init + `.gitignore` + package-lock.json + 清理 .idea/data/dist 出交付物 | 0.5 人日 | 无（一切的前提） |
| **M1** ★ | MySQL 迁移：Flyway V1（全量 DDL、无 DROP）+ 配置改造 + `DbType.MYSQL` + 时区固定 | 4.5 人日 | M0 |
| **M2** ★ | 后端：鉴权全套（Security/JWT/三角色/错误码）+ B04 并发条件 UPDATE + 贵重物资待审批单入口校验 + B05/B06/B07 修复 + ScheduledTasks 逐条小事务 + 唯一键大写归一化 + DTO 枚举校验全防线 + prod profile | 10 人日 | M1（契约部分可先行） |
| **M3** ★ | 前端：登录页 + 拦截器 + 路由守卫 + 角色权限 + B05 防护 + 环境变量 | 8 人日 | M2 契约（D5/D6 可并行提前） |
| **M4** | 容器化：Dockerfile×2 + compose（MySQL 三账号分权 + my.cnf 全量参数）+ nginx + `.env` 体系 + CI 骨架 | 7 人日 | M2（与 M3 并行） |
| **M5** | 测试体系：Testcontainers 骨架 → 集成+鉴权矩阵 → 状态机 → E2E（滚动进行） | 35 人日 | 与 M2-M4 并行启动 |
| **M6** ★ | 数据迁移执行（路径 A/B）+ 五项校验 + 备份脚本 + 首次恢复演练 | 2 人日 | M1 冻结、M4 |
| **M7** ★ | staging 全量冒烟 + 回滚演练 + 客户 .env 生成 + 割接 + 培训 | 2 人日 | M2-M6 全部 |

**试点上线（Blocker 清零）**：M0→M1→M2→M3→M4→M6→M7 的关键路径 ≈ 18 人日 ≈ 3 周日历。
**完整生产化**：+ M5 全量 + S01-S17 治理项 + UAT ≈ 75 人日总量，6-7 周日历。

**注意**：S10（身份证加密）必须在**客户录入真实身份证之前**完成，否则存量回填成本翻倍——若客户投用早于 Phase 2，需显式记录该风险。

---

## 十一、决策与权衡记录（主理人裁决）

| # | 分歧/议题 | 裁决 | 理由 |
|---|----------|------|------|
| 1 | 并发修复：后端主张条件 UPDATE（无版本列）vs 数据组 DDL 含 version + @Version | **条件 UPDATE 为实现方案，version 列免费保留作兜底** | 条件 UPDATE 改动最小、语义清晰；version 列建表零成本，本期不挂拦截器，留作扩展 |
| 2 | "VARCHAR 时间字段导致跨天/跨月排序错误"的说法 | **采纳数据组纠错，废弃该表述** | 定长零填充格式下字典序=时间序，该说法不成立（DBA 验证后反而削弱评审可信度）；真实问题是无类型约束、无日期函数、JVM 时区依赖、存储与比较效率 |
| 3 | token 方案：双 token vs 单 token 8h | **双 token**（access 2h + refresh 7d 轮换） | 单 token 强制重登打断现场操作；前端已按双 token 结构实现，成本增量小 |
| 4 | 鉴权粒度 | 最小登录+三角色，明确排除完整四维 RBAC（用户已拍板） | — |
| 5 | 审批权归属 | **暂定仅 ADMIN**（避免仓管自我审批），升为待客户确认项 | 业务规则，技术侧不应代拍 |
| 6 | 前后端成员直连协调契约 | 追认有效产出，但后续跨成员信息流仍经主理人中转 | 本次直连产出质量高（错误码细分、B05 双端闭环），程序瑕疵不影响结论采信 |
| 7 | H2 保留策略 | dev profile 保留 H2（开发零依赖），h2 依赖降为 test scope，生产彻底切换 MySQL | 兼顾开发体验与生产安全 |
| 8 | 权限唯一键：数据组主张 `uk_perm_target_area` 唯一键 vs 架构师反对（续期即多条，唯一键挡业务） | **建普通复合索引 `(target_type,target_no,area)`，不建唯一键** | 权限是版本化记录，续期/多时段是合法业务；普通索引查询性能与唯一键等价；"一人一区一条"若客户确认则走应用层校验 |
| 9 | RPO/RTO 对外承诺：架构师暂定 24h/4h vs 数据组 15min/30min，差一个数量级 | **分层承诺**：基础档（默认交付）RPO 24h / RTO 4h；高保障档（客户选购，含 binlog 近实时备份与恢复演练服务）RPO 15min / RTO 30min | 单机园区场景 binlog 近实时备份运维成本明显抬升，不应默认承担；数据组 binlog 方案作为高保障档实现基础 |
| 10 | B1 修复路径：后端原"独立 init.sql 手工执行" vs 数据组 Flyway | **Flyway 版本化迁移**（后端已自行更新采纳） | 客户现场后续升级必须有版本化机制；Entity 零改动设计抵消了引入成本 |
| 11 | 时间字段"排序错误"对外口径 | **采纳数据组纠错并全队统一**：不能说"VARCHAR 导致跨天/跨月排序出错"（定长零填充格式字典序=时间序）；正确表述为无类型约束、无日期函数、JVM 时区依赖、存储与比较效率低 | 客户 DBA 一验即破的错误结论会拖累整份评审可信度 |
| 12 | 贵重物资多张「待审批」单卡死（批准第一张后其余永久卡死，无作废接口） | **技术默认：`stockOut` 入口拒绝**——该物资已有未完成待审批单则拒绝提交并提示；业务口径（是否允许多张并存）待客户确认，若要求允许多张再加作废接口 | 业务规则应由客户拍板，但卡死路径必须先消灭 |
| 13 | 定时任务 `@Transactional` 的长事务风险（方法级事务会阻塞并发出库/审批） | **循环内逐条独立小事务**（每条记录先建告警再改状态，单条一个事务），不采用方法级整段事务 | 兼顾"告警不丢"与"不阻塞核心写操作" |
| 14 | 数据层 P2 排期 | **敏感字段加密（S10）提前到与客户数据导入同一批次**；其余 P2 治理项维持完整生产化档位 | 加密必须在客户录入真实身份证之前完成，否则存量回填成本翻倍 |
| 15 | `stock_record.type` 的 `RETURN` 为死枚举（归还只更新原 OUT 单 return_time，从不新增 RETURN 记录） | **采纳移除**：从 `chk_sr_type` CHECK、`api-contract.md` 与全部枚举定义中删除 RETURN；TC-STK-25 作回归防线。**DDL 侧为单点改动**（归档表 `CREATE TABLE ... LIKE` 自动继承主表 CHECK）；归档表紧邻执行 `DROP CHECK` 解耦（历史留痕不应被主表将来规则收紧追溯，防归档作业静默失败） | 死枚举误导后人写错代码；"更新原单"模型合理无数据损失；后端独立复核与数据组结论一致（交叉验证） |
| 16 | actuator 是否引入（devops 健康检查与 frontend 启动依赖 `/api/actuator/health`） | **必加**：backend 已确认零成本纳入任务清单（pom 依赖 + management 配置 + 路径）；联调期未就绪可临时 TCP 兜底 healthcheck，正式交付前必须切回（需探测 DB 连通性） | compose 的 `depends_on: service_healthy`、割接检查单、巡检脚本三处都依赖该端点，缺失 = frontend 永远起不来 |
| 17 | 身份证加密的档期：Phase 2 迁移改造 vs 随 V1 基线落地 | **随 V1 基线直接落地加密三列**（cipher + hash + mask），不保留明文阶段；后端加解密与掩码逻辑与 V1 同批次交付 | 客户走空库上线路径 A，V1 即真实数据第一行：此时写死加密三列零额外成本，却省掉 V1.1 加密迁移 + 存量回填两个脚本，且**从第一行数据起就是密文，无明文窗口期**（用户已明确加密必须早于真实数据录入） |

---

## 十二、已知限制（明示取舍，防上线后被当 bug 提报）

1. **登出/改密不即时吊销旧凭据**：最小方案下 refresh token 为无状态 JWT（服务端不存库），旧 refresh 在 7 天自然过期前仍然有效，多设备可各自刷新。这是为不上黑名单表做的取舍。若客户要求"旧凭据立即失效"：后端 +0.5~1 人日（服务端存储）+ 前端 +0.25 人日（多标签页 storage 事件协调），**不在当前 72 人日估算内**。
2. **单实例硬性约束**：定时任务为进程内调度，只允许一个后端实例运行（部署文档明示）；alarm `dedup_key` 幂等键作兜底，双实例需加 ShedLock。
3. **模拟通行接口**：`/access-records/simulate` 是终端上报的等价模拟，真实门禁终端接入前无 deviceId+timestamp 幂等（网络重试会产生重复流水）。
4. **Phase 1 身份证明文落库**：接口脱敏已生效，但存储加密在 Phase 2（须在客户录入真实身份证前完成）。
5. **同工号软删除仅一次（Phase 1）**：唯一键 `(emp_no, deleted)` 的已知代价；Phase 2 改用 `deleted_at DATETIME` 时间戳标记（二次删除时间戳不同永不撞键，且自带删除时间审计，一列两用）；删除实现用 LambdaUpdateWrapper 手写条件更新、**不依赖 @TableLogic**（MP delval 引用本行 id 的能力版本间行为不一致，实测前不赌），Phase 1 维持物理删除与现状行为一致、零回归。
6. **门岗四门：DB 层无约束、API 层兜底**：`chk_ar_gate` 已删除（`GATE_AREA` 在 Java 层拦截未知门岗，DB 再拦收益极低且让新增门岗要改两处——新增门岗仅改代码）；`chk_perm_area` 保留（`AccessPermission.validate()` 只查非空不查取值，DB 是唯一防线）——两者取舍依据不同。客户如需动态门岗管理属范围变更。
7. **高保障档（RPO 15min）若客户选购**：恢复演练需纳入交付 SOW 并明确首年演练次数，否则承诺无法验证。

---

## 十三、待确认清单（阻塞排期的客户侧问题）

| # | 事项 | 影响 |
|---|------|------|
| 1 | ~~客户 MySQL 版本~~ | ✅ **已确认：MySQL 8.0**（满足 8.0.16+，CHECK 约束与 utf8mb4_0900_ai_ci 均可用）。5.7 降级预案（§七附注 3 条变换）**弃用**，执行现版 DDL |
| 2 | ~~服务器时区~~ | ✅ **已确认：统一北京时间（东八区）**。三层固定：容器 `TZ=Asia/Shanghai` + JVM `-Duser.timezone=Asia/Shanghai` + JDBC `serverTimezone=Asia/Shanghai` + my.cnf `+08:00`；割接执行四值时区验收命令 |
| 3 | **数据量级**（员工/车辆/日均通行/物资） | 索引取舍、容量估算、物资下拉方案（S09） |
| 4 | 是否已有**统一身份中心**（OIDC/LDAP） | 自研 JWT（3-5 人日）vs 对接（5-8 人日） |
| 5 | 贵重物资**审批人归属**（仅 ADMIN？） | 权限矩阵定稿 |
| 6 | 门岗对 `/permissions` **是否开放写权限**（现场授权 vs 集中管理） | 权限矩阵定稿 |
| 7 | **部署形态**：根路径/子路径、是否 HTTPS、是否允许 Docker、浏览器基线（含国产浏览器？） | Nginx/vite base/CORS/兼容性方案 |
| 8 | ~~等保/个保法合规要求~~ | ✅ **用户已明确：身份证加密必须在客户录入真实数据之前完成** → 裁决 17：加密三列直接进 V1 基线，零明文窗口期 |
| 9 | **SLA**（RPO/RTO 选基础档还是高保障档）与**留存合规期**（通行/访客记录） | 备份频率、归档周期定稿 |
| 13 | 业务上"同一人对同一区域"是否允许多条有效期记录（**问客户业务方而非 IT 方**） | 决定权限表走复合索引（裁决 8 默认）还是应用层强校验 |
| 14 | 贵重物资是否允许多张「待审批」单并存（裁决 12 默认入口拒绝） | 决定是否需要加作废接口 |
| 15 | 客户现场 H2 是否已有真实存量数据（默认按空库上线处理，TC-MIG-01~04 条件执行） | 决定走迁移路径 A 还是 B、存量对账用例是否执行 |
| 16 | 客户内网网段清单（与容器 `SP_NET_SUBNET` 默认 172.28.x 是否冲突） | 冲突则改子网；应用挪出 compose 或改连已有 MySQL 时需同步追加账号授权 |
| 17 | 客户现场是否有 Prometheus/Grafana 监控接入需求 | 决定 actuator 默认仅暴露 health 单端点（裁决口径），还是追加 metrics/prometheus 端点（需 micrometer-registry-prometheus + 内网抓取源限定 + 单独授权规则） |
| 10 | README **9 条假设逐条书面确认**（尤其审批规则与逾期限制规则） | 验收标准缺失 = 硬性 No-Go |
| 11 | 代码托管平台（GitHub/Gitee/Gitea） | CI 语法微调 |
| 12 | 通知通道（内网 SMTP / 企业微信 webhook） | 告警通知实现 |

---

## 附录（六份专项评审全文，实施时直接取用）

| 编号 | 文件 | 内容要点 |
|------|------|---------|
| 01 | `docs/reviews/01-architecture-review.md` | 架构评审 12 项问题（B1-B12）、选型表、非功能目标、目标部署架构图 |
| 02 | `docs/reviews/02-backend-review.md` | 后端评审 20 项问题、**最小鉴权完整落地方案**（依赖/类清单/SecurityConfig 骨架/权限矩阵/密钥管理）、14 步实施顺序 |
| 03 | `docs/reviews/03-data-review.md` | 数据层评审 + **完整 MySQL DDL** + 索引设计依据 + H2→MySQL 迁移双路径 + Flyway 规范 + 备份/加密/归档方案 |
| 04 | `docs/reviews/04-frontend-review.md` | 前端评审（B1-B7/S1-S10/O1-O6）+ 鉴权对接方案定稿 + 角色矩阵 + Nginx/环境变量对接清单 + D1-D7 排期 |
| 05 | `docs/reviews/05-devops-plan.md` | **可直接拷贝的 Dockerfile×2 / compose / nginx.conf / .env.example / .gitignore / CI workflow / logback** + 发布回滚预案 + 运维手册 |
| 06 | `docs/reviews/06-qa-plan.md` | 测试策略、**104 条用例条目（v1.6 封版，参数化展开 262 断言实例）**、TC-ENUM 封版（17 约束矩阵 + 防线归属三层总表）、TC-MIG 管道原子性专项（TC-MIG-02/08/12）、非功能测试、缺陷分级、Go/No-Go 检查表（7 条硬性 No-Go）、UAT 方案。**落地物**：`docs/qa/testcases.md`（可执行规格）、`docs/qa/gates.md`（门禁与割接检查单）、`docs/qa/fixtures/*.csv`（鉴权 42×4 与枚举参数化数据）、`smart-park-frontend/e2e/`（Playwright 15 条冒烟） |

> 本方案为评审汇编基准。用户确认后按 M0→M7 顺序下发实施任务；六份附录中的 DDL、Dockerfile、nginx.conf、CI workflow 均为可直接执行的成品内容。
