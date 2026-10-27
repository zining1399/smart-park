#!/usr/bin/env bash
# ============================================================================
# 归档作业（每月低峰执行一次）
#   crontab 例: 0 3 1 * * /opt/smart-park/scripts/archive.sh >> /opt/smart-park/logs/archive.log 2>&1
#
# 在线保留策略（由 data-engineer 定义）：
#   access_record  在线 24 个月 → access_record_archive
#   stock_record   在线 36 个月 → stock_record_archive
#
# 三条硬要求：
#   1. 执行前必须有当日可用备份（无备份即中止）
#   2. 避开 Flyway 发布窗口与备份窗口（02:00±1h）——本脚本默认排在每月 1 日 03:00
#   3. 分批 5000 行 + 批间 sleep 0.1s，禁止一次性大事务（长事务会拖垮主库）
#
# 驱动方式：使用容器内 mysql 客户端（镜像自带）经 docker exec 执行，
#           不使用 /dev/tcp 等 bash 专有特性（alpine 无 bash 会静默失败）
# ============================================================================
set -uo pipefail

BASE_DIR="${BASE_DIR:-/opt/smart-park}"
cd "$BASE_DIR" || exit 1

CONTAINER="${MYSQL_CONTAINER:-smart-park-mysql}"
DB="${MYSQL_DATABASE:-smart_park}"
BATCH=5000
SLEEP=0.1
LOG="$BASE_DIR/logs/archive.log"
mkdir -p "$(dirname "$LOG")"

ts() { date '+%F %T'; }
log() { echo "[$(ts)] $*"; }

# -------- ① 前置：容器健康 --------
STATUS=$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo missing)
[ "$STATUS" = "healthy" ] || { log "❌ 容器 $CONTAINER 状态=$STATUS，归档中止"; exit 1; }

# -------- ② 前置：当日必须有可用备份 --------
TODAY=$(date +%Y%m%d)
if ! ls -1 "$BASE_DIR/backup"/full_${TODAY}_*.sql.gz >/dev/null 2>&1; then
  log "❌ 未发现当日(${TODAY})备份，归档中止（归档前必须有可用备份）"
  exit 1
fi

mysql_exec() {
  docker exec -i "$CONTAINER" sh -c \
    "exec mysql --defaults-extra-file=/run/secrets/bak.cnf --default-character-set=utf8mb4 -N -B $DB"
}

# -------- ③ 分批搬运 + 分批删除 --------
archive_table() {
  local table="$1" archive="$2" months="$3" moved=0 batches=0
  log "开始归档：$table → $archive（在线保留 ${months} 个月）"

  while :; do
    # ① 分批搬运
    local n
    n=$(docker exec -i "$CONTAINER" sh -c \
      "exec mysql --defaults-extra-file=/run/secrets/bak.cnf --default-character-set=utf8mb4 $DB" <<SQL 2>>"$LOG"
INSERT INTO ${archive}
SELECT * FROM ${table}
WHERE pass_time < DATE_SUB(CURDATE(), INTERVAL ${months} MONTH) LIMIT ${BATCH};
SELECT ROW_COUNT();
SQL
)
    n=$(echo "$n" | tail -1 | tr -d '[:space:]')
    [ -z "$n" ] && n=0
    [ "$n" -eq 0 ] && break

    # ② 确认搬运成功后再分批删除
    docker exec -i "$CONTAINER" sh -c \
      "exec mysql --defaults-extra-file=/run/secrets/bak.cnf --default-character-set=utf8mb4 $DB" >/dev/null 2>>"$LOG" <<SQL
DELETE FROM ${table}
WHERE pass_time < DATE_SUB(CURDATE(), INTERVAL ${months} MONTH) LIMIT ${BATCH};
SQL

    moved=$((moved + n))
    batches=$((batches + 1))
    sleep "$SLEEP"                    # 让出 IO，避免长事务拖垮主库
  done

  # 校验：源表不应再有超出保留期的行
  local remain
  remain=$(docker exec -i "$CONTAINER" sh -c \
    "exec mysql --defaults-extra-file=/run/secrets/bak.cnf --default-character-set=utf8mb4 -N -B $DB" <<SQL 2>>"$LOG"
SELECT COUNT(*) FROM ${table} WHERE pass_time < DATE_SUB(CURDATE(), INTERVAL ${months} MONTH);
SQL
)
  log "✅ $table 归档完成：搬运 ${moved} 行 / ${batches} 批；残留 ${remain:-?} 行（应为 0）"
  [ "${remain:-1}" = "0" ] || { log "⚠ $table 归档后仍有残留 ${remain} 行，请人工核对"; return 1; }
  return 0
}

rc=0
archive_table access_record access_record_archive 24 || rc=1
archive_table stock_record  stock_record_archive  36 || rc=1

# -------- ④ 结果记录（归档失败是静默的，必须留痕并被巡检捕获）--------
if [ "$rc" -eq 0 ]; then
  log "archive OK"
else
  echo "[$(date '+%F %T')] [P1] 归档作业异常，详见 $LOG" >> "$BASE_DIR/logs/alerts.log"
fi

# 说明：InnoDB 删行后空间会被复用，默认不做 OPTIMIZE TABLE（千万行级重建耗时以小时计）。
# 确需把磁盘还给 OS 时，在低峰单独执行，主从环境需先确认从库延迟。
exit "$rc"
