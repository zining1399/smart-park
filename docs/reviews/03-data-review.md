# 附录 03 · 数据层评审与 H2→MySQL 迁移方案（储海量 · 数据建模工程师）

> 归档说明：本文件为成员产出原文归档。**落地时以"原文 + 以下 4 处变更清单"为准**（数据组与后端交叉确认定稿，除此之外全部不变）：
> 1. `uk_perm_target_area` 唯一键 → **普通复合索引 `idx_perm_target_area (target_type, target_no, area)`**（3 列，不带 deleted）——裁决 8；
> 2. **删除 `chk_ar_gate`**（应用层 GATE_AREA 已拦；`chk_perm_area` 保留）——后端提出、数据组采纳；
> 3. `chk_sr_type` 移除 `RETURN`（改 `IN ('IN','OUT')`）——裁决 15；**单点改动**：归档表 `CREATE TABLE ... LIKE` 自动继承主表 CHECK，无需二次修改；
> 4. **归档表 DROP 继承的 CHECK**（解耦，防主表规则将来收紧导致归档作业被自身约束拒绝、静默归档失败）——数据组建议、后端采纳。
> 另两项口径修正：①时间字段问题的正确表述见 §0 修正表（不得沿用"排序出错"说法）；②对外量级统一按员工 3000 / 车辆 500 / 日通行 1 万（保守口径）；③Phase 2 逻辑删除改用 `deleted_at DATETIME`（时间戳永不撞键 + 自带删除审计）；④MySQL 5.7 降级预案见终稿 §七附注（3 条变换 + `DROP CHECK` 语法错误陷阱）。以 `docs/delivery-improvement-plan.md` 为执行基准。

---

## 0. 先修正 5 处对任务书的事实性偏差

| # | 任务书的说法 | 复核结论 |
|---|------|---------|
| 1 | 时间字段 VARCHAR 导致「跨天/跨月会出错」 | **不成立**。`DateTimes.FMT` 固定为 `yyyy-MM-dd HH:mm:ss`，定长零填充，字典序**恰好等于**时间序，跨天/跨月/跨年都不会错。真正的问题在别处（见 B2/B3），是**无校验、无函数、时区、效率**，不是排序错 |
| 2 | 要求给 `ddl-auto` 策略 | 本项目是 **MyBatis-Plus，没有 JPA/Hibernate**，`spring.jpa.hibernate.ddl-auto` 不存在。等价开关是 `spring.sql.init.mode` |
| 3 | Mapper 9 个 | 实际 **8 个**（AccessRecord / AccessPermission / Alarm / Employee / Material / StockRecord / Vehicle / Visitor），且**全部是空接口**，无任何自定义 SQL |
| 4 | 未提 MyBatis-Plus 方言 | `MybatisPlusConfig` 里写死 `new PaginationInnerInterceptor(DbType.H2)`，换 MySQL 不改会持续用 H2 方言 → **这是任务书漏掉的 Blocker** |
| 5 | 隐含要先改字段长度 | 实测 `alarm.content` 实际最长约 50 字符（VARCHAR 按字符计），`reason(255)` 同理 → **够用，不需要改**，改了才是过度设计 |

## 1. 问题清单（三档分组）

### 1.1 Blocker（不解决禁止上线）

| ID | 对象 | 现象 | 影响 | 修改建议 |
|----|------|------|------|---------|
| **B1** | `schema.sql` + `application.yml` | 8 张表全部 `DROP TABLE IF EXISTS` 开头；`spring.sql.init.mode: always` | 每次应用重启**全量删表重建**，业务数据全部丢失。上线阻断 | 移除 `DROP`；引入 Flyway；`mode: never` |
| **B2** | 全部 8 张表 | 时间字段 `VARCHAR(20)` | ①**无类型约束**：`StockOutRequest.expectReturnTime` 只有 `@NotBlank`，可写入脏格式，一旦混入就永久破坏比较 ②**无日期函数** ③**时间与 JVM 时区绑定**：客户服务器若是 UTC，全库时间比北京时间早 8 小时 ④存储 19 字节 vs DATETIME 5 字节，索引膨胀、逐行字符串比较慢 | 改 `DATETIME`（推荐，2038 无问题、无隐式 UTC 转换）或 `TIMESTAMP`；应用层改 `LocalDateTime`；固定 `TZ=Asia/Shanghai` |
| **B3** | `MybatisPlusConfig` | `new PaginationInnerInterceptor(DbType.H2)` 硬编码 | 切 MySQL 后分页插件仍按 H2 方言生成 SQL | 改 `DbType.MYSQL` |
| **B4** | `data.sql` | ①演示数据（假身份证、2026 未来日期）②alarm 引用不存在的物资 `M-SPARE-009` ③`visitor` 明文身份证 | 演示数据进生产 = 污染主数据；未来日期导致看板上线即空白；孤儿引用证明无引用完整性 | 生产环境**不加载 `data.sql`**；基础数据由客户提供 Excel 走导入；上线前孤儿引用检查 |
| **B5** | `application.yml` | H2 文件库 + 空密码 + H2 Console 开启 | 文件库不支持并发/网络访问；`/h2-console` 在生产暴露一个可直接执行任意 SQL 的入口 | 换 MySQL；密码走 `${DB_PASSWORD}`；删除 h2 console 配置；h2 依赖降为 test scope |
| **B6** | `StockRecordServiceImpl.approve()/stockOut()/returnBack()` | 典型 **read-check-write** 竞态 | 同一物资两张「已批准」出库单；双归还 | 条件更新 + 影响行数校验（终稿裁决：条件 UPDATE 为主，version 列免费保留兜底） |

### 1.2 强烈建议（上线前应解决）

