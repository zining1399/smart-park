-- =============================================================================
-- Flyway 基线迁移脚本 · V1__init.sql
-- 智慧园区管理系统（smart-park）生产库表结构基线
-- 作者：储海量（数据建模工程师）   冻结依据：裁决 8 / 裁决 15 / 裁决 17
-- =============================================================================
--
-- 【CHECK 约束：共 20 条】
--   命名一律 `chk_<表名>_<列名>`；三个长表名沿用评审稿既有缩写
--   （access_permission → chk_perm_*、access_record → chk_ar_*、stock_record → chk_sr_*），
--   其余用完整表名（chk_employee_* / chk_vehicle_* / chk_material_* / chk_visitor_* /
--   chk_alarm_* / chk_sys_user_*）。
--   清单：chk_employee_status / chk_vehicle_status / chk_vehicle_owner_type /
--         chk_material_status / chk_material_category / chk_perm_target_type /
--         chk_perm_area / chk_perm_status / chk_perm_time_range / chk_ar_pass_type /
--         chk_ar_direction / chk_ar_result / chk_visitor_status / chk_visitor_area /
--         chk_sr_type / chk_sr_approve_status / chk_alarm_level / chk_alarm_status /
--         chk_sys_user_role / chk_sys_user_status
--   ⚠ 改约束名必须同步通知 QA：TC-ENUM 断言按「错误号 3819 + 约束名」双条件匹配，
--     名字对不上会漏检或误报。
--
-- =============================================================================
--
-- 【运行环境要求】
--   1. MySQL 8.0.16 及以上。
--      低于 8.0.16 时 CHECK 约束会被"静默忽略"（不报错、也不生效），比直接报错更危险。
--      本脚本依赖 CHECK 作为应用层校验之外的第二道防线，故版本下限是硬要求。
--   2. 库级字符集 utf8mb4 / 排序规则 utf8mb4_0900_ai_ci（由运维初始化脚本建库时设定，
--      本脚本在每张表上再显式声明一次，防止建库参数被覆盖时不生效）。
--   3. 会话时区 Asia/Shanghai（my.cnf default-time-zone='+08:00' 与 JDBC serverTimezone
--      双重固定）。DATETIME 不做时区转换，写入即读出，两侧必须一致。
--
-- 【本文件的三条硬约束（改动前必须先读）】
--   1. 不含任何 DROP TABLE / DROP DATABASE / DROP INDEX。
--      文件中唯一的 DROP 是归档表的 ALTER TABLE ... DROP CHECK（见文末 §10），
--      目的是解耦 CREATE TABLE ... LIKE 继承下来的 CHECK 约束。
--      自查命令（必须无输出）：
--        grep -nEi "drop[[:space:]]+(table|database|schema|index)" V1__init.sql
--   2. 时间字段一律 DATETIME，不用 TIMESTAMP：
--        无 2038 问题、不做隐式 UTC 转换、不受 5.6.5 "每表仅一个 DEFAULT CURRENT_TIMESTAMP"
--        的历史限制（本项目多张表需要 create_time + update_time 两个默认值）。
--      业务时间（pass_time / expire_time / end_time ...）与审计时间
--      （create_time / update_time）分开存放，不复用。
--   3. 个人敏感信息一律三列存储，禁止使用 MySQL AES_ENCRYPT()：
--        <列名>        VARCHAR(255)  AES-256-GCM 密文（Base64），Java 层加解密，密钥来自环境变量
--        <列名>_hash   CHAR(64)      HMAC-SHA256 十六进制，支撑等值查询
--        <列名>_mask   VARCHAR(32)   脱敏展示值（身份证前 6 后 4 / 手机号前 3 后 4）
--      密文放在原业务列名上，实体与 Mapper 零改动；
--      禁用 AES_ENCRYPT() 是因为密钥会出现在 SQL 文本里，进而落进 binlog 与慢日志。
--
-- 【本脚本未做的事（后续版本处理，不要在本文件里补）】
--   · 不启用逻辑删除过滤：deleted 列 V1 只落地不过滤，等 P2 配合 @TableLogic 一起启用，
--     避免出现"查不到但仍占唯一键"的半套逻辑删除。
--   · 不改唯一键：uk_employee_emp_no / uk_vehicle_plate_no / uk_material_code 保持原样，
--     与软删除的冲突（删除后重键）在 P2 用 (xxx, deleted_at) 解决，deleted_at 不在 V1。
--   · 不建外键：一致性由应用层保证，外键会让归档与批量导入变得难以控制。
--   · 不加分区：按员工 3000 / 车辆 500 / 日通行 1 万的确认量级，3 年约 1095 万行，
--     归档足够，分区需改主键含分区列，代价大于收益。
--
-- =============================================================================
-- §1 员工
-- =============================================================================
CREATE TABLE employee (
    id          BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    emp_no      VARCHAR(32)  NOT NULL                           COMMENT '工号，业务唯一；入库前应用层统一转大写（utf8mb4_0900_ai_ci 大小写不敏感，与 H2 行为不同）',
    name        VARCHAR(64)  NOT NULL                           COMMENT '姓名',
    dept        VARCHAR(64)  DEFAULT NULL                       COMMENT '部门',
    phone       VARCHAR(255) DEFAULT NULL                       COMMENT '手机号：AES-256-GCM 密文（Base64），禁止明文',
    phone_hash  CHAR(64)     DEFAULT NULL                       COMMENT '手机号 HMAC-SHA256 十六进制，用于等值查询',
    phone_mask  VARCHAR(32)  DEFAULT NULL                       COMMENT '手机号脱敏展示值，如 138****1234',
    status      VARCHAR(16)  NOT NULL DEFAULT '在职'             COMMENT '在职 / 离职',
    deleted     TINYINT      NOT NULL DEFAULT 0                 COMMENT '逻辑删除：0 未删除 1 已删除（V1 仅落列，P2 启用过滤）',
    version     INT          NOT NULL DEFAULT 0                 COMMENT '乐观锁版本号（P2 启用）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（审计）',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_employee_emp_no (emp_no),
    KEY idx_emp_phone_hash (phone_hash),
    CONSTRAINT chk_employee_status CHECK (status IN ('在职', '离职'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工';

-- =============================================================================
-- §2 车辆
-- =============================================================================
CREATE TABLE vehicle (
    id          BIGINT      NOT NULL AUTO_INCREMENT            COMMENT '主键',
    plate_no    VARCHAR(32) NOT NULL                           COMMENT '车牌号，业务唯一；入库前统一转大写并去掉分隔符',
    owner_type  VARCHAR(16) DEFAULT NULL                       COMMENT '员工 / 访客 / 仓储',
    owner_name  VARCHAR(64) DEFAULT NULL                       COMMENT '车主 / 使用人',
    dept        VARCHAR(64) DEFAULT NULL                       COMMENT '所属部门',
    status      VARCHAR(16) NOT NULL DEFAULT '正常'             COMMENT '正常 / 黑名单',
    deleted     TINYINT     NOT NULL DEFAULT 0                 COMMENT '逻辑删除（V1 仅落列）',
    version     INT         NOT NULL DEFAULT 0                 COMMENT '乐观锁版本号（P2 启用）',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（审计）',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vehicle_plate_no (plate_no),
    CONSTRAINT chk_vehicle_status     CHECK (status IN ('正常', '黑名单')),
    CONSTRAINT chk_vehicle_owner_type CHECK (owner_type IS NULL OR owner_type IN ('员工', '访客', '仓储'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车辆';

-- =============================================================================
-- §3 通行权限
--   索引说明：idx_perm_target_area 是【普通复合索引，不是唯一键】（裁决 8）。
--   门禁判定是 count(target_type, target_no, area) > 0，普通索引与该查询等价；
--   做成唯一键会挡住"同一人同区域续期"这类正常业务，故降级为普通索引。
-- =============================================================================
CREATE TABLE access_permission (
    id          BIGINT      NOT NULL AUTO_INCREMENT             COMMENT '主键',
    target_type VARCHAR(16) NOT NULL                            COMMENT 'PERSON / VEHICLE',
    target_no   VARCHAR(32) NOT NULL                            COMMENT '工号或车牌号',
    target_name VARCHAR(64) DEFAULT NULL                        COMMENT '姓名或车牌（冗余展示用）',
    area        VARCHAR(32) NOT NULL                            COMMENT '办公区 / 生产区 / 仓储区 / 生活区',
    start_time  DATETIME    NOT NULL                            COMMENT '有效期起（业务时间）',
    end_time    DATETIME    NOT NULL                            COMMENT '有效期止（业务时间）',
    status      VARCHAR(16) NOT NULL DEFAULT '生效'              COMMENT '生效 / 临期 / 失效（服务端按时间计算）',
    deleted     TINYINT     NOT NULL DEFAULT 0                  COMMENT '逻辑删除（V1 仅落列）',
    version     INT         NOT NULL DEFAULT 0                  COMMENT '乐观锁版本号（P2 启用）',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间（审计）',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    -- 门禁校验热路径：eq(target_type) + eq(target_no) + eq(area) + 时间范围
    KEY idx_perm_target_area (target_type, target_no, area),
    -- 定时任务 scanPermissions：eq(status) + lt/ge/le(end_time)，status 等值在前、end_time 范围在后
    KEY idx_perm_expire (status, end_time),
    CONSTRAINT chk_perm_target_type CHECK (target_type IN ('PERSON', 'VEHICLE')),
    CONSTRAINT chk_perm_area        CHECK (area IN ('办公区', '生产区', '仓储区', '生活区')),
    CONSTRAINT chk_perm_status      CHECK (status IN ('生效', '临期', '失效')),
    -- 有效期倒挂兜底：用 >= 而非 >，只拦真正的倒挂（end < start）。
    -- 零时长（start = end）放行：拦它是业务判断不是数据完整性判断，
    -- 且客户批量导入历史权限时容易撞上，没必要为它挡住一次导入。
    CONSTRAINT chk_perm_time_range  CHECK (end_time >= start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通行权限';

-- =============================================================================
-- §4 通行流水（大表：按日 1 万计，1 年约 365 万行）
--   不加 deleted / version：流水不可删除，生命周期只走归档。
--   不建 chk_ar_gate：门禁名称由应用层 GATE_AREA 常量兜住，加 CHECK 会让"客户新增道闸"
--   变成需要改表的 DDL（裁决：删除 chk_ar_gate，保留 chk_perm_area）。
-- =============================================================================
CREATE TABLE access_record (
    id          BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    pass_type   VARCHAR(16)  NOT NULL                           COMMENT 'PERSON / VEHICLE',
    target_no   VARCHAR(32)  NOT NULL                           COMMENT '工号或车牌',
    target_name VARCHAR(64)  DEFAULT NULL                       COMMENT '姓名或车牌',
    gate        VARCHAR(32)  DEFAULT NULL                       COMMENT '东门 / 西门 / 南门 / 货运门（应用层常量校验，不加 CHECK）',
    direction   VARCHAR(8)   DEFAULT NULL                       COMMENT 'IN / OUT',
    pass_time   DATETIME     NOT NULL                           COMMENT '通行时间（业务时间）',
    result      VARCHAR(16)  NOT NULL                           COMMENT '放行 / 拦截',
    reason      VARCHAR(255) DEFAULT NULL                       COMMENT '拦截原因',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录写入时间（审计）',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    -- 列表默认按时间倒序 + 时间范围筛选
    KEY idx_ar_pass_time (pass_time),
    -- 按人员 / 车牌查通行轨迹（精确匹配可用；当前 pageQuery 用的是 like('%v%')，用不上本索引，
    -- 保留是为"按人查轨迹"这一明确需求预留，若确认无此需求可在 V2 删除）
    KEY idx_ar_target_time (target_no, pass_time),
    CONSTRAINT chk_ar_pass_type CHECK (pass_type IN ('PERSON', 'VEHICLE')),
    CONSTRAINT chk_ar_direction CHECK (direction IS NULL OR direction IN ('IN', 'OUT')),
    CONSTRAINT chk_ar_result    CHECK (result IN ('放行', '拦截'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通行流水';

-- =============================================================================
-- §5 访客（含身份证 / 手机号三列加密，裁决 17）
--   id_card / phone 存密文；明文永不落库。查询只能走 *_hash 等值，脱敏展示走 *_mask。
-- =============================================================================
CREATE TABLE visitor (
    id              BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键',
    visitor_name    VARCHAR(64)  NOT NULL                            COMMENT '访客姓名',
    id_card         VARCHAR(255) DEFAULT NULL                        COMMENT '身份证：AES-256-GCM 密文（Base64），禁止明文',
    id_card_hash    CHAR(64)     DEFAULT NULL                        COMMENT '身份证 HMAC-SHA256 十六进制，用于等值查询',
    id_card_mask    VARCHAR(32)  DEFAULT NULL                        COMMENT '身份证脱敏展示值，如 110101********7733（前 6 后 4）',
    phone           VARCHAR(255) DEFAULT NULL                        COMMENT '手机号：AES-256-GCM 密文（Base64），禁止明文',
    phone_hash      CHAR(64)     DEFAULT NULL                        COMMENT '手机号 HMAC-SHA256 十六进制，用于等值查询',
    phone_mask      VARCHAR(32)  DEFAULT NULL                        COMMENT '手机号脱敏展示值，如 138****1234（前 3 后 4）',
    plate_no        VARCHAR(32)  DEFAULT NULL                        COMMENT '随行车牌（可空）',
    host_name       VARCHAR(64)  DEFAULT NULL                        COMMENT '被访人',
    host_dept       VARCHAR(64)  DEFAULT NULL                        COMMENT '被访部门',
    reason          VARCHAR(255) DEFAULT NULL                        COMMENT '来访事由',
    area            VARCHAR(32)  DEFAULT NULL                        COMMENT '授权区域：办公区 / 生产区 / 仓储区 / 生活区',
    appoint_time    DATETIME     DEFAULT NULL                        COMMENT '预约到访时间（业务时间）',
    expire_time     DATETIME     DEFAULT NULL                        COMMENT '授权失效时间（业务时间）',
    status          VARCHAR(16)  NOT NULL DEFAULT '已预约'            COMMENT '已预约 / 在场 / 已签销 / 滞留预警',
    check_in_time   DATETIME     DEFAULT NULL                        COMMENT '签到时间（业务时间）',
    check_out_time  DATETIME     DEFAULT NULL                        COMMENT '签离时间（业务时间）',
    deleted         TINYINT      NOT NULL DEFAULT 0                  COMMENT '逻辑删除（V1 仅落列）',
    version         INT          NOT NULL DEFAULT 0                  COMMENT '乐观锁版本号（P2 启用）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间（审计）',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    -- 定时任务 scanVisitors：eq(status='在场') + lt(expire_time)
    KEY idx_visitor_status_expire (status, expire_time),
    -- 加密后唯一可用的精确查询入口（当前代码未使用，属 P2 能力预留）
    KEY idx_visitor_id_card_hash (id_card_hash),
    KEY idx_visitor_phone_hash (phone_hash),
    CONSTRAINT chk_visitor_status CHECK (status IN ('已预约', '在场', '已签销', '滞留预警')),
    CONSTRAINT chk_visitor_area   CHECK (area IS NULL OR area IN ('办公区', '生产区', '仓储区', '生活区'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='访客';

-- =============================================================================
-- §6 物资
-- =============================================================================
CREATE TABLE material (
    id          BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    code        VARCHAR(64)  NOT NULL                           COMMENT '一物一码，业务唯一',
    name        VARCHAR(64)  NOT NULL                           COMMENT '物资名称',
    category    VARCHAR(16)  DEFAULT NULL                       COMMENT '工具 / 备件 / 车辆',
    spec        VARCHAR(128) DEFAULT NULL                       COMMENT '规格型号',
    dept        VARCHAR(64)  DEFAULT NULL                       COMMENT '归属部门',
    location    VARCHAR(64)  DEFAULT NULL                       COMMENT '存放位置',
    valuable    TINYINT(1)   NOT NULL DEFAULT 0                 COMMENT '是否贵重（出库需审批）',
    status      VARCHAR(16)  NOT NULL DEFAULT '在库'             COMMENT '在库 / 在借 / 逾期 / 维修中',
    deleted     TINYINT      NOT NULL DEFAULT 0                 COMMENT '逻辑删除（V1 仅落列）',
    version     INT          NOT NULL DEFAULT 0                 COMMENT '乐观锁版本号（P2 启用）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（审计）',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_code (code),
    CONSTRAINT chk_material_status   CHECK (status IN ('在库', '在借', '逾期', '维修中')),
    CONSTRAINT chk_material_category CHECK (category IS NULL OR category IN ('工具', '备件', '车辆'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='物资';

-- =============================================================================
-- §7 出入库流水
--   不加 deleted / version：流水不可删除，生命周期只走归档。
--   idx_sr_overdue_scan 的列序是特意排的：type（等值）→ return_time（IS NULL 在 MySQL
--   里按等值 ref 处理）→ approve_status（IN 范围）→ expect_return_time（范围，只能走 ICP）。
--   把 return_time 提到 IN 之前，可以多拿到一个 key part；反过来写则 IN 之后全部失效。
-- =============================================================================
CREATE TABLE stock_record (
    id                 BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键',
    material_code      VARCHAR(64)  NOT NULL                            COMMENT '物资编码（逻辑外键，无物理约束）',
    material_name      VARCHAR(64)  DEFAULT NULL                        COMMENT '物资名称（冗余展示用）',
    type               VARCHAR(16)  NOT NULL                            COMMENT 'IN / OUT',
    borrower           VARCHAR(64)  DEFAULT NULL                        COMMENT '领用人（IN 时可空）',
    dept               VARCHAR(64)  DEFAULT NULL                        COMMENT '领用部门',
    approve_status     VARCHAR(16)  NOT NULL                            COMMENT '免审 / 待审批 / 已批准 / 已驳回',
    expect_return_time DATETIME     DEFAULT NULL                        COMMENT '预计归还时间（业务时间）',
    out_time           DATETIME     DEFAULT NULL                        COMMENT '实际出库时间（业务时间）',
    return_time        DATETIME     DEFAULT NULL                        COMMENT '实际归还时间（业务时间）',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '单据创建时间【业务字段，非审计字段：应用会显式写入，为空时取当前时间】',
    remark             VARCHAR(255) DEFAULT NULL                        COMMENT '备注',
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    KEY idx_sr_material_code (material_code),
    -- 定时任务 scanMaterials + 领用前逾期校验：eq(type='OUT') + return_time IS NULL + IN(approve_status) + lt(expect_return_time)
    KEY idx_sr_overdue_scan (type, return_time, approve_status, expect_return_time),
    -- checkOverdueSameCategory（写路径，每次领用都跑）：eq(borrower) 打头
    KEY idx_sr_borrower_overdue (borrower, type, return_time, approve_status),
    -- 裁决 15：RETURN 是死枚举（归还走 OUT + return_time，不写第二条记录），故 chk_sr_type 只有 IN / OUT
    CONSTRAINT chk_sr_type           CHECK (type IN ('IN', 'OUT')),
    CONSTRAINT chk_sr_approve_status CHECK (approve_status IN ('免审', '待审批', '已批准', '已驳回'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出入库流水';

-- =============================================================================
-- §8 告警
--   dedup_key：P2 告警去重启用；MySQL 唯一索引允许多个 NULL，故历史数据与未去重告警不受影响。
--   alarm.type 不加 CHECK：告警类型由代码常量生成、不接收外部输入，且新增告警类型属正常
--   业务扩展，加 CHECK 会把扩展变成需要改表的 DDL。
-- =============================================================================
CREATE TABLE alarm (
    id          BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键',
    type        VARCHAR(32)  NOT NULL                            COMMENT '门禁异常 / 访客滞留 / 物资逾期 / 黑名单（代码常量生成，不加 CHECK）',
    title       VARCHAR(128) NOT NULL                            COMMENT '告警标题',
    content     VARCHAR(512) DEFAULT NULL                        COMMENT '告警内容',
    level       VARCHAR(16)  NOT NULL DEFAULT '警告'              COMMENT '提示 / 警告 / 严重',
    status      VARCHAR(16)  NOT NULL DEFAULT '未处理'            COMMENT '未处理 / 已处理',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '告警产生时间【业务字段：应用显式写入，为空时取当前时间】',
    handle_time DATETIME     DEFAULT NULL                        COMMENT '处理时间（业务时间）',
    dedup_key   VARCHAR(128) DEFAULT NULL                        COMMENT '告警去重键（P2 启用）：NULL 不参与唯一判定',
    deleted     TINYINT      NOT NULL DEFAULT 0                  COMMENT '逻辑删除（V1 仅落列；误报告警可下线）',
    version     INT          NOT NULL DEFAULT 0                  COMMENT '乐观锁版本号（P2：并发处理同一条告警）',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alarm_dedup_key (dedup_key),
    -- 仪表盘「未处理告警数」+ 告警列表按状态筛选、按时间倒序
    KEY idx_alarm_status_time (status, create_time),
    CONSTRAINT chk_alarm_level  CHECK (level IN ('提示', '警告', '严重')),
    CONSTRAINT chk_alarm_status CHECK (status IN ('未处理', '已处理'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警';

-- =============================================================================
-- §9 系统账号（最小鉴权：登录 + 三角色；backend 的 SysUser 实体严格对齐本表）
--
--   不加 deleted / version：账号不做软删除（"禁用"即可），登录路径也不需要乐观锁。
--   username 唯一键在 utf8mb4_0900_ai_ci 下**大小写不敏感**：'admin' 与 'Admin' 视为同一账号。
--   这是刻意选择——登录名大小写不敏感可防止形似账号（易混淆 / 冒名），
--   代价是创建同名不同大小写的账号会被 ERROR 1062 挡住，应用层要把 1062 转成
--   "登录名已存在"的友好提示，不要直接抛 500。
-- =============================================================================
CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    username      VARCHAR(64)  NOT NULL                           COMMENT '登录名，唯一（大小写不敏感）',
    password_hash VARCHAR(100) NOT NULL                           COMMENT 'BCrypt 散列（strength 12，60 字符），永不回传前端',
    real_name     VARCHAR(64)  NOT NULL                           COMMENT '姓名',
    role          VARCHAR(16)  NOT NULL                           COMMENT 'ADMIN 管理员 / GATE 门岗 / STORE 仓管',
    status        VARCHAR(16)  NOT NULL DEFAULT '启用'             COMMENT '启用 / 禁用',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（审计）',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（审计）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    CONSTRAINT chk_sys_user_role   CHECK (role IN ('ADMIN', 'GATE', 'STORE')),
    CONSTRAINT chk_sys_user_status CHECK (status IN ('启用', '禁用'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统账号';

-- =============================================================================
-- §10 归档表
--   用 CREATE TABLE ... LIKE 建：自动继承主表列定义与索引，主表结构变更时只需同步一次。
--   建完立刻 DROP 掉继承来的 CHECK（裁决：解耦）——归档表存的是历史数据，主表规则将来收紧
--   （例如新增枚举值后又删掉）时，归档作业会被归档表自己的约束拒绝，导致静默归档失败。
--   归档作业脚本见 docs/ops/archive.sql。
--
--   注意：MySQL 8.0 的 CREATE TABLE ... LIKE **会**继承 CHECK，但约束名自动改写为
--   `<表名>_chk_<序号>`（按主表定义顺序）。用主表原名 DROP 会报
--   ERROR 3821 "Check constraint ... is not found in the table."——这只代表名字不对，
--   不代表归档表没有约束；切勿据此删掉下面的 DROP CHECK 行：删了全库 CHECK 会变成 25 条，
--   scripts/smoke.sh ④ 与 docs/reviews/03-data-review.md 的「20 条逐字清单」当场对不上，
--   且本节的归档解耦裁决失效。
-- =============================================================================
CREATE TABLE access_record_archive LIKE access_record;
-- 注：MySQL 8.0 的 CREATE TABLE ... LIKE **会**继承 CHECK，但约束名被自动改写为
-- `<table>_chk_<n>`（按主表 CHECK 定义顺序），不能再用原名 chk_ar_pass_type 等 DROP。
-- 归档表存历史数据，主表将来收紧枚举时归档作业不能被自己的旧约束拒掉，故全部 drop。
ALTER TABLE access_record_archive
    DROP CHECK access_record_archive_chk_1,
    DROP CHECK access_record_archive_chk_2,
    DROP CHECK access_record_archive_chk_3;
ALTER TABLE access_record_archive ROW_FORMAT=COMPRESSED;

CREATE TABLE stock_record_archive LIKE stock_record;
ALTER TABLE stock_record_archive
    DROP CHECK stock_record_archive_chk_1,
    DROP CHECK stock_record_archive_chk_2;
ALTER TABLE stock_record_archive ROW_FORMAT=COMPRESSED;

-- =============================================================================
-- §11 索引总清单（12 个二级索引 + 5 个唯一键 + 9 个主键，共 9 张主表 + 2 张归档表）
--   唯一键：uk_employee_emp_no / uk_vehicle_plate_no / uk_material_code /
--           uk_alarm_dedup_key / uk_sys_user_username
--   明文不建索引：*_hash 之外的 PII 列（id_card / phone 密文）不建任何索引，
--   密文等值无意义，精确查询一律走 *_hash。
--
--   明确不建的索引（理由，避免后人补建时踩坑）：
--     · employee(status) / vehicle(status) / material(status)：
--       基数极低（2~4 个值）且表只有 3000 / 500 / 数千行，全表扫描比走索引更快。
--     · access_record(result, pass_time) / (gate, pass_time)：
--       列表默认不带 result / gate 条件；带上时 pass_time 已能过滤绝大部分行，
--       回表再过滤的代价低于多一个索引的写放大（日增 1 万行）。
--       若客户明确要求"只看拦截记录"成为高频入口，再在 V2 补 (result, pass_time)。
--     · access_permission(target_no)：已被 idx_perm_target_area 的最左前缀覆盖（不带
--       target_type 的查询走不了，但业务上不存在这种查询）。
-- =============================================================================
