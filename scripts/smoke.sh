#!/usr/bin/env bash
# ============================================================================
# smart-park 真实容器冒烟（必须在一台有 docker 的机器上跑，客户现场或 staging）
#
#   用法：
#     ./scripts/smoke.sh              # 完整冒烟：config → up -d → 全部断言
#     SMOKE_UP=0 ./scripts/smoke.sh   # 只跑断言（栈已在运行，不执行 up -d）
#
#   设计原则：
#     1. 只做"启动 + 只读断言"，不写业务数据、不删任何东西、绝不动 mysql-data 卷
#     2. 任一断言失败 → 打印失败清单 → exit 1（CI/现场可直接当卡口用）
#     3. 失败不中断后续断言：一次跑完拿到全部问题，避免"修一个再跑一趟"
#
#   覆盖（对应 runbook 割接检查单"上线中"）：
#     ① docker compose config 语法/变量校验
#     ② 三容器 healthy
#     ③ Flyway：flyway_schema_history 恰好 1 行且 success=1
#     ④ 建表结果：业务表 11 张 / CHECK 约束 20 条
#     ⑤ /api/actuator/health 返回 UP
#     ⑥ 前端 / 200、SPA fallback（/visitors 直访 200）
#     ⑦ 3306 / 8080 均未对宿主机暴露（端口收敛）
# ============================================================================
set -uo pipefail

BASE_DIR="${BASE_DIR:-/opt/smart-park}"
cd "$BASE_DIR" || { echo "❌ 目录不存在: $BASE_DIR"; exit 1; }

# ---- 可覆盖参数 ----
WAIT_SECONDS="${SMOKE_WAIT_SECONDS:-240}"   # 见下方【为什么不是 120】
DO_UP="${SMOKE_UP:-1}"                      # 0 = 跳过 up -d，只做断言
NGINX_PORT="${NGINX_PORT:-80}"
SPA_ROUTE="${SMOKE_SPA_ROUTE:-/visitors}"   # 任意一个前端路由，用于验证 SPA fallback
MYSQL_C="${MYSQL_CONTAINER:-smart-park-mysql}"
BACKEND_C="${BACKEND_CONTAINER:-smart-park-backend}"
FRONTEND_C="${FRONTEND_CONTAINER:-smart-park-frontend}"
DB="${MYSQL_DATABASE:-smart_park}"
EXPECT_TABLES=11                            # 9 主表 + 2 归档表（不含 flyway_schema_history）
EXPECT_CHECKS=20
CRED="/run/secrets/bak.cnf"                 # 只读账号足够跑断言，不引入写权限

# 【为什么默认 240s 而不是 120s】backend healthcheck start_period=120s + interval=30s，
# 首个"healthy"判定最早出现在 ~150s；首次启动还叠加 Flyway 迁移耗时。取 120s 会
# 把健康的栈判成超时失败（假阳性），故默认 240s，现场可用 SMOKE_WAIT_SECONDS 覆盖。

PASS=0
FAILED=()
note()  { printf '  %s\n' "$1"; }
ok()    { PASS=$((PASS + 1)); printf '  ✅ %s\n' "$1"; }
bad()   { FAILED+=("$1");     printf '  ❌ %s\n' "$1"; }
chk()   { # chk <描述> <实际值> <期望值>
  if [ "$2" = "$3" ]; then ok "$1（=$2）"; else bad "$1：期望 $3，实际 $2"; fi
}
need()  { command -v "$1" >/dev/null 2>&1 || { echo "❌ 缺少命令: $1"; exit 2; }; }

# 容器内执行 SQL（走 socket + 只读凭证文件，密码不落命令行）
dbq() {
  docker exec -i "$MYSQL_C" sh -c \
    "exec mysql --defaults-extra-file=$CRED -N -B -e \"$1\"" 2>/dev/null
}

# 端口是否对宿主机开放（nc 缺失时用 bash /dev/tcp 兜底）
port_open() {
  if command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$1" >/dev/null 2>&1
  else
    (exec 3<>/dev/tcp/127.0.0.1/"$1") >/dev/null 2>&1
  fi
}

echo "=================================================================="
echo " smart-park 容器冒烟  $(date '+%F %T')  (BASE_DIR=$BASE_DIR)"
echo "=================================================================="

need docker
command -v curl >/dev/null 2>&1 || { echo "❌ 缺少 curl（HTTP 断言依赖）"; exit 2; }
[ -f .env ] || echo "  ⚠ 未找到 .env，将使用 compose 默认值（生产请确认已 cp .env.example .env）"
[ -f mysql/bak.cnf ] || { echo "❌ 缺少 mysql/bak.cnf，请先执行 scripts/init-secrets.sh"; exit 2; }

# ---------------------------------------------------------------- ① config
echo "① docker compose config"
if docker compose --env-file .env config >/dev/null 2>&1; then
  ok "compose 配置解析通过"
else
  bad "docker compose config 失败（YAML 语法 / 变量缺失）"
  echo "   定位：docker compose --env-file .env config"
fi