| ID | 对象 | 现象 | 影响 | 修改建议 |
|----|------|------|------|---------|
| **S1** | `access_permission` | 无唯一约束（终稿裁决：不建唯一键，理由见归档说明 1） | — | 复合索引 `(target_type, target_no, area)`；「一人一区仅一条」若客户确认走应用层校验 |
| **S2** | `visitor.id_card` / `visitor.phone` / `employee.phone` | 身份证、手机号**明文入库**；仅接口返回时脱敏 | 违反《个人信息保护法》对敏感个人信息的存储要求；DBA/备份文件/binlog/慢日志均能读到全量 | Phase 2 改三列存储：密文（AES-256-GCM）+ HMAC-SHA256（供精确查询）+ 掩码（供展示）；密钥由环境变量注入。**不要用 MySQL 的 `AES_ENCRYPT()`**——密钥会出现在 SQL 语句里，被 binlog 和慢日志记录 |
| **S3** | 缺失审计字段 | 6 表无 `create_time`/`update_time` | 无法追溯；无法做增量同步与对账 | 全表补 `create_time DATETIME DEFAULT CURRENT_TIMESTAMP` / `update_time DATETIME ... ON UPDATE CURRENT_TIMESTAMP` |
| **S4** | 缺失逻辑删除 | 全部删除为物理 `DELETE` | 门禁/访客是强审计场景，被抹掉无法追责；物理删除造成主外键孤儿 | 加逻辑删除（Phase 2 改 `deleted_at DATETIME`，LambdaUpdateWrapper 手写条件更新，不依赖 @TableLogic）；流水表不做删除只归档 |
| **S5** | `alarm` 无去重 | `deny()` 每次拦截都无条件 `createAlarm` | 告警表被噪声灌满 | 加 `dedup_key VARCHAR(64)` + 唯一键 + `INSERT ... ON DUPLICATE KEY UPDATE` |
| **S6** | `checkOverdueSameCategory()` | 对每条逾期记录循环 `materialMapper.selectOne` → **N+1** | 一次出库可能触发几十次单表查询 | 改批量 `IN` 查询 |
| **S7** | `DashboardServiceImpl.accessTrend()` | 全部记录拉进 JVM 内存再分组 | 数据量上来后看板必然超时 | SQL 侧聚合；**注意 `DATE(pass_time)` 对列做函数不可走索引**，正确写法是 `pass_time >= ? AND pass_time < ?` 区间 + 外层 GROUP BY |
| **S8** | `ScheduledTasks` 三个方法 | **无事务**。`scanMaterials` 先改物资「逾期」再建告警，告警失败则状态已变、下轮守卫不再命中 → **告警永久丢失** | 静默丢告警 | 逐条独立小事务 + 先建告警再改状态（终稿裁决 13） |
| **S9** | `access_record` 分页 | `LIKE '%xxx%'` 前置通配符 + 深翻页 | 不可走索引；`LIMIT 100000,10` 扫描 10 万行 | `targetNo` 改前缀匹配；列表默认限定 30 天；超 1 万条改游标分页 |
| **S10** | 枚举用 VARCHAR 存储 | 10+ 列存中文/英文字符串 | 写错值不被拦截；改文案要改数据；受 collation 影响 | Phase 1 加 `CHECK`（需 MySQL 8.0.16+）；API 层 @Pattern 为唯一防线（终稿 S17） |
| **S11** | Collation 行为变化 | H2 大小写敏感；MySQL `utf8mb4_0900_ai_ci` 大小写不敏感 | `E1001`/`e1001` 行为变化 | 写入时 `emp_no`/`plate_no`/`code` 统一 `toUpperCase()` 归一化 |

### 1.3 可选

| ID | 对象 | 现象 | 建议 |
|----|------|------|------|
| **O1** | `alarm.content VARCHAR(512)` / `reason VARCHAR(255)` | 实测最长约 50 字符 | **够用，不改** |
| **O2** | 库存无数量字段 | `material` 只有 `status` 无 `quantity` | 若是「一物一码」串行管理则设计合理。【待确认】 |
| **O3** | `visitor.plate_no` 与 `vehicle.plate_no` | 应用层无联动 | 应用层录入时校验，不建 FK |
| **O4** | `GlobalExceptionHandler` | `e.getMessage()` 直接返回前端 | 归 backend-engineer |

## 2. 实体与关系（概念模型）

8 实体：Employee / Vehicle / AccessPermission / AccessRecord / Visitor / Material / StockRecord / Alarm。

**结论：本项目 8 张表之间无一条物理外键**（多态引用 `target_no` 既指工号又指车牌，本就无法用外键表达；外键会阻碍归档）。**推荐用软删除 + 应用层校验 + 定时孤儿巡检替代物理外键。**

ER 图（略，原文含 mermaid erDiagram，关系：Employee/Vehicle 1—N AccessPermission/AccessRecord（target_no 多态）、Employee 1—N Visitor（host_name 字符串弱引用）、Material 1—N StockRecord（material_code）、三源 1—N Alarm）。

## 3. 逻辑模型（字段级修正要点）

- 全部 8 表：补 `create_time` / `update_time`（DATETIME，自动维护）；
- 主数据表 6 表：补 `deleted`（Phase 2 改 `deleted_at DATETIME`）+ `version`；
- 时间字段全部 VARCHAR(20) → DATETIME；`access_record` 的 `pass_time`（业务时间）与 `create_time`（入库时间）分离（终端离线补传对账）；
- `valuable` → TINYINT(1)；主键 → BIGINT UNSIGNED；
- `alarm` 补 `dedup_key`；`visitor.id_card` 扩到 VARCHAR(64) 为 Phase 2 密文预留（**不加身份证唯一键**——同一访客多次来访是合法业务）。

## 4. 物理模型：MySQL DDL

> 归档说明：以下 DDL 为原文；落地时按归档说明 1-4 处变更执行（§4.3 的 `uk_perm_target_area` 改为 `idx_perm_target_area (target_type, target_no, area)` 普通 KEY；§4.4 的 `chk_ar_gate` 删除；§4.7 的 `chk_sr_type` 改 `IN ('IN','OUT')`；§4.9 归档表追加 DROP CHECK）。

### 4.0 建库

```sql
CREATE DATABASE IF NOT EXISTS `smart_park`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE `smart_park`;
```

### 4.1 员工

```sql
DROP TABLE IF EXISTS `employee`;
CREATE TABLE `employee` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `emp_no`      VARCHAR(32)  NOT NULL                COMMENT '工号，业务唯一键（写入时统一大写）',
  `name`        VARCHAR(64)  NOT NULL                COMMENT '姓名',
  `dept`        VARCHAR(64)  DEFAULT NULL            COMMENT '所属部门',
  `phone`       VARCHAR(32)  DEFAULT NULL            COMMENT '手机号（Phase2 改密文）',
  `status`      VARCHAR(16)  NOT NULL DEFAULT '在职'  COMMENT '在职/离职',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
  `version`     INT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_employee_emp_no` (`emp_no`, `deleted`),
  KEY `idx_employee_status` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工';

ALTER TABLE `employee`
  ADD CONSTRAINT `chk_employee_status` CHECK (`status` IN ('在职','离职'));
```

> 唯一键带 `deleted` 的理由：软删除后同工号可复用。代价是同工号最多软删一次（Phase 2 `deleted_at` 时间戳方案解决）。

### 4.2 车辆

```sql
DROP TABLE IF EXISTS `vehicle`;
CREATE TABLE `vehicle` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `plate_no`    VARCHAR(32)  NOT NULL                COMMENT '车牌号，业务唯一键（写入时统一大写）',
  `owner_type`  VARCHAR(16)  DEFAULT NULL            COMMENT '员工/访客/仓储',
  `owner_name`  VARCHAR(64)  DEFAULT NULL,
  `dept`        VARCHAR(64)  DEFAULT NULL,
  `status`      VARCHAR(16)  NOT NULL DEFAULT '正常'  COMMENT '正常/黑名单',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
  `version`     INT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vehicle_plate_no` (`plate_no`, `deleted`),
  KEY `idx_vehicle_status` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车辆';

ALTER TABLE `vehicle`
  ADD CONSTRAINT `chk_vehicle_status`     CHECK (`status` IN ('正常','黑名单')),
  ADD CONSTRAINT `chk_vehicle_owner_type` CHECK (`owner_type` IS NULL OR `owner_type` IN ('员工','访客','仓储'));
```

