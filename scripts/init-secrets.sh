#!/usr/bin/env bash
# ============================================================================
# 生成 MySQL 凭证文件（供容器内 --defaults-extra-file 使用，密码不落命令行/进程列表）
#   mysql/bak.cnf —— 备份账号（SELECT + LOCK TABLES + RELOAD + REPLICATION CLIENT）
#                    ⚠ 只有 SELECT 权限，拿它做 INSERT 会报 ERROR 1142
#   mysql/mig.cnf —— 迁移账号（SELECT/INSERT/UPDATE/DELETE + DDL，Flyway 建表与数据导入用）
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

# -------- 迁移账号（DML + DDL：Flyway 建表、数据导入）--------
cat > mysql/mig.cnf <<EOF
[mysql]
user=${MYSQL_MIG_USER:-smartpark_mig}
password=${MYSQL_MIG_PASSWORD}
EOF
chmod 600 mysql/mig.cnf

echo "✅ 已生成 mysql/bak.cnf（备份账号，600）"
echo "✅ 已生成 mysql/mig.cnf（迁移账号，600）"
echo "   提示：修改 .env 的账号密码后需重新执行本脚本并重启 mysql 容器"
echo "   注意：导入只能用 mig.cnf；用 bak.cnf 导入会报 ERROR 1142（权限不足）"
