#!/usr/bin/env bash
# ============================================================================
# 归档作业（每月低峰执行一次）
#   crontab 例: 0 4 1 * * /opt/smart-park/scripts/archive.sh >> /opt/smart-park/logs/archive.log 2>&1
#   排 04:00 的原因：备份 02:30 起，1.5GB 库 dump 常跑 30 分钟以上，排 03:00 会与备份重叠
#
# 在线保留策略（由 data-engineer 定义，与 docs/ops/archive.sql 严格一致）：
#   access_record 在线 24 个月，时间列 pass_time
#   stock_record  在线 36 个月，时间列 create_time，附加"已完结"条件
#     （type='IN' 或 return_time IS NOT NULL —— 未归还的 OUT 单必须留在线表）
#
# 四条硬要求：
#   1. 执行前必须有当日【已完成】的备份（无备份 / 备份仍在跑 / 有 .part 半成品 → 中止）
#   2. 避开备份窗口与 Flyway 发布窗口
#   3. 分批 5000 行 + 批间 sleep 0.1s，禁止一次性大事务
#   4. 每批 INSERT 与 DELETE 在同一事务内完成，且 ORDER BY <时间列>, id 保证搬=删
#
# 凭证：用 app.cnf（应用账号：INSERT/DELETE 具备，无 DDL/DROP）
#        —— 不能用 bak.cnf（只读，会 1142），也不用 mig.cnf（带 DDL，不该给高频作业）
# ============================================================================
set -uo pipefail

BASE_DIR="${BASE_DIR:-/opt/smart-park}"
cd "$BASE_DIR" || exit 1

CONTAINER="${MYSQL_CONTAINER:-smart-park-mysql}"
DB="${MYSQL_DATABASE:-smart_park}"
CRED="/run/secrets/app.cnf"
BATCH="${ARCHIVE_BATCH:-5000}"
SLEEP=0.1
LOG="$BASE_DIR/logs/archive.log"
mkdir -p "$(dirname "$LOG")"

ts() { date '+%F %T'; }
log() { echo "[$(ts)] $*"; }

# -------- ① 前置：容器健康 --------
STATUS=$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo missing)
[ "$STATUS" = "healthy" ] || { log "❌ 容器 $CONTAINER 状态=$STATUS，归档中止"; exit 1; }

# -------- ② 前置：当日必须有"已完成"的备份 --------
TODAY=$(date +%Y%m%d)
# 2a. 备份进程仍在跑即中止："备份文件已存在" ≠ "备份已结束"
if pgrep -f mysqldump >/dev/null 2>&1; then
  log "❌ 检测到 mysqldump 进程仍在运行，归档中止（避免与 --single-transaction 备份并发）"
  exit 1
fi
# 2b. 必须存在当日备份文件
if ! ls -1 "$BASE_DIR/backup"/full_${TODAY}_*.sql.gz >/dev/null 2>&1; then
  log "❌ 未发现当日(${TODAY})备份，归档中止（归档前必须有可用备份）"
  exit 1
fi
# 2c. 半成品检测：*.part 存在说明备份尚未完成（backup.sh 先写 .part 再 rename）
if ls -1 "$BASE_DIR/backup"/*.part >/dev/null 2>&1; then
  log "❌ 发现未完成的备份半成品(*.part)，归档中止"
  exit 1
fi

archive_table() {
  local table="$1" archive="$2" months="$3" time_col="$4" extra="$5"
  local moved=0 batches=0 n

  log "开始归档：$table → $archive（在线保留 ${months} 个月，时间列 ${time_col}）"

  while :; do
    # 单批：INSERT + DELETE 在同一事务内，ROW_COUNT() 在 COMMIT 前取
    n=$(docker exec -i "$CONTAINER" sh -c \
      "exec mysql --defaults-extra-file=$CRED --default-character-set=utf8mb4 -N -B $DB" <<SQL 2>>"$LOG"
SET SESSION autocommit = 0;
SET @cutoff = DATE_SUB(NOW(), INTERVAL ${months} MONTH);
START TRANSACTION;
INSERT INTO ${archive}
SELECT * FROM ${table}
WHERE ${time_col} < @cutoff ${extra}
ORDER BY ${time_col}, id
LIMIT ${BATCH};
DELETE FROM ${table}
WHERE ${time_col} < @cutoff ${extra}
ORDER BY ${time_col}, id
LIMIT ${BATCH};
SELECT ROW_COUNT() AS 本批搬运行数;
COMMIT;
SQL
)
    n=$(echo "$n" | tail -1 | tr -d '[:space:]')
    [ -z "$n" ] && n=0
    [ "$n" -eq 0 ] && break

    moved=$((moved + n))
    batches=$((batches + 1))
    sleep "$SLEEP"                    # 让出 IO，避免长事务顶住主库
  done

  # 校验：主表不应再有满足归档条件的行
  local remain
  remain=$(docker exec -i "$CONTAINER" sh -c \
    "exec mysql --defaults-extra-file=$CRED --default-character-set=utf8mb4 -N -B $DB" <<SQL 2>>"$LOG"
SELECT COUNT(*) FROM ${table} WHERE ${time_col} < DATE_SUB(NOW(), INTERVAL ${months} MONTH) ${extra};
SQL
)
  remain=$(echo "$remain" | tail -1 | tr -d '[:space:]')
  log "✅ $table 归档完成：搬运 ${moved} 行 / ${batches} 批；残留 ${remain:-?} 行（应为 0）"
  [ "${remain:-1}" = "0" ] || { log "⚠ $table 归档后仍有残留 ${remain} 行，请人工核对"; return 1; }
  return 0
}

rc=0
archive_table access_record access_record_archive 24 "pass_time"  "" || rc=1
archive_table stock_record  stock_record_archive  36 "create_time" "AND (type = 'IN' OR return_time IS NOT NULL)" || rc=1

# -------- ④ 结果记录（归档失败是静默的，必须留痕并被巡检捕获）--------
if [ "$rc" -eq 0 ]; then
  log "archive OK"
else
  echo "[$(date '+%F %T')] [P1] 归档作业异常，详见 $LOG" >> "$BASE_DIR/logs/alerts.log"
fi

# 说明：InnoDB 删行后空间会被复用，默认不做 OPTIMIZE TABLE（千万行级重建耗时以小时计）。
# 确需把磁盘还给 OS 时，在低峰单独执行，主从环境需先确认从库延迟。
exit "$rc"
