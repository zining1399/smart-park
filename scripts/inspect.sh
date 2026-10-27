#!/usr/bin/env bash
# ============================================================================
# 轻量巡检（客户现场无 Prometheus 时的保底监控）
#   crontab: * * * * * /opt/smart-park/scripts/inspect.sh
# 检查项：容器健康 / 后端健康端点 / 磁盘 / ERROR 日志增量 / 备份新鲜度
# 产出：异常写入 logs/alerts.log（P0/P1/P2），可接邮件或 webhook 外发
# ============================================================================
set -uo pipefail

BASE_DIR="${BASE_DIR:-/opt/smart-park}"
ALERT="$BASE_DIR/logs/alerts.log"
BACKUP_MAX_AGE_H=25          # 备份文件超 25h 未更新即告警（含 crontab 未执行的情况）
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
LATEST=$(ls -1t "$BASE_DIR/backup"/full_*.sql.gz 2>/dev/null | head -1)
if [ -z "$LATEST" ]; then
  alert "P1" "未发现任何备份文件"
else
  AGE_H=$(( ( $(date +%s) - $(stat -c %Y "$LATEST") ) / 3600 ))
  [ "$AGE_H" -ge "$BACKUP_MAX_AGE_H" ] && alert "P1" "最新备份已 ${AGE_H}h（>${BACKUP_MAX_AGE_H}h）：$LATEST"
fi

exit 0