# ---------------------------------------------------------------- ② 启动并等健康
echo "② 启动并等待三容器 healthy（超时 ${WAIT_SECONDS}s）"
if [ "$DO_UP" = "1" ]; then
  docker compose --env-file .env up -d >/dev/null 2>&1 \
    && ok "up -d 已执行" \
    || bad "docker compose up -d 失败（看 docker compose logs）"
fi

DEADLINE=$(( $(date +%s) + WAIT_SECONDS ))
while :; do
  ALL_UP=1
  for c in "$MYSQL_C" "$BACKEND_C" "$FRONTEND_C"; do
    st=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo missing)
    [ "$st" = "healthy" ] || ALL_UP=0
  done
  [ "$ALL_UP" = "1" ] && break
  [ "$(date +%s)" -ge "$DEADLINE" ] && break
  sleep 5
done

for c in "$MYSQL_C" "$BACKEND_C" "$FRONTEND_C"; do
  st=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo missing)
  chk "容器 $c 健康状态" "$st" "healthy"
done
if [ "$ALL_UP" != "1" ]; then
  note "    排障顺序：一查 curl、二查 actuator、三查应用；docker compose logs <service>"
fi

# ---------------------------------------------------------------- ③ Flyway
echo "③ Flyway 迁移结果"
FW_ROWS=$(dbq "SELECT COUNT(*) FROM ${DB}.flyway_schema_history;")
FW_OK=$(dbq "SELECT COUNT(*) FROM ${DB}.flyway_schema_history WHERE success=1 AND version='1';")
chk "flyway_schema_history 行数" "${FW_ROWS:-ERR}" "1"
chk "V1 迁移 success=1 行数" "${FW_OK:-ERR}" "1"
if [ "${FW_OK:-ERR}" != "1" ]; then
  note "    若报 1045：docker exec 走 socket，账号需 @'localhost' 授权（见 mysql/init/01-accounts.sh）"
fi

# ---------------------------------------------------------------- ④ 建表结果
echo "④ 建表结果（业务表 / CHECK 约束）"
TBL=$(dbq "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='${DB}' AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history';")
chk "业务表数量（9 主表 + 2 归档）" "${TBL:-ERR}" "$EXPECT_TABLES"

CK=$(dbq "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='${DB}' AND CONSTRAINT_TYPE='CHECK';")
chk "CHECK 约束数量" "${CK:-ERR}" "$EXPECT_CHECKS"
if [ "${CK:-0}" = "0" ]; then
  note "    🔴 CHECK=0 说明 MySQL < 8.0.16（CHECK 被静默忽略），必须停上线先升 MySQL"
fi

# ---------------------------------------------------------------- ⑤ 后端健康
echo "⑤ 后端健康端点"
HEALTH_CODE=$(curl -s -o /tmp/smoke_health.json -w '%{http_code}' \
  "http://127.0.0.1:${NGINX_PORT}/api/actuator/health" 2>/dev/null || echo 000)
chk "/api/actuator/health HTTP 状态码" "$HEALTH_CODE" "200"
if grep -q '"UP"' /tmp/smoke_health.json 2>/dev/null; then
  ok "health 内容含 UP"
else
  bad "health 内容不含 UP（实际: $(tr -d '\n' < /tmp/smoke_health.json 2>/dev/null | cut -c1-120)）"
fi
rm -f /tmp/smoke_health.json

# ---------------------------------------------------------------- ⑥ 前端
echo "⑥ 前端与 SPA fallback"
ROOT_CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${NGINX_PORT}/" 2>/dev/null || echo 000)
chk "前端 / 状态码" "$ROOT_CODE" "200"
SPA_CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${NGINX_PORT}${SPA_ROUTE}" 2>/dev/null || echo 000)
chk "SPA fallback ${SPA_ROUTE} 状态码" "$SPA_CODE" "200"
if [ "$SPA_CODE" != "200" ]; then
  note "    404 说明 nginx.conf 的 try_files \$uri \$uri/ /index.html 未生效"
fi

# ---------------------------------------------------------------- ⑦ 端口收敛
echo "⑦ 端口收敛（数据库/后端不得对宿主机暴露）"
if port_open 3306; then bad "3306 对宿主机开放（应为不暴露）"; else ok "3306 未暴露（符合预期）"; fi
if port_open 8080; then bad "8080 对宿主机开放（应为不暴露）"; else ok "8080 未暴露（符合预期）"; fi

# ---------------------------------------------------------------- 汇总
echo "------------------------------------------------------------------"
echo " 通过 $PASS 项，失败 ${#FAILED[@]} 项"
if [ "${#FAILED[@]}" -gt 0 ]; then
  echo " 失败清单："
  for f in "${FAILED[@]}"; do echo "   - $f"; done
  echo "------------------------------------------------------------------"
  echo "❌ 冒烟未通过"
  exit 1
fi
echo "✅ 冒烟全部通过（栈可对外服务）"
echo "   提示：冒烟不会清理栈；如需销毁演练环境用 docker compose down -v（⚠ 会删数据卷）"
exit 0
