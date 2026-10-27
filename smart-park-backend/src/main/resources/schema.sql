-- 智慧园区管理系统 H2 表结构（每次启动重建，保证演示数据一致）
-- 匹配 Java 实体类字段（开发环境用，生产用 MySQL + Flyway）
DROP TABLE IF EXISTS alarm;
DROP TABLE IF EXISTS stock_record;
DROP TABLE IF EXISTS material;
DROP TABLE IF EXISTS visitor;
DROP TABLE IF EXISTS access_record;
DROP TABLE IF EXISTS access_permission;
DROP TABLE IF EXISTS vehicle;
DROP TABLE IF EXISTS employee;
DROP TABLE IF EXISTS sys_user;

-- 员工
CREATE TABLE employee (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    emp_no      VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    dept        VARCHAR(64),
    phone       VARCHAR(255),
    phone_hash  CHAR(64),
    phone_mask  VARCHAR(32),
    status      VARCHAR(16)  NOT NULL DEFAULT '在职',
    CONSTRAINT uk_employee_emp_no UNIQUE (emp_no)
);

-- 车辆
CREATE TABLE vehicle (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plate_no   VARCHAR(32) NOT NULL,
    owner_type VARCHAR(16),
    owner_name VARCHAR(64),
    dept       VARCHAR(64),
    status     VARCHAR(16) NOT NULL DEFAULT '正常',
    CONSTRAINT uk_vehicle_plate_no UNIQUE (plate_no)
);

-- 通行权限（目标 + 区域 + 有效期）
CREATE TABLE access_permission (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL,
    target_no   VARCHAR(32) NOT NULL,
    target_name VARCHAR(64),
    area        VARCHAR(32) NOT NULL,
    start_time  VARCHAR(20) NOT NULL,
    end_time    VARCHAR(20) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT '生效'
);

-- 通行流水
CREATE TABLE access_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    pass_type   VARCHAR(16) NOT NULL,
    target_no   VARCHAR(32) NOT NULL,
    target_name VARCHAR(64),
    gate        VARCHAR(32),
    direction   VARCHAR(8),
    pass_time   VARCHAR(20) NOT NULL,
    result      VARCHAR(16) NOT NULL,
    reason      VARCHAR(255)
);
CREATE INDEX idx_access_record_pass_time ON access_record (pass_time);

-- 访客（含身份证/手机号三列：密文 + hash + 掩码）
CREATE TABLE visitor (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    visitor_name   VARCHAR(64) NOT NULL,
    id_card        VARCHAR(255),
    id_card_hash   CHAR(64),
    id_card_mask   VARCHAR(32),
    phone          VARCHAR(255),
    phone_hash     CHAR(64),
    phone_mask     VARCHAR(32),
    plate_no       VARCHAR(32),
    host_name      VARCHAR(64),
    host_dept      VARCHAR(64),
    reason         VARCHAR(255),
    area           VARCHAR(32),
    appoint_time   VARCHAR(20),
    expire_time    VARCHAR(20),
    status         VARCHAR(16) NOT NULL DEFAULT '已预约',
    check_in_time  VARCHAR(20),
    check_out_time VARCHAR(20)
);

-- 物资
CREATE TABLE material (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    code     VARCHAR(64) NOT NULL,
    name     VARCHAR(64) NOT NULL,
    category VARCHAR(16),
    spec     VARCHAR(128),
    dept     VARCHAR(64),
    location VARCHAR(64),
    valuable BOOLEAN     NOT NULL DEFAULT FALSE,
    status   VARCHAR(16) NOT NULL DEFAULT '在库',
    CONSTRAINT uk_material_code UNIQUE (code)
);

-- 出入库流水
CREATE TABLE stock_record (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_code      VARCHAR(64) NOT NULL,
    material_name      VARCHAR(64),
    type               VARCHAR(16) NOT NULL,
    borrower           VARCHAR(64),
    dept               VARCHAR(64),
    approve_status     VARCHAR(16) NOT NULL,
    expect_return_time VARCHAR(20),
    out_time           VARCHAR(20),
    return_time        VARCHAR(20),
    create_time        VARCHAR(20) NOT NULL,
    remark             VARCHAR(255)
);
CREATE INDEX idx_stock_record_material_code ON stock_record (material_code);

-- 告警
CREATE TABLE alarm (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    type        VARCHAR(32) NOT NULL,
    title       VARCHAR(128) NOT NULL,
    content     VARCHAR(512),
    level       VARCHAR(16) NOT NULL DEFAULT '警告',
    status      VARCHAR(16) NOT NULL DEFAULT '未处理',
    create_time VARCHAR(20) NOT NULL,
    handle_time VARCHAR(20)
);

-- 系统账号（登录用）
CREATE TABLE sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    real_name     VARCHAR(64) NOT NULL,
    role          VARCHAR(16) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT '启用',
    create_time   VARCHAR(20) NOT NULL,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);
