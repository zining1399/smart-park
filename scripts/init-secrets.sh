#!/usr/bin/env bash
# ============================================================================
# 生成 MySQL 凭证文件（供容器内 --defaults-extra-file 使用，密码不落命令行/进程列表）
#   mysql/bak.cnf —— 备份账号（SELECT + LOCK TABLES + RELOAD + REPLICATION CLIENT）
#                    ⚠ 只有 SELECT 权限，拿它做 INSERT 会报 ERROR 1142
#   mysql/mig.cnf —— 迁移账号（DML + DDL），【仅】Flyway 建表使用
#   mysql/app.cnf —— 应用账号（仅 DML：SELECT/INSERT/UPDATE/DELETE，无 DDL/DROP）
#                    数据导入就用它：导入本质是批量 DML，语义匹配，且把最坏情况
#                    限制为"插入脏数据（可回滚、可校验发现）"而非删库
#
# 用法： chmod +x scripts/init-secrets.sh && ./scripts/init-secrets.sh
# 铁律：凭证文件权限 600、属主 root、严禁提交 git（.gitignore 已排除 mysql/*.cnf）
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

[ -f .env ] || { echo "❌ 未找到 .env，请先 cp .env.example .env 并填写"; exit 1; }

# shellcheck disable=SC1091
set -a; . ./.env; set +a

: "${MYSQL_BAK_PASSWORD:?未注入 MYSQL_BAK_PASSWORD（检查 .env）}"
: "${MYSQL_MIG_PASSWORD:?未注入 MYSQL_MIG_PASSWORD（检查 .env）}"
: "${MYSQL_APP_PASSWORD:?未注入 MYSQL_APP_PASSWORD（检查 .env）}"

umask 077
mkdir -p mysql

# -------- 备份账号（只读 + 锁表）--------
cat > mysql/bak.cnf <<EOF
[mysqldump]
user=${MYSQL_BAK_USER:-smartpark_bak}
password=${MYSQL_BAK_PASSWORD}

[mysql]
user=${MYSQL_BAK_USER:-smartpark_bak}
password=${MYSQL_BAK_PASSWORD}
EOF
chmod 600 mysql/bak.cnf

# -------- 迁移账号（DML + DDL：仅 Flyway 建表使用）--------
cat > mysql/mig.cnf <<EOF
[mysql]
user=${MYSQL_MIG_USER:-smartpark_mig}
password=${MYSQL_MIG_PASSWORD}
EOF
chmod 600 mysql/mig.cnf

# -------- 应用账号（仅 DML：数据导入使用）--------
cat > mysql/app.cnf <<EOF
[mysql]
user=${MYSQL_APP_USER:-smartpark_app}
password=${MYSQL_APP_PASSWORD}
EOF
chmod 600 mysql/app.cnf

echo "✅ 已生成 mysql/bak.cnf（备份账号：只读+锁表）"
echo "✅ 已生成 mysql/mig.cnf（迁移账号：仅 Flyway 建表）"
echo "✅ 已生成 mysql/app.cnf（应用账号：仅 DML，数据导入用它）"
echo "   提示：修改 .env 的账号密码后需重新执行本脚本并重启 mysql 容器"
echo "   注意：导入只能用 app.cnf；用 bak.cnf 会报 ERROR 1142（无 INSERT 权限）"