### 4.3 通行权限

```sql
DROP TABLE IF EXISTS `access_permission`;
CREATE TABLE `access_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `target_type` VARCHAR(16)  NOT NULL                COMMENT 'PERSON/VEHICLE',
  `target_no`   VARCHAR(32)  NOT NULL                COMMENT '工号或车牌号',
  `target_name` VARCHAR(64)  DEFAULT NULL,
  `area`        VARCHAR(32)  NOT NULL                COMMENT '办公区/生产区/仓储区/生活区',
  `start_time`  DATETIME     NOT NULL,
  `end_time`    DATETIME     NOT NULL,
  `status`      VARCHAR(16)  NOT NULL DEFAULT '生效'  COMMENT '生效/临期/失效',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
  `version`     INT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  -- 【落地变更 1】原文 UNIQUE KEY `uk_perm_target_area` (target_type, target_no, area, deleted)
  -- 改为普通复合索引：
  KEY `idx_perm_target_area` (`target_type`, `target_no`, `area`),
  KEY `idx_perm_status_endtime` (`status`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通行权限';

ALTER TABLE `access_permission`
  ADD CONSTRAINT `chk_perm_target_type` CHECK (`target_type` IN ('PERSON','VEHICLE')),
  ADD CONSTRAINT `chk_perm_status`      CHECK (`status` IN ('生效','临期','失效')),
  ADD CONSTRAINT `chk_perm_area`        CHECK (`area` IN ('办公区','生产区','仓储区','生活区')),
  ADD CONSTRAINT `chk_perm_time_range`  CHECK (`end_time` >= `start_time`);
```

> 设计要点：门禁校验查询 `WHERE target_type=? AND target_no=? AND area=? AND 时间范围` 恰好命中 `idx_perm_target_area` 最左前缀，**无需为门禁校验另建含时间列的索引**——那是纯冗余。

### 4.4 通行流水（高增长表）

```sql
DROP TABLE IF EXISTS `access_record`;
CREATE TABLE `access_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pass_type`   VARCHAR(16)  NOT NULL,
  `target_no`   VARCHAR(32)  NOT NULL,
  `target_name` VARCHAR(64)  DEFAULT NULL            COMMENT '通行时快照名',
  `gate`        VARCHAR(32)  DEFAULT NULL            COMMENT '东门/西门/南门/货运门',
  `direction`   VARCHAR(8)   DEFAULT NULL            COMMENT 'IN/OUT',
  `pass_time`   DATETIME     NOT NULL                COMMENT '通行事件时间（业务时间）',
  `result`      VARCHAR(16)  NOT NULL                COMMENT '放行/拦截',
  `reason`      VARCHAR(255) DEFAULT NULL,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间（系统时间）',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ar_pass_time`        (`pass_time`),
  KEY `idx_ar_target_pass_time` (`target_no`, `pass_time`),
  KEY `idx_ar_result_pass_time` (`result`, `pass_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通行流水';

ALTER TABLE `access_record`
  ADD CONSTRAINT `chk_ar_pass_type` CHECK (`pass_type` IN ('PERSON','VEHICLE')),
  ADD CONSTRAINT `chk_ar_result`    CHECK (`result` IN ('放行','拦截')),
  ADD CONSTRAINT `chk_ar_direction` CHECK (`direction` IS NULL OR `direction` IN ('IN','OUT'));
  -- 【落地变更 2】chk_ar_gate 已删除（应用层 GATE_AREA 拦截，DB 再拦收益低且新增门岗要改两处）
```

> 流水表**不加 deleted**——只归档不删除。`pass_time`（业务时间）与 `create_time`（入库时间）分离：终端离线补传时两者不同。

### 4.5 访客

```sql
DROP TABLE IF EXISTS `visitor`;
CREATE TABLE `visitor` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `visitor_name`    VARCHAR(64)  NOT NULL,
  `id_card`         VARCHAR(64)  DEFAULT NULL          COMMENT 'Phase1 明文，Phase2 改密文三列',
  `phone`           VARCHAR(64)  DEFAULT NULL,
  `plate_no`        VARCHAR(32)  DEFAULT NULL,
  `host_name`       VARCHAR(64)  DEFAULT NULL,
  `host_dept`       VARCHAR(64)  DEFAULT NULL,
  `reason`          VARCHAR(255) DEFAULT NULL,
  `area`            VARCHAR(32)  DEFAULT NULL,
  `appoint_time`    DATETIME     DEFAULT NULL,
  `expire_time`     DATETIME     DEFAULT NULL,
  `status`          VARCHAR(16)  NOT NULL DEFAULT '已预约' COMMENT '已预约/在场/已签销/滞留预警',
  `check_in_time`   DATETIME     DEFAULT NULL,
  `check_out_time`  DATETIME     DEFAULT NULL,
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  `version`         INT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_visitor_status_expire` (`status`, `expire_time`),
  KEY `idx_visitor_appoint_time`  (`appoint_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='访客';

ALTER TABLE `visitor`
  ADD CONSTRAINT `chk_visitor_status` CHECK (`status` IN ('已预约','在场','已签销','滞留预警'));
```

> `id_card` 扩到 VARCHAR(64) 为 Phase 2 密文预留，避免二次改列。**不加身份证唯一键**（多次来访合法）。

### 4.6 物资

```sql
DROP TABLE IF EXISTS `material`;
CREATE TABLE `material` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(64)  NOT NULL                COMMENT '一物一码，业务唯一键',
  `name`        VARCHAR(64)  NOT NULL,
  `category`    VARCHAR(16)  DEFAULT NULL            COMMENT '工具/备件/车辆',
  `spec`        VARCHAR(128) DEFAULT NULL,
  `dept`        VARCHAR(64)  DEFAULT NULL,
  `location`    VARCHAR(64)  DEFAULT NULL,
  `valuable`    TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否贵重（出库需审批）',
  `status`      VARCHAR(16)  NOT NULL DEFAULT '在库'  COMMENT '在库/在借/逾期/维修中',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
  `version`     INT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_code` (`code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='物资';

ALTER TABLE `material`
  ADD CONSTRAINT `chk_material_status`   CHECK (`status` IN ('在库','在借','逾期','维修中')),
  ADD CONSTRAINT `chk_material_category` CHECK (`category` IS NULL OR `category` IN ('工具','备件','车辆'));
```

### 4.7 出入库流水

```sql
DROP TABLE IF EXISTS `stock_record`;
CREATE TABLE `stock_record` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `material_code`      VARCHAR(64)  NOT NULL          COMMENT '物资编码（冗余，归档后仍可查）',
  `material_name`      VARCHAR(64)  DEFAULT NULL      COMMENT '物资名称快照',
  `type`               VARCHAR(16)  NOT NULL          COMMENT 'IN/OUT',
  `borrower`           VARCHAR(64)  DEFAULT NULL,
  `dept`               VARCHAR(64)  DEFAULT NULL,
  `approve_status`     VARCHAR(16)  NOT NULL          COMMENT '免审/待审批/已批准/已驳回',
  `expect_return_time` DATETIME     DEFAULT NULL,
  `out_time`           DATETIME     DEFAULT NULL,
  `return_time`        DATETIME     DEFAULT NULL,
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `remark`             VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sr_material_code`  (`material_code`, `id`),
  KEY `idx_sr_overdue_scan`   (`type`, `return_time`, `approve_status`, `expect_return_time`),
  KEY `idx_sr_create_time`    (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出入库流水';

ALTER TABLE `stock_record`
  -- 【落地变更 3】按裁决 15：chk_sr_type 改为 IN ('IN','OUT')（RETURN 为死枚举已移除）
  ADD CONSTRAINT `chk_sr_type`           CHECK (`type` IN ('IN','OUT')),
  ADD CONSTRAINT `chk_sr_approve_status` CHECK (`approve_status` IN ('免审','待审批','已批准','已驳回'));
```

> **`idx_sr_overdue_scan` 的列顺序是索引设计里最讲究的一个**：待服务查询 `type='OUT' AND approve_status IN (...) AND return_time IS NULL AND expect_return_time < NOW()`。B+ 树约束：遇第一个范围条件后其后列只能 ICP。`return_time IS NULL` 被 MySQL 按**等值**处理可作键部，提前到 `approve_status`（IN 视为范围）之前 → 生效键部 3 列。**上线前必须 EXPLAIN 实测 key_len**，未达预期则回退顺序 `(type, approve_status, return_time, expect_return_time)` 二者取优。

### 4.8 告警

```sql
DROP TABLE IF EXISTS `alarm`;
CREATE TABLE `alarm` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type`        VARCHAR(32)  NOT NULL                COMMENT '门禁异常/黑名单/访客滞留/物资逾期',
  `title`       VARCHAR(128) NOT NULL,
  `content`     VARCHAR(512) DEFAULT NULL,
  `level`       VARCHAR(16)  NOT NULL DEFAULT '警告'  COMMENT '提示/警告/严重',
  `status`      VARCHAR(16)  NOT NULL DEFAULT '未处理' COMMENT '未处理/已处理',
  `dedup_key`   VARCHAR(64)  DEFAULT NULL            COMMENT '去重键：type+业务对象+日期哈希',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `handle_time` DATETIME     DEFAULT NULL,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alarm_dedup`   (`dedup_key`),
  KEY `idx_alarm_status_id`     (`status`, `id`),
  KEY `idx_alarm_create_time`   (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警';

ALTER TABLE `alarm`
  ADD CONSTRAINT `chk_alarm_level`  CHECK (`level` IN ('提示','警告','严重')),
  ADD CONSTRAINT `chk_alarm_status` CHECK (`status` IN ('未处理','已处理'));
```

### 4.9 归档表（Phase 3 启用）

```sql
CREATE TABLE IF NOT EXISTS `access_record_archive` LIKE `access_record`;
ALTER TABLE `access_record_archive`
  DROP INDEX `idx_ar_pass_time`, DROP INDEX `idx_ar_target_pass_time`, DROP INDEX `idx_ar_result_pass_time`,
  ADD INDEX `idx_ara_target_pass_time` (`target_no`, `pass_time`),
  ROW_FORMAT = COMPRESSED, COMMENT = '通行流水归档表（>24个月）';

CREATE TABLE IF NOT EXISTS `stock_record_archive` LIKE `stock_record`;
ALTER TABLE `stock_record_archive`
  DROP INDEX `idx_sr_material_code`, DROP INDEX `idx_sr_overdue_scan`, DROP INDEX `idx_sr_create_time`,
  ADD INDEX `idx_sra_material_code` (`material_code`, `id`),
  ROW_FORMAT = COMPRESSED, COMMENT = '出入库流水归档表（>36个月）';
-- 【落地变更 4】归档表需紧邻执行 DROP CHECK 解耦（LIKE 会连 CHECK 一起复制；历史留痕不应被主表将来规则绑定）
```

## 5. 索引设计清单与依据

### 5.1 建立的索引

| 表 | 索引 | 字段与顺序 | 服务的查询 | 依据 |
|---|---|---|---|---|
| employee | `uk_employee_emp_no` | (emp_no, deleted) | 门禁校验 + 工号唯一 | 唯一键兼作查询索引 |
| employee | `idx_employee_status` | (status, id) | 列表 eq + ORDER BY id DESC | 过滤+消除 filesort+分页截断 |
| vehicle | `uk_vehicle_plate_no` / `idx_vehicle_status` | 同上结构 | 同上 | 同上 |
| access_permission | `idx_perm_target_area` | (target_type, target_no, area) | **门禁校验（最高频）** | 最左前缀直接命中 |
| access_permission | `idx_perm_status_endtime` | (status, end_time) | 定时任务三条 UPDATE | 等值+范围全命中，60s 一轮无索引则全表扫 |
| access_record | `idx_ar_pass_time` | (pass_time) | 看板/趋势/列表/排序 | 必需 |
| access_record | `idx_ar_target_pass_time` | (target_no, pass_time) | 通行轨迹查询 | 等值+范围+排序全命中 |
| access_record | `idx_ar_result_pass_time` | (result, pass_time) | 只看拦截记录 | 低选择率值（拦截占比个位数百分比）+时间倒序，命中率极高 |
| visitor | `idx_visitor_status_expire` | (status, expire_time) | 滞留扫描 | 等值+范围 |
| visitor | `idx_visitor_appoint_time` | (appoint_time) | 预约检索 | 高频预留 |
| material | `uk_material_code` | (code, deleted) | 出库/归还/审批 selectOne | 唯一键兼查询 |
| stock_record | `idx_sr_material_code` | (material_code, id) | 按物资查历史 | 等值+PK 序免排序 |
| stock_record | `idx_sr_overdue_scan` | (type, return_time, approve_status, expect_return_time) | 逾期扫描 + 出库前校验 | 见 §4.7 专项说明 |
| stock_record | `idx_sr_create_time` | (create_time) | 按单据时间排序 | 常见排序维度 |
| alarm | `uk_alarm_dedup` | (dedup_key) | 告警去重写入 | S5 |
| alarm | `idx_alarm_status_id` | (status, id) | 看板 COUNT + 列表 | id 单调递增相关，消除排序 |
| alarm | `idx_alarm_create_time` | (create_time) | 时间范围检索 | 主要时间维度 |

### 5.2 专项：`idx_sr_overdue_scan` 列顺序

（见 §4.7 末尾说明 + EXPLAIN 验证要求。）

### 5.3 明确「不建」的索引

| 不建 | 理由 |
|---|---|
| `access_record(pass_type)/(gate)/(direction)` 单列 | 区分度极差；查询总与时间范围同时出现，已被 `idx_ar_pass_time` 覆盖 |
| `access_record` 另建含时间列的权限索引 | `idx_perm_target_area` 已覆盖，纯冗余 |
| `material(status)/(category)` | 百至千级表，全表扫比走索引快；物资过万且分布倾斜再评估【待确认量级】 |
| `employee(dept)`、`vehicle(owner_type)` | 小表，无独立高价值查询 |
| `visitor(id_card)` 唯一约束 | 同一访客多次来访合法 |

## 6. 高频查询与优化（10 条）

| # | 场景 | 现状 | 优化 |
|---|---|---|---|
| 1 | 门禁校验（写入路径最高频） | 全表扫描 | 迁移后 `idx_perm_target_area` 解决；selectCount 可改 selectOne/LIMIT 1 |
| 2 | 通行流水分页 | `LIKE '%x%'` + 深翻页 | 前缀匹配 + 30 天窗口 + 游标分页 |
| 3 | 看板今日通行数 | 应用层拼串 | SQL 侧区间（**禁用 `DATE(pass_time)=CURDATE()`**，对列做函数不可走索引） |
| 4 | 看板 7 日趋势 | JVM 内存过滤 | SQL 聚合 `GROUP BY DATE(pass_time), direction` |
| 5 | 物资逾期扫描 | 全表扫 | `idx_sr_overdue_scan` + 逐条小事务 |
| 6 | 访客滞留扫描 | 全表扫 | `idx_visitor_status_expire` |
| 7 | 权限时效扫描 ×3 | 全表扫 ×3 | `idx_perm_status_endtime`；可合并为 CASE WHEN 批量 UPDATE |
| 8 | 未处理告警数 | 全表扫 | `idx_alarm_status_id` |
| 9 | 出库前逾期校验 | N+1 | 批量 IN |
| 10 | 物资状态分布 | Java 分组 | `GROUP BY status` 让 DB 聚合 |

## 7. H2 → MySQL 迁移步骤

### 7.0 前置确认（必须先做）
1. **备份 H2 文件库**（`./data/smart-park.mv.db`，注意相对应用工作目录）；
2. **确认客户 MySQL 版本**（<8.0.16 走终稿 §七降级附注）；
3. **确认数据规模**（员工数/车数/日均通行）。

### 7.1 路径 A —— 空库上线（推荐，客户无真实数据时）

建库 → Flyway 建表（应用首次启动自动执行）→ 基础数据由客户 Excel 转 CSV 导入。

> 编码提醒：Excel 导出 CSV 默认 **GBK**，先 `iconv -f GBK -t UTF-8` 转码再导入。
> 【落地变更】MySQL 8 默认 `local_infile=OFF`，导入管道改 CSV→INSERT（运维组 v1.4 已固化，勿常开 local_infile 开关）。

### 7.2 路径 B —— H2 存量数据迁移

1. **停应用**（释放 H2 文件锁）；
2. **CSV 导出**（`org.h2.tools.Shell` + `CALL CSVWRITE(...)` 逐表导出；`SCRIPT` 生成的是 H2 方言 SQL 不能直接喂 MySQL，仅作备份留档）；
3. **MySQL 建结构**（不导数据）；
4. **导入**：时间字段 `STR_TO_DATE(@x, '%Y-%m-%d %H:%i:%s')` 强制类型转换（杜绝脏格式混入），空串 `NULLIF` 处理；
5. **导入后校验（不可跳过）**：①逐表行数比对；②时间 NULL 检出须 0 行；③MIN/MAX 边界合理性；④孤儿引用检查（stock_record→material；`M-SPARE-009` 作反向用例验证检查脚本本身有效）；⑤门禁校验与逾期扫描两条 EXPLAIN 验证（key 与 key_len）；
6. **切换数据源启动**，冒烟 + 看板回归。

### 7.3 迁移期风险

时区偏移 8 小时（先固定 TZ 再迁移，迁移后比对已知记录）；大小写冲突（导入前 UPPER 归一化）；CSV GBK 乱码（全程 utf8mb4）；自增 ID 未前进（`ALTER TABLE t AUTO_INCREMENT = MAX+1`）。

## 8. 版本化迁移：Flyway

### 8.1 目录结构

```
smart-park-backend/src/main/resources/db/migration/
├── V1.0.0__init_schema.sql              # 8 表 + 索引 + CHECK
├── V1.0.1__add_audit_columns.sql
├── V1.1.0__visitor_pii_encryption.sql   # Phase2
├── V1.1.1__backfill_pii_encryption.sql
├── V1.2.0__add_alarm_dedup_key.sql
└── undo/                                # 人工回滚脚本（社区版不自动执行）
```

**命名规范**：`V{主}.{次}.{修订}__{下划线描述}.sql`，版本与 pom 对齐；双下划线分隔；一个版本只做一件事（DDL 与 DML 回填分离）；脚本**不写 DROP**；向前兼容（加列可空/有默认；跨版本删列）；合入主干后**禁止再修改内容**（校验和不匹配启动失败）。

### 8.2 选型：Flyway（否决 Liquibase / 继续用 schema.sql）

- **否决 schema.sql + sql.init**：无版本历史、无法演进（改文件不触发执行）、破坏性设计（正是 B1 根因）、无校验和、无审计；
- **否决 Liquibase**：跨库抽象层本项目用不上（只跑 MySQL 8）；团队是 MyBatis-Plus 原生 SQL 栈，changelog 学习成本高；`.sql` 可直接给客户 DBA 评审、无 Java 环境也能执行；其 rollback 优势被"向前兼容 + 备份恢复"覆盖；
- **自知代价**：社区版无自动 undo（配 undo/ 人工脚本）、无 dry-run（预发先全跑一遍）、误操作拦截有限（**必配 `clean-disabled: true` 生产保险丝**）。

### 8.3 pom 变更

```xml
<dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId></dependency>
<dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
```

> **Flyway 版本陷阱**：Boot 3.2.12 托管 Flyway 9.22.3，MySQL 支持内置。若手动覆盖到 Flyway 10+，必须额外引 `flyway-mysql`，否则报 `Unsupported Database: MySQL 8.0`。**建议直接用 Boot 托管版本，不覆盖。**

## 9. `application.yml` 数据源改造

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${DB_HOST:127.0.0.1}:${DB_PORT:3306}/${DB_NAME:smart_park}?useUnicode=true&characterEncoding=utf8mb4&connectionCollation=utf8mb4_0900_ai_ci&serverTimezone=Asia/Shanghai&useSSL=${DB_USE_SSL:true}&rewriteBatchedStatements=true&useLegacyDatetimeCode=false&zeroDateTimeBehavior=CONVERT_TO_NULL&allowMultiQueries=false
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      pool-name: SmartParkHikariCP
      minimum-idle: 5
      maximum-pool-size: 20
      idle-timeout: 600000
      max-lifetime: 1500000          # 必须 < MySQL wait_timeout
      connection-timeout: 30000
      keepalive-time: 300000
      leak-detection-threshold: 60000
  sql:
    init:
      mode: never                    # ★ B1 修复开关
  flyway:
    enabled: true
    locations: classpath:db/migration
    encoding: UTF-8
    baseline-on-migrate: true
    baseline-version: 0
    validate-on-migrate: true
    clean-disabled: true             # ★ 生产保险丝
    out-of-order: false
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: Asia/Shanghai
    default-property-inclusion: non_null

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

关键项：无 JPA 故无 `ddl-auto`（等价开关是 sql.init.mode）；`allowMultiQueries=false` 防注入叠加；不配 `connection-test-query`（JDBC4 `isValid()` 更快，保活用 keepalive-time）；同时必须改 `MybatisPlusConfig` 的 `DbType.H2 → DbType.MYSQL`；生产移除 `spring.h2.console` 整段。

## 10. 生产数据治理

### 10.1 容量估算（假设：员工 3000 / 车辆 500 / 日通行 1 万）
1 年 ≈ 365 万行 / 0.5GB；3 年 ≈ 1095 万行 / 1.5GB。**无需分库分表、无需分区，只需归档**（单表超 2000 万行再评估分区，分区需改主键含分区列）。

### 10.2 备份策略
每日 **02:30** `mysqldump --single-transaction --master-data=2 --routines --triggers --set-gtid-purged=OFF | gzip`（crontab `30 2 * * *`，已与运维落地的 `scripts/backup.sh` 对齐；巡检断言"备份文件 mtime < 25h 即 P1"亦按 02:30 校准）；binlog（ROW/MINIMAL）连续归档支撑 PITR；日全量保留 30 天 + 月全量 12 个月 + binlog 7 天；`innodb_flush_log_at_trx_commit=1` + `sync_binlog=1`；异地/离线一份防勒索；**发布窗口避开 02:00±1h**（`--single-transaction` 与 Flyway DDL 并发有风险）——注意"发布窗口避开 02:00±1h"与"备份 02:30"是两个不同口径，前者是发布约束，不要一起改。

### 10.3 RPO / RTO
【终稿裁决 9 修正】分层承诺：基础档 RPO 24h / RTO 4h（默认交付）；高保障档 RPO 15min / RTO 30min（客户选购，本文 binlog 方案为实现基础）。

### 10.4 敏感字段加密（Phase 2）
三列存储（cipher VARBINARY + hash BINARY(32) HMAC 精确查询 + mask 展示）；**禁用 MySQL `AES_ENCRYPT()`**（密钥进 binlog 与慢日志）；只支持等值查询（业务上够用）；注意 HTTP access log 请求体含明文身份证也需脱敏。

### 10.5 归档策略
`access_record` 在线 24 个月、`stock_record` 36 个月【待确认合规口径】；归档表 `ROW_FORMAT=COMPRESSED`；**分批搬运**（每批 5000 行 + sleep 0.1s，先 INSERT 后 DELETE 循环至影响行数为 0），每月低峰执行一次，执行前先做当日备份。

### 10.6 权限最小化
应用账号仅 DML（不给 DDL/DROP）；迁移账号仅 Flyway 执行期；备份账号 SELECT+LOCK TABLES+RELOAD+REPLICATION CLIENT。

## 11. 工作量与实施顺序

| 阶段 | 人日 |
|---|---|
| P1 迁移上线（DDL + Flyway + 配置 + 导入脚本 + 联调回归） | 4.5 |
| P2 治理改造（加密 + 逻辑删除/乐观锁 + 告警去重 + 查询优化） | 6.0 |
| P3 运维体系（备份 + 演练 + 归档 + 监控） | 3.5 |
| **合计**（不含现场协调、客户数据整理、UAT） | **≈ 14** |

实施顺序：阶段 0 前置确认 → 阶段 1 阻断项清零（B1/B2/B3/B5 + Flyway 接管）→ 阶段 2 结构与索引落地（含 EXPLAIN 验证）→ 阶段 3 数据迁移（双路径 + 五项校验）→ 阶段 4 并发与一致性强化（条件更新 + 逻辑删除 + 定时任务事务 + 告警去重）→ 阶段 5 查询性能优化 → 阶段 6 安全与运维（加密须在客户录入真实身份证前完成）。

## 12. 待确认清单

① 客户 MySQL 版本（<8.0.16 走降级附注）② 服务器时区 ③ 数据量级（员工/车辆/日通行/物资）④ 物资量级 ⑤ 一人一区多条权限业务口径 ⑥ 留存合规期 ⑦ SLA ⑧ 一物一码 vs 批量数量 ⑨ 部署实例数 ⑩ H2 存量数据有无 ⑪ `idx_sr_overdue_scan` EXPLAIN 实测。

---

## 13. V1 落地变更说明（实施阶段补记，储海量）

> 本节记录**本评审稿 → `db/migration/V1__init.sql` 实际落地**之间的全部差异与原因。
> 评审稿正文（§1~§12）保持不变，**落地以本节 + 两个 ops 文件为准**。
> 配套文件：`smart-park-backend/src/main/resources/db/migration/V1__init.sql`、`docs/ops/archive.sql`、`docs/ops/migration-h2-to-mysql.md`。

### 13.0 三项待确认已闭环（①②③）

| 原待确认项 | 结论 | 连带影响 |
|---|---|---|
| ① MySQL 版本 | **确认为 8.0（≥8.0.16）** | CHECK 约束与 `utf8mb4_0900_ai_ci` 均可用；**§七的 5.7 降级附注作废**，不再需要"3 条变换规则 + `DROP CHECK` 语法错误陷阱" |
| ② 服务器时区 | **统一 `Asia/Shanghai`**（OS 层 / my.cnf / JDBC `serverTimezone` / Jackson 四层） | DATETIME 不做时区转换这一选择的最后一块短板补齐 |
| ③ 数据量级 | **员工 3000 / 车辆 500 / 日均通行 ≤ 1 万** | 3 年约 1095 万行 ≈ 1.5GB，**不分区、不分表，只归档**（§10.1 结论维持） |

### 13.1 相对评审稿的 11 处落地变更

| # | 变更 | 原因 |
|---|---|---|
| 1 | **PII 三列加密从 Phase 2 提前到 V1 基线**（裁决 17） | 不留"明文先行、后补加密"的窗口期。客户一旦录入真实身份证，回溯加密要把历史明文全部扫一遍，代价远高于一开始就是密文 |
| 2 | `visitor.id_card` / `visitor.phone` / `employee.phone`：**原列名存密文**（VARCHAR(255)），新增 `<列名>_hash CHAR(64)` + `<列名>_mask VARCHAR(32)` | 原列名承载密文 → MyBatis-Plus 实体与 8 个 Mapper **零改动**（它们全是空接口，无自定义 SQL）。密文长度约 64 字节，给到 255 留足余量 |
| 3 | **`Employee` 无身份证字段**（已复核 `Employee.java`：仅 `id/empNo/name/dept/phone/status`） | 只有 `employee.phone` 走三列；不存在 `employee.id_card*`。若客户后续要求登记员工证件，需走 V2 加列 |
| 4 | **明令禁用 MySQL `AES_ENCRYPT()`**，加解密全部在 Java 层，密钥来自 `SMARTPARK_CRYPTO_KEY` / `SMARTPARK_CRYPTO_HMAC_KEY` | `AES_ENCRYPT()` 的密钥写在 SQL 文本里，会落进 binlog、慢日志、performance_schema，等于把密钥备份到所有能读日志的人手里 |
| 5 | **CHECK 最终 20 条**，表 9 张主表 + 2 张归档表；`chk_ar_gate` 删除、`chk_perm_time_range` 补建、`sys_user` 新增、5 条约束改名对齐 QA 断言矩阵 | `chk_ar_gate` 按裁决删除（应用层 `GATE_AREA` 已拦，加 CHECK 会让客户新增道闸变成改表 DDL）；`alarm.type` 不纳入（由代码常量生成、不接收外部输入，且新增告警类型是正常扩展，加 CHECK 会阻塞扩展）。见下方 5a / 5b / 5c |
| 6 | **归档表先 `CREATE TABLE ... LIKE` 再 `DROP CHECK`** | LIKE 会连 CHECK 一起复制。不解耦的话，主表规则将来收紧（如删掉某个枚举值）时，归档作业会被归档表自己的约束拒绝，表现为**静默归档失败** |
| 7 | `deleted` / `version` 只加在 **6 张主数据表**（employee / vehicle / access_permission / visitor / material / alarm），**2 张流水表不加** | 流水不可删除、生命周期只走归档；给流水加逻辑删除会诱导"删流水"这种错误用法 |
| 8 | **V1 只落 `deleted` 列，不做过滤** | 半套逻辑删除最危险：查不到却仍占唯一键。等 P2 配合 `@TableLogic` 一起启用 |
| 9 | **唯一键维持原样**（`uk_employee_emp_no` / `uk_vehicle_plate_no` / `uk_material_code`），软删除与唯一键的冲突延后 | 冲突解法是 P2 的 `(xxx, deleted_at)`（`deleted_at DATETIME`，时间戳永不撞键 + 自带删除审计），V1 不引入 `deleted_at` |
| 10 | **路径 B 的导入机制改为 CSV → INSERT → 管道**，`LOAD DATA LOCAL INFILE` 全部移除 | MySQL 8 默认 `local_infile=OFF`（ERROR 1148/3948）。此外 3306 已不对外暴露、`LOADDATA` 服务端文件模式还要动 `secure_file_priv`，为一次性迁移扩大生产库攻击面不划算 |
| 11 | **校验②的断言内容作废并替换** | 原断言 `pass_time IS NULL` 行数为 0，在直插方案下**恒为 0，是空断言**。替换为：导入 stderr 必须 0 字节 + 源端时间列正则预检 + 目标端 `0000-00-00` 兜底计数 |
| 12 | **转换环节统一复用 `scripts/csv-to-inserts.py`**，本手册不再自带转换器；`id` 不进列清单走自增 | 与「客户 Excel 基础数据导入」是同一条管道，另建第二套会两边漂移。连带修正：H2 导出去掉 `null=\N`（该脚本把空字段映射为 NULL，加哨兵反而会被当成字面 `'\N'`）；`material.valuable` 在导出 SQL 里用 `CASE WHEN ... THEN 1 ELSE 0 END` 转 0/1（H2 导出 `TRUE/FALSE` 直插 TINYINT(1) 在 STRICT 下报 1366）；**不使用 `SET id = NULL`**（主键 `NOT NULL AUTO_INCREMENT`，该写法本身非法） |

**5a · `chk_perm_time_range CHECK (end_time >= start_time)`（补建）**
`AccessPermission` 确有 `start_time` / `end_time`（已复核实体）。字段已改 DATETIME，加这条成本极低，能挡住"有效期倒挂"这类真实脏数据；API 层校验保留、DB 作兜底，双层不冲突。
用 **`>=` 而非 `>`**：只拦真倒挂（`end < start`），零时长（`start = end`）放行。拦零时长是业务判断不是数据完整性判断，且客户批量导入历史权限时容易撞上，没必要为它挡住一次导入。

**5b · 5 条约束改名（对齐 QA 的 TC-ENUM 断言矩阵）**

| 原（V1 初稿缩写） | 现（完整表名） |
|---|---|
| `chk_emp_status` | `chk_employee_status` |
| `chk_veh_status` | `chk_vehicle_status` |
| `chk_veh_owner_type` | `chk_vehicle_owner_type` |
| `chk_mat_status` | `chk_material_status` |
| `chk_mat_category` | `chk_material_category` |

约束名会出现在 MySQL 报错与运维排障信息里，完整表名可读性更好。V1 尚未在任何环境执行，改名零迁移成本。
三个长表名沿用评审稿既有缩写 `chk_perm_*` / `chk_ar_*` / `chk_sr_*`（与 QA 矩阵一致）。
**⚠ 改约束名必须同步通知 QA**：断言按「错误号 3819 + 约束名」双条件匹配，名字对不上会漏检或误报。

**5c · 新增 `sys_user` 表（最小鉴权，backend 的 `SysUser` 实体严格对齐）**
M2 鉴权落地需要，V1 原 8 张主表里没有它，缺表会导致首次登录即报错。
- 字段：`id / username / password_hash VARCHAR(100) / real_name / role / status / create_time / update_time`
- **不加 `deleted` / `version`**：账号不做软删除（"禁用"即可），登录路径也不需要乐观锁
- 唯一键 `uk_sys_user_username`；CHECK 两条：`chk_sys_user_role`（`ADMIN`/`GATE`/`STORE`）、`chk_sys_user_status`（`启用`/`禁用`）
- **登录名大小写不敏感**（沿用 `utf8mb4_0900_ai_ci`）：`admin` 与 `Admin` 视为同一账号。刻意选择——防止形似账号（易混淆 / 冒名）。代价是创建同名不同大小写会撞 **1062**，应用层要把 1062 转成"登录名已存在"的友好提示，不要直接抛 500
- **无 H2 存量来源**，不参与路径 B 迁移；由应用 `AdminInitializer` 初始化三角色账号

**最终 20 条清单**（`SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_TYPE='CHECK'` 应逐字返回）：

```
chk_alarm_level            chk_alarm_status           chk_ar_direction
chk_ar_pass_type           chk_ar_result              chk_employee_status
chk_material_category      chk_material_status        chk_perm_area
chk_perm_status            chk_perm_target_type       chk_perm_time_range
chk_sr_approve_status      chk_sr_type                chk_sys_user_role
chk_sys_user_status        chk_vehicle_owner_type     chk_vehicle_status
chk_visitor_area           chk_visitor_status
```

（归档表 2 张的 CHECK 已在 V1 内 `DROP`，故 `information_schema` 中不含 `*_archive`。）

**表与索引总账**：9 张主表 + 2 张归档表；12 个二级索引 + 5 个唯一键 + 9 个主键。

### 13.2 V1 的禁止项（写进文件头，改动前必读）

1. **不得出现 `DROP TABLE` / `DROP DATABASE` / `DROP INDEX`**（B01 根因）。
   文件中唯一的 DROP 是归档表的 `ALTER TABLE ... DROP CHECK`。
   自查：`grep -nEi "drop[[:space:]]+(table|database|schema|index)" V1__init.sql` → 必须无输出。
2. **时间字段一律 `DATETIME`**，业务时间与 `create_time`/`update_time` 审计时间分开，不复用。
3. **PII 明文永不落库**；`*_hash` 之外的 PII 列不建任何索引（密文等值无意义，精确查询一律走 hash）。

### 13.3 索引最终清单（V1 实际落地）

| 表 | 索引 | 类型 | 服务查询 | 理由 |
|---|---|---|---|---|
| employee | `uk_employee_emp_no (emp_no)` | 唯一 | `eq(emp_no)` 门禁校验 + 工号重复校验 | 业务唯一 |
| employee | `idx_emp_phone_hash (phone_hash)` | 普通 | 加密后按手机号精确查 | 密文列无法等值，hash 是唯一入口 |
| vehicle | `uk_vehicle_plate_no (plate_no)` | 唯一 | `eq(plate_no)` 门禁校验 | 业务唯一 |
| access_permission | `idx_perm_target_area (target_type, target_no, area)` | **普通** | 门禁判定 `count(...)>0` | **裁决 8**：普通索引与该查询等价，做成唯一键会挡住"同人同区续期" |
| access_permission | `idx_perm_expire (status, end_time)` | 普通 | `scanPermissions` 三条 UPDATE | 等值在前、范围在后 |
| access_record | `idx_ar_pass_time (pass_time)` | 普通 | 列表时间范围 + 时间倒序 | 大表主力索引 |
| access_record | `idx_ar_target_time (target_no, pass_time)` | 普通 | 按人/车查轨迹 | 当前 `pageQuery` 用 `like('%v%')` 用不上；为明确需求预留，确认无此需求可在 V2 删 |
| visitor | `idx_visitor_status_expire (status, expire_time)` | 普通 | `scanVisitors` | 等值 + 范围 |
| visitor | `idx_visitor_id_card_hash` / `idx_visitor_phone_hash` | 普通 | 加密后精确查 | 同上，P2 能力预留 |
| material | `uk_material_code (code)` | 唯一 | `eq(code)` 多处 | 一物一码 |
| stock_record | `idx_sr_material_code (material_code)` | 普通 | 按物资查流水 | 沿用原表 |
| stock_record | `idx_sr_overdue_scan (type, return_time, approve_status, expect_return_time)` | 普通 | `scanMaterials` + 领用前逾期校验 | **列序特意排过**：`return_time IS NULL` 在 MySQL 按等值 ref 处理，提到 `IN` 之前可多拿一个 key part |
| stock_record | `idx_sr_borrower_overdue (borrower, type, return_time, approve_status)` | 普通 | `checkOverdueSameCategory` | 写路径，每次领用都跑 |
| alarm | `uk_alarm_dedup_key (dedup_key)` | 唯一 | P2 告警去重 | MySQL 唯一索引允许多个 NULL，历史数据不受影响 |
| alarm | `idx_alarm_status_time (status, create_time)` | 普通 | 仪表盘未处理告警数 + 列表筛选 | 等值 + 倒序 |

**明确不建**（避免后人补建踩坑）：
`employee(status)` / `vehicle(status)` / `material(status)` —— 基数 2~4，表仅 3000 / 500 / 数千行，全表扫描更快；
`access_record(result,pass_time)` / `(gate,pass_time)` —— 列表默认不带该条件，且 `pass_time` 已能过滤绝大部分行，多一个索引的写放大（日增 1 万）不划算；若"只看拦截记录"成为高频入口再在 V2 补。

### 13.4 迁移手册要点（`docs/ops/migration-h2-to-mysql.md`）

- 推荐 **路径 A（空库启动）**：客户首次上线、H2 只有演示数据时走 A，Flyway 自动建表，不迁任何数据。
- **路径 B** 仅在客户现场已有真实存量时使用，四段管道：H2 `CSVWRITE` → CSV → python 转 INSERT → `docker exec -i`。
- CSV 导出必须带 **`null=\N`**：H2 默认把 NULL 与空字符串都导成空字段，MySQL 侧无法区分；用哨兵后在转换器里翻译成 SQL `NULL`。
- 原子性：**每张表一个事务**，表内必须原子（部分导入比整体失败更糟），表间不要求（无物理外键）。
- 会话必须锁 `sql_mode` 含 `STRICT_TRANS_TABLES, NO_ZERO_DATE, NO_ZERO_IN_DATE`，否则脏日期被静默写成 `0000-00-00`。
- **QA 断言请写错误码 3819**，不要只写"插入失败"——1364/1406/1062 也会让插入失败，只断言"失败"会产生假通过。
- 五项校验：① 行数一致 ② 脏日期为零（stderr 0 字节 + 源端正则 + 目标端零值兜底）③ 枚举合法性 ④ 孤儿引用 ⑤ 业务汇总与抽样比对。
- **`data.sql` 演示数据禁止导入生产**：含虚构身份证且是明文；`spring.sql.init.mode: never` 已保证应用不会执行它。

### 13.5 归档作业要点（`docs/ops/archive.sql`）

- `access_record` 在线 24 个月，`stock_record` 在线 36 个月，其余 6 张表不归档。
- 分批 5000 行 + `sleep 0.1`，**先 INSERT 后 DELETE，同事务**，靠 `(时间列, id)` 确定性排序保证"搬的就是删的"。
- `stock_record` 归档条件多一条：**只搬已完结单据**（`type='IN'` 或 `type='OUT' AND return_time IS NOT NULL`），在外未还的 OUT 单必须留在线表，否则逾期扫描与领用校验查不到。
- 三条校验：主表无残留超期行 / 归档表无混入未超期行 / 归档表无混入未完结行（第三条非零说明归档条件写错，必须回滚）。
- 【待确认】24 / 36 个月是建议值；**超出最短留存期后"删除归档数据"这一步不可逆**，未确认合规口径前不得删归档表。

### 13.6 仍未闭环（承接 §12）

④ 物资量级 ⑤ 一人一区多条权限业务口径（已按"允许多条"落地，需客户确认）⑥ 留存合规期 ⑦ SLA 档位 ⑧ 一物一码 vs 批量数量 ⑨ 部署实例数 ⑩ H2 存量数据有无（决定走路径 A 还是 B）⑪ `idx_sr_overdue_scan` 的 EXPLAIN 实测（上线前在客户库上跑一次）。

