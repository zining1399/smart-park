#!/usr/bin/env bash
# ============================================================================
# 轻量巡检（客户现场无 Prometheus 时的保底监控）
#   crontab: * * * * * /opt/smart-park/scripts/inspect.sh
# 检查项：容器健康 / 后端健康端点 / 磁盘 / ERROR 日志增量 /
#         备份新鲜度 / 归档作业（每月 04:00，失败即静默，必须被巡检捕获）
# 产出：异常写入 logs/alerts.log（P0/P1/P2），可接邮件或 webhook 外发
# ============================================================================
set -uo pipefail

BASE_DIR="${BASE_DIR:-/opt/smart-park}"
ALERT="$BASE_DIR/logs/alerts.log"
BACKUP_MAX_AGE_H=25          # 备份文件超 25h 未更新即告警（含 crontab 未执行的情况）
ARCHIVE_MAX_AGE_D=40         # 归档每月执行一次：超过 40 天没有成功记录即告警
DISK_LIMIT=80                # 磁盘使用率告警阈值（%）

mkdir -p "$(dirname "$ALERT")"
cd "$BASE_DIR" || exit 1

ts() { date '+%F %T'; }
alert() { echo "$(ts) [$1] $2" >> "$ALERT"; }

# 1) 三容器健康状态
for c in smart-park-mysql smart-park-backend smart-park-frontend; do
  st=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo missing)
  [ "$st" = "healthy" ] || alert "P0" "容器 $c 状态=$st（排障顺序：一查 curl、二查 actuator、三查应用）"
done

# 2) 后端健康端点
curl -fsS http://127.0.0.1/api/actuator/health 2>/dev/null | grep -q '"UP"' \
  || alert "P0" "/api/actuator/health 非 UP"

# 3) 磁盘
DISK=$(df -P / | awk 'NR==2{gsub("%","",$5); print $5}')
if [ -n "${DISK:-}" ] && [ "$DISK" -ge "$DISK_LIMIT" ]; then
  alert "P1" "磁盘使用率 ${DISK}%（先清理 30 天前日志与旧备份，绝不动 mysql-data 卷）"
fi

# 4) 近 1 分钟新增 ERROR 日志
if find "$BASE_DIR/logs/backend" -name 'smart-park-error*.log' -mmin -1 2>/dev/null | grep -q .; then
  alert "P2" "出现新增 ERROR 日志（grep traceId 定位：grep '\"traceId\":\"xxx\"' logs/backend/smart-park.json）"
fi

# 5) 备份新鲜度（★ 防备份任务静默失败）
#    配套：backup.sh 先写 .part 临时名、校验通过后才 rename，
#    因此"文件存在"即"备份已完成"，半成品不会被这里误判为成功
LATEST=$(ls -1t "$BASE_DIR/backup"/full_*.sql.gz 2>/dev/null | head -1)
if [ -z "$LATEST" ]; then
  alert "P1" "未发现任何备份文件"
else
  AGE_H=$(( ( $(date +%s) - $(stat -c %Y "$LATEST") ) / 3600 ))
  [ "$AGE_H" -ge "$BACKUP_MAX_AGE_H" ] && alert "P1" "最新备份已 ${AGE_H}h（>${BACKUP_MAX_AGE_H}h）：$LATEST"
fi

# 6) 归档作业（每月 1 日 04:00；失败不报错也不影响业务，只能靠巡检兜住）
ARCHIVE_LOG="$BASE_DIR/logs/archive.log"
if [ -f "$ARCHIVE_LOG" ]; then
  # 6a. 失败留痕：archive.sh 的中止/残留用 ❌ ⚠ 标记，并向 alerts.log 写 [P1]
  if grep -q '❌\|⚠\|\[P1\]\|ERROR' "$ARCHIVE_LOG" 2>/dev/null; then
    alert "P1" "归档日志出现中止/残留/ERROR（归档失败会导致主表无限膨胀）：检查 $ARCHIVE_LOG"
  fi
  # 6b. 成功新鲜度：只认 "archive OK" 行——用"最后一次执行"的时间会掩盖"每次都失败"
  LAST_OK=$(grep 'archive OK' "$ARCHIVE_LOG" 2>/dev/null | tail -1 | grep -o '^\[[0-9-]* [0-9:]*\]' | tr -d '[]')
  if [ -n "$LAST_OK" ]; then
    OK_TS=$(date -d "$LAST_OK" +%s 2>/dev/null || echo 0)
    AGE_D=$(( ( $(date +%s) - OK_TS ) / 86400 ))
    [ "$AGE_D" -ge "$ARCHIVE_MAX_AGE_D" ] && alert "P2" "归档已 ${AGE_D} 天未成功（>${ARCHIVE_MAX_AGE_D} 天；crontab：0 4 1 * *）"
  else
    alert "P2" "归档日志无成功记录（crontab：0 4 1 * * scripts/archive.sh）"
  fi
else
  alert "P2" "尚未产生归档日志（首次归档为部署次月 1 日 04:00；若已过期请手工排查 crontab）"
fi

exit 0
