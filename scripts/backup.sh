#!/usr/bin/env bash
# ============================================================================
# MySQL 全量备份（每日 02:30 crontab）
#   crontab: 30 2 * * * /opt/smart-park/scripts/backup.sh >> /opt/smart-park/logs/backup.log 2>&1
#
# 要点：
#   - 走 docker exec（容器内网 socket）+ --defaults-extra-file，密码不落命令行/进程列表
#   - docker exec 不加 -t：避免 CR 污染 gzip 流
#   - --single-transaction 与 Flyway DDL 不能并发：发布窗口避开 02:00±1h，冲突时先发布后备份
#   - 保留 7 份本机备份；RSYNC_TARGET 非空时同步一份到异机/离线介质
#   - 备份失败必须告警：inspect.sh 断言最新备份 mtime < 25h
# ============================================================================
set -euo pipefail

BASE_DIR="${BASE_DIR:-/opt/smart-park}"
cd "$BASE_DIR"

DATE=$(date +%Y%m%d_%H%M)
DIR="$BASE_DIR/backup"
KEEP=7
mkdir -p "$DIR"

# 凭证文件检查（由 scripts/init-secrets.sh 生成）
[ -f mysql/bak.cnf ] || { echo "❌ 缺少 mysql/bak.cnf，请先执行 scripts/init-secrets.sh"; exit 1; }

# shellcheck disable=SC1091
[ -f .env ] && { set -a; . ./.env; set +a; }

CONTAINER="${MYSQL_CONTAINER:-smart-park-mysql}"
DB="${MYSQL_DATABASE:-smart_park}"

# 容器健康检查（不健康时拒绝备份并报警，避免备份到半成品状态）
STATUS=$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo missing)
[ "$STATUS" = "healthy" ] || { echo "❌ 容器 $CONTAINER 状态=$STATUS，跳过备份"; exit 1; }

TMP="$DIR/full_${DATE}.sql.gz.tmp"
OUT="$DIR/full_${DATE}.sql.gz"

# 关键：docker exec -i（不带 -t），容器内 socket 连接走 @'localhost' 授权
if ! docker exec -i "$CONTAINER" sh -c \
  'exec mysqldump --defaults-extra-file=/run/secrets/bak.cnf \
   --single-transaction --master-data=2 --routines --triggers \
   --set-gtid-purged=OFF '"$DB" | gzip > "$TMP"; then
  rm -f "$TMP"
  echo "❌ mysqldump 执行失败"
  exit 1
fi

# 完整性校验（gzip 可正常解压即视为有效）
if ! gzip -t "$TMP" 2>/dev/null; then
  rm -f "$TMP"
  echo "❌ 备份文件未通过 gzip 完整性校验"
  exit 1
fi
mv "$TMP" "$OUT"

# 保留最近 7 份
ls -1t "$DIR"/full_*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

# 异机/离线副本（可选，RSYNC_TARGET 在 .env 配置）
if [ -n "${RSYNC_TARGET:-}" ]; then
  rsync -a --delete "$DIR/" "$RSYNC_TARGET" || echo "⚠ 异机同步失败，本机备份仍然有效"
fi

echo "[$(date '+%F %T')] 备份完成: $OUT ($(du -h "$OUT" | cut -f1))"
