#!/usr/bin/env bash
# ============================================================================
# 生成 MySQL 备份凭证文件 mysql/bak.cnf（供容器内 mysqldump --defaults-extra-file 使用）
# 用法： chmod +x scripts/init-secrets.sh && ./scripts/init-secrets.sh
# 铁律：凭证文件权限 600、属主 root、严禁提交 git（.gitignore 已排除 mysql/bak.cnf）
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

[ -f .env ] || { echo "❌ 未找到 .env，请先 cp .env.example .env 并填写"; exit 1; }

# shellcheck disable=SC1091
set -a; . ./.env; set +a

: "${MYSQL_BAK_PASSWORD:?未注入 MYSQL_BAK_PASSWORD（检查 .env）}"

umask 077
mkdir -p mysql
cat > mysql/bak.cnf <<EOF
[mysqldump]
user=${MYSQL_BAK_USER:-smartpark_bak}
password=${MYSQL_BAK_PASSWORD}

[mysql]
user=${MYSQL_BAK_USER:-smartpark_bak}
password=${MYSQL_BAK_PASSWORD}
EOF
chmod 600 mysql/bak.cnf

echo "✅ 已生成 mysql/bak.cnf（600）"
echo "   提示：修改 .env 的 MYSQL_BAK_PASSWORD 后需重新执行本脚本并重启 mysql 容器"
