#!/bin/bash
# ============================================================================
# MySQL 三账号最小权限初始化
# 挂载到 /docker-entrypoint-initdb.d/ —— 仅在数据卷首次创建时执行一次
#
# 铁律：
#   1. 密码绝不给默认值：漏注入即在首次部署报明确错误（宁可失败，也不带错配授权上线）
#   2. 账号名非密钥，可给默认值兜底
#   3. 不要写 GRANT ALL ON <db>.flyway_schema_history：
#      该表在 init 阶段尚不存在，MySQL 8 会报 ERROR 1146 中断整个脚本
#      （库级 ON <db>.* 授权对 Flyway 后续新建表自动生效，无需单独授权）
#   4. mysql -uroot 保持默认 socket 连接（不写 -h），勿改成 -h 127.0.0.1
#   5. ★ app / mig 必须同时存在两段 host 授权：
#        @'<子网>.%'  —— 供 backend 容器经 compose 内网 TCP 连接（Flyway / 应用运行时）
#        @'localhost' —— 供 docker exec 内的 mysql 客户端（默认走 socket）做导入/归档/恢复
#      只授子网段时，docker exec 场景的连接 host 是 localhost，匹配不到任何账号，
#      报错是 ERROR 1045（拒绝连接）而非 1142（权限不足）—— 导入/归档/恢复会全线失败
# ============================================================================
set -euo pipefail

# ---- 密码强断言（漏注入立即失败，报因明确）----
: "${SP_NET_SUBNET:?未注入 SP_NET_SUBNET：检查 compose mysql.environment 段}"
: "${MYSQL_ROOT_PASSWORD:?未注入 MYSQL_ROOT_PASSWORD}"
: "${MYSQL_APP_PASSWORD:?未注入 MYSQL_APP_PASSWORD}"
: "${MYSQL_MIG_PASSWORD:?未注入 MYSQL_MIG_PASSWORD}"
: "${MYSQL_BAK_PASSWORD:?未注入 MYSQL_BAK_PASSWORD}"

# ---- 账号名与库名（非密钥，默认值兜底）----
MYSQL_APP_USER="${MYSQL_APP_USER:-smartpark_app}"
MYSQL_MIG_USER="${MYSQL_MIG_USER:-smartpark_mig}"
MYSQL_BAK_USER="${MYSQL_BAK_USER:-smartpark_bak}"
MYSQL_DATABASE="${MYSQL_DATABASE:-smart_park}"

export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"

# 授权 host 段由子网推导（改 .env 的 SP_NET_SUBNET 即自动联动，无需改本脚本）
HOST_PREFIX=$(echo "$SP_NET_SUBNET" | cut -d. -f1-2)   # 172.28.0.0/24 -> 172.28
APP_HOST="'${MYSQL_APP_USER}'@'${HOST_PREFIX}.%'"
MIG_HOST="'${MYSQL_MIG_USER}'@'${HOST_PREFIX}.%'"

mysql -uroot <<SQL
-- 1) 应用运行时账号：仅 DML，无 DROP/DDL 权限（SQL 注入或误操作破坏半径限制在数据行）
--    两段 host：子网段给 backend 容器 TCP 连接；localhost 给 docker exec（socket）
--    的数据导入 / 归档作业使用（两者密码相同、权限相同）
CREATE USER ${APP_HOST} IDENTIFIED BY '${MYSQL_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON ${MYSQL_DATABASE}.* TO ${APP_HOST};
CREATE USER '${MYSQL_APP_USER}'@'localhost' IDENTIFIED BY '${MYSQL_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON ${MYSQL_DATABASE}.* TO '${MYSQL_APP_USER}'@'localhost';

-- 2) Flyway 迁移账号：仅发布期使用（库级授权自动覆盖 flyway_schema_history 等新建表）
--    同理双 host：子网段给 Flyway；localhost 给 docker exec 场景的全量恢复（需建表+写入）
CREATE USER ${MIG_HOST} IDENTIFIED BY '${MYSQL_MIG_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER, REFERENCES, CREATE VIEW
  ON ${MYSQL_DATABASE}.* TO ${MIG_HOST};
CREATE USER '${MYSQL_MIG_USER}'@'localhost' IDENTIFIED BY '${MYSQL_MIG_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER, REFERENCES, CREATE VIEW
  ON ${MYSQL_DATABASE}.* TO '${MYSQL_MIG_USER}'@'localhost';

-- 3) 备份账号：只读 + 锁表，仅允许容器内 socket 连接（配合 docker exec）
CREATE USER '${MYSQL_BAK_USER}'@'localhost' IDENTIFIED BY '${MYSQL_BAK_PASSWORD}';
GRANT SELECT, LOCK TABLES, RELOAD, REPLICATION CLIENT, PROCESS ON *.*
  TO '${MYSQL_BAK_USER}'@'localhost';

FLUSH PRIVILEGES;
SQL

echo "[init] 账号创建完成：${MYSQL_APP_USER} / ${MYSQL_MIG_USER} (host: ${HOST_PREFIX}.% + localhost)；${MYSQL_BAK_USER} (host: localhost)"
